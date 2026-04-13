package com.example.linkfront

import android.content.Context
import android.os.Handler
import android.os.Looper
import android.util.Log
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import androidx.core.content.edit
import com.chaquo.python.PyObject
import com.chaquo.python.Python
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import org.json.JSONObject
import org.webrtc.*
import java.nio.ByteBuffer
import java.nio.charset.StandardCharsets

class WebRTCManager(
    private val context: Context,
    private val messageDao: MessageDao,
    private val peerDao: PeerDao,
    private val onConnectionRequest: (String, String, ByteArray, (Boolean) -> Unit) -> Unit,
    var onMessageReceived: ((String) -> Unit)? = null,
    var onStateChanged: ((String) -> Unit)? = null
) {
    private val tag = "WebRTCManager"
    private val scope = CoroutineScope(Dispatchers.IO)
    private val peerConnectionFactory: PeerConnectionFactory
    private var peerConnection: PeerConnection? = null
    private var dataChannel: DataChannel? = null
    
    private val py = Python.getInstance()
    private val linkModule = py.getModule("linkfront")

    private var identity: PyObject? = null
    var myUsername by mutableStateOf("")
        private set
    var myFingerprint by mutableStateOf("Unknown")
        private set

    private var signalServerSocket: java.net.ServerSocket? = null
    private var signalPort: Int = 0

    fun updateUsername(newName: String) {
        myUsername = newName
        context.getSharedPreferences("link_identity", Context.MODE_PRIVATE).edit {
            putString("my_username", newName)
        }
    }

    data class PeerIdentity(val publicKey: ByteArray, val createdAt: Long) {
        override fun equals(other: Any?): Boolean {
            if (this === other) return true
            if (other !is PeerIdentity) return false
            return publicKey.contentEquals(other.publicKey) && createdAt == other.createdAt
        }
        override fun hashCode(): Int {
            var result = publicKey.contentHashCode()
            result = 31 * result + createdAt.hashCode()
            return result
        }
    }

    private val trustedPeers = mutableMapOf<String, PeerIdentity>()

    private var ephemeralPrivateKey: ByteArray? = null
    private var ephemeralPublicKey: ByteArray? = null
    var peerUsername: String by mutableStateOf("Waiting for peer...")
        private set
    var peerFingerprint: String? by mutableStateOf(null)
        private set
    var connectionStatus by mutableStateOf("Disconnected")
        private set
    private var session: PyObject? = null
    private var onIceGatheringComplete: ((String) -> Unit)? = null
    private val gatheringHandler = Handler(Looper.getMainLooper())
    private val gatheringTimeoutRunnable = Runnable {
        finishGathering()
    }

    private fun finishGathering() {
        gatheringHandler.removeCallbacks(gatheringTimeoutRunnable)
        val localDescription = peerConnection?.localDescription
        if (localDescription != null && onIceGatheringComplete != null) {
            Log.d(tag, "Gathering finished early (or complete). Sending SDP.")
            val thinnedSdp = thinSdp(localDescription.description)
            onIceGatheringComplete?.invoke(thinnedSdp)
            onIceGatheringComplete = null
        }
    }

    private fun thinSdp(sdp: String): String {
        val lines = sdp.lines()
        val filtered = mutableListOf<String>()
        var skipCurrentSection = false
        
        for (line in lines) {
            val trimmed = line.trim()
            if (trimmed.isEmpty()) continue
            
            if (trimmed.startsWith("m=")) {
                // Skip audio/video media sections
                skipCurrentSection = trimmed.startsWith("m=audio") || trimmed.startsWith("m=video")
            }
            
            if (!skipCurrentSection) {
                // Remove loopback candidates and non-essential attributes
                if (trimmed.startsWith("a=candidate:")) {
                    if (trimmed.contains("127.0.0.1") || trimmed.contains("::1") || trimmed.contains("localhost")) {
                        continue
                    }
                    // Aggressively thin candidate lines by removing optional parameters
                    val parts = trimmed.split(" ")
                    val essentialParts = mutableListOf<String>()
                    var i = 0
                    while (i < parts.size) {
                        val p = parts[i]
                        if (p == "generation" || p == "network-id" || p == "network-cost") {
                            i += 2
                            continue
                        }
                        essentialParts.add(p)
                        i++
                    }
                    filtered.add(essentialParts.joinToString(" "))
                    continue
                }

                val blacklist = listOf(
                    "a=extmap:", "a=rtcp-fb:", "a=msid:", "a=ssrc:",
                    "a=mid:audio", "a=mid:video",
                    "a=" +
                            "max-ptime:", "a=ptime:"
                )

                if (blacklist.any { trimmed.startsWith(it) }) continue

                filtered.add(trimmed)
            }
        }
        val result = filtered.joinToString("\r\n") + "\r\n"
        Log.d(tag, "Thinned SDP length: ${result.length} (Original: ${sdp.length})")
        return result
    }

    private fun createRtcConfig(): PeerConnection.RTCConfiguration {
        val iceServers = listOf(
            PeerConnection.IceServer.builder("stun:stun.l.google.com:19302").createIceServer(),
            PeerConnection.IceServer.builder("stun:stun1.l.google.com:19302").createIceServer(),
            PeerConnection.IceServer.builder("stun:stun2.l.google.com:19302").createIceServer(),
            PeerConnection.IceServer.builder("stun:stun3.l.google.com:19302").createIceServer(),
            PeerConnection.IceServer.builder("stun:stun4.l.google.com:19302").createIceServer(),
            PeerConnection.IceServer.builder("turn:openrelay.metered.ca:443?transport=tcp")
                .setUsername("openrelayproject")
                .setPassword("openrelayproject")
                .createIceServer(),
            PeerConnection.IceServer.builder("turn:openrelay.metered.ca:80")
                .setUsername("openrelayproject")
                .setPassword("openrelayproject")
                .createIceServer(),
            PeerConnection.IceServer.builder("stun:iphone-stun.strato-iphone.de:3478").createIceServer(),
            PeerConnection.IceServer.builder("stun:stun.ekiga.net").createIceServer(),
            PeerConnection.IceServer.builder("stun:stun.schlund.de").createIceServer(),
            PeerConnection.IceServer.builder("stun:stun.voiparound.com").createIceServer(),
            PeerConnection.IceServer.builder("stun:stun.voipbuster.com").createIceServer(),
            PeerConnection.IceServer.builder("stun:stun.voipstunt.com").createIceServer(),
            PeerConnection.IceServer.builder("stun:stun.voxgratia.org").createIceServer()
        )
        val rtcConfig = PeerConnection.RTCConfiguration(iceServers)
        rtcConfig.sdpSemantics = PeerConnection.SdpSemantics.UNIFIED_PLAN
        rtcConfig.continualGatheringPolicy = PeerConnection.ContinualGatheringPolicy.GATHER_CONTINUALLY
        rtcConfig.iceTransportsType = PeerConnection.IceTransportsType.ALL
        return rtcConfig
    }

    fun renewConnection() {
        Log.d(tag, "Renewing connection state...")
        prepareForNewConnection()
        peerUsername = "Waiting for peer..."
        peerFingerprint = null
        connectionStatus = "Disconnected"
        publishMyAddress()
        onStateChanged?.invoke("Connection Reset")
    }

    fun prepareForNewConnection() {
        dataChannel?.dispose()
        dataChannel = null
        peerConnection?.close()
        peerConnection?.dispose()
        peerConnection = createPeerConnection(createRtcConfig())
        session = null
        connectionStatus = "Disconnected"
    }

    init {
        val prefs = context.getSharedPreferences("link_identity", Context.MODE_PRIVATE)
        myUsername = prefs.getString("my_username", "UnknownUser") ?: "UnknownUser"
        
        val privateKeyHex = prefs.getString("private_key_hex", null)
        if (privateKeyHex != null) {
            // Pass the hex string directly to Python Identity(seed=hex)
            identity = linkModule["Identity"]?.call(privateKeyHex)
        } else {
            // Generate new identity if none exists
            val newId = linkModule["Identity"]?.call()
            identity = newId
            val newPrivateKeyHex = newId?.callAttr("get_private_key_hex")?.toString()
            prefs.edit(commit = true) {
                putString("private_key_hex", newPrivateKeyHex)
            }
        }
        myFingerprint = getFingerprint()

        PeerConnectionFactory.initialize(
            PeerConnectionFactory.InitializationOptions.builder(context)
                .createInitializationOptions()
        )

        val options = PeerConnectionFactory.Options()
        peerConnectionFactory = PeerConnectionFactory.builder()
            .setOptions(options)
            .createPeerConnectionFactory()

        peerConnection = createPeerConnection(createRtcConfig())

        startSignalListener()
        
        // Start the self-healing DHT loop
        startDhtHeartbeat()

        scope.launch {
            peerDao.getAllPeers().collect { peers ->
                peers.forEach { peer ->
                    trustedPeers[peer.username] = PeerIdentity(peer.identityKey, 0L)
                }
            }
        }
    }

    private fun startDhtHeartbeat() {
        scope.launch(Dispatchers.IO) {
            while (true) {
                try {
                    val status = getDhtStatus()
                    val isConnected = if (status != null) JSONObject(status).optBoolean("is_connected") else false
                    
                    // Always try to publish if we aren't "Connected" in WebRTC, 
                    // or just periodically (every 5 mins) to keep the DHT entry alive.
                    if (connectionStatus != "Connected" || !isConnected) {
                        Log.d(tag, "DHT Heartbeat: Publishing address...")
                        publishMyAddress()
                    }
                } catch (e: Exception) {
                    Log.e(tag, "Heartbeat error: ${e.message}")
                }
                // Refresh every 5 minutes
                kotlinx.coroutines.delay(5 * 60 * 1000)
            }
        }
    }

    private fun startSignalListener() {
        scope.launch(Dispatchers.IO) {
            try {
                signalServerSocket = java.net.ServerSocket(0)
                signalPort = signalServerSocket!!.localPort
                Log.d(tag, "Signal Listener started on port $signalPort")
                
                while (true) {
                    val client = signalServerSocket?.accept() ?: break
                    scope.launch(Dispatchers.IO) {
                        try {
                            val input = client.getInputStream().bufferedReader().readText()
                            val json = JSONObject(input)
                            val isEncrypted = json.optBoolean("encrypted", false)
                            var sdp = json.getString("sdp")
                            val type = json.getString("type")
                            
                            if (isEncrypted) {
                                try {
                                    val cryptoModule = Python.getInstance().getModule("linkfront.crypto")
                                    val myPrivKey = identity?.callAttr("get_encryption_private_key_bytes")
                                    sdp = cryptoModule.callAttr("decrypt_with_my_key", sdp, myPrivKey).toString()
                                    Log.d(tag, "Decrypted incoming $type via direct socket")
                                } catch (e: Exception) {
                                    Log.e(tag, "Failed to decrypt direct socket SDP: ${e.message}")
                                }
                            }
                            
                            if (type == "offer") {
                                handleRemoteSdp(sdp, SessionDescription.Type.OFFER) { answer ->
                                    val response = JSONObject()
                                    response.put("type", "answer")
                                    // Encrypt answer if we can find the peer's key (this is harder for direct offers without a QR)
                                    // For now, we mainly use direct for ANSWERS (the "one-scan" backchannel)
                                    response.put("sdp", answer)
                                    client.getOutputStream().write(response.toString().toByteArray())
                                    client.getOutputStream().flush()
                                }
                            } else if (type == "answer") {
                                handleRemoteSdp(sdp, SessionDescription.Type.ANSWER, null)
                            }
                        } catch (_: Exception) {
                            Log.e(tag, "Signal handling error: Failed to process client signal")
                        } finally {
                            client.close()
                        }
                    }
                }
            } catch (_: Exception) {
                Log.e(tag, "Signal Listener error: Server socket failure")
            }
        }
    }

    fun publishMyAddress() {
        scope.launch(Dispatchers.IO) {
            try {
                val publicIp = linkModule.callAttr("get_public_ip")?.toString()
                val myPublicKey = identity?.callAttr("get_public_key_bytes")?.toJava(ByteArray::class.java)
                val fingerprint = getFingerprint(myPublicKey)
                
                if (publicIp != null) {
                    Log.d(tag, "Publishing to DHT: $fingerprint at $publicIp:$signalPort")
                    linkModule.callAttr("publish_address", fingerprint, publicIp, signalPort)
                }
            } catch (_: Exception) {
                Log.e(tag, "DHT Publish error: Background publication failed")
            }
        }
    }

    fun connectToPeerViaDHT(fingerprint: String) {
        scope.launch(Dispatchers.IO) {
            try {
                val result = linkModule.callAttr("lookup_address", fingerprint)
                if (result != null) {
                    val map = result.asMap()
                    val ip = map[PyObject.fromJava("ip")]?.toString()
                    val port = map[PyObject.fromJava("port")]?.toInt()
                    
                    if (ip != null && port != null) {
                        Log.d(tag, "Peer found at $ip:$port. Initiating...")
                        createOffer { offerSdp ->
                            sendOfferToAddress(ip, port, offerSdp)
                        }
                    }
                }
            } catch (_: Exception) {
                Log.e(tag, "DHT Connect error: Node lookup failed")
            }
        }
    }

    private fun sendOfferToAddress(ip: String, port: Int, sdp: String) {
        scope.launch(Dispatchers.IO) {
            try {
                val socket = java.net.Socket(ip, port)
                val request = JSONObject()
                request.put("type", "offer")
                request.put("sdp", sdp)
                
                socket.getOutputStream().write(request.toString().toByteArray())
                socket.getOutputStream().flush()
                
                val responseText = socket.getInputStream().bufferedReader().readText()
                val responseJson = JSONObject(responseText)
                val answerSdp = responseJson.getString("sdp")
                
                handleRemoteSdp(answerSdp, SessionDescription.Type.ANSWER, null)
                socket.close()
                Log.d(tag, "Connection via DHT established")
            } catch (_: Exception) {
                Log.e(tag, "Failed to send offer to $ip:$port")
            }
        }
    }

    private fun createPeerConnection(config: PeerConnection.RTCConfiguration): PeerConnection? {
        return peerConnectionFactory.createPeerConnection(config, object : PeerConnection.Observer {
            override fun onIceCandidate(candidate: IceCandidate) {
                Log.d(tag, "New ICE Candidate: $candidate")
            }
            override fun onIceGatheringChange(state: PeerConnection.IceGatheringState) {
                if (state == PeerConnection.IceGatheringState.COMPLETE) finishGathering()
            }
            override fun onSignalingChange(state: PeerConnection.SignalingState) {}
            override fun onIceConnectionChange(state: PeerConnection.IceConnectionState) {
                connectionStatus = when(state) {
                    PeerConnection.IceConnectionState.CONNECTED,
                    PeerConnection.IceConnectionState.COMPLETED -> "Verifying..."
                    PeerConnection.IceConnectionState.DISCONNECTED -> "Reconnecting..."
                    PeerConnection.IceConnectionState.FAILED,
                    PeerConnection.IceConnectionState.CLOSED -> {
                        val fingerprint = peerFingerprint
                        if (fingerprint != null) {
                            scope.launch {
                                messageDao.markPendingAsFailed(fingerprint)
                            }
                        }
                        "Disconnected"
                    }
                    else -> state.name
                }
                onStateChanged?.invoke("ICE: $state")
            }
            override fun onIceConnectionReceivingChange(p0: Boolean) {}
            override fun onIceCandidatesRemoved(p0: Array<out IceCandidate>) {}
            override fun onDataChannel(channel: DataChannel) {
                setupDataChannel(channel)
            }
            override fun onAddStream(p0: MediaStream) {}
            override fun onRemoveStream(p0: MediaStream) {}
            override fun onRenegotiationNeeded() {}
            override fun onAddTrack(p0: RtpReceiver, p1: Array<out MediaStream>) {}
        })
    }

    private fun setupDataChannel(channel: DataChannel) {
        Log.d(tag, "setupDataChannel: label=${channel.label()}")
        dataChannel = channel
        dataChannel?.registerObserver(object : DataChannel.Observer {
            override fun onBufferedAmountChange(p0: Long) {}
            override fun onStateChange() {
                val state = dataChannel?.state()
                Log.d(tag, "DataChannel State Change: $state")
                if (state == DataChannel.State.OPEN) {
                    sendHandshake()
                } else if (state == DataChannel.State.CLOSED || state == DataChannel.State.CLOSING) {
                    val fingerprint = peerFingerprint
                    if (fingerprint != null) {
                        scope.launch {
                            messageDao.markPendingAsFailed(fingerprint)
                        }
                    }
                    connectionStatus = "Disconnected"
                }
            }
            override fun onMessage(buffer: DataChannel.Buffer) {
                onReceive(buffer)
            }
        })
    }

    fun initDataChannel() {
        val init = DataChannel.Init()
        val channel = peerConnection?.createDataChannel("chat", init)
        if (channel != null) setupDataChannel(channel)
    }

    fun createOffer(callback: (String) -> Unit) {
        onIceGatheringComplete = callback
        initDataChannel()
        
        // Start watching the DHT Post Box for an answer
        scope.launch(Dispatchers.IO) {
            val myFingerprint = getFingerprint()
            val postBoxKey = "answer_for_$myFingerprint"
            Log.d(tag, "Watching DHT Post Box: $postBoxKey")
            
            // Poll for 60 seconds
            for (i in 1..60) {
                val encryptedAnswer = linkModule.callAttr("get_value", postBoxKey)
                if (encryptedAnswer != null && encryptedAnswer.toString() != "None") {
                    try {
                        val cryptoModule = Python.getInstance().getModule("linkfront.crypto")
                        val myPrivKey = identity?.callAttr("get_encryption_private_key_bytes")
                        val decryptedAnswer = cryptoModule.callAttr("decrypt_with_my_key", encryptedAnswer, myPrivKey).toString()
                        
                        Log.d(tag, "Received and Decrypted Answer via DHT Post Box!")
                        handleRemoteSdp(decryptedAnswer, SessionDescription.Type.ANSWER, null)
                        // Clean up the post box
                        linkModule.callAttr("put_value", postBoxKey, "None")
                        break
                    } catch (e: Exception) {
                        Log.e(tag, "Failed to decrypt DHT answer: ${e.message}")
                    }
                }
                kotlinx.coroutines.delay(2000) // Poll every 2 seconds
            }
        }

        val constraints = MediaConstraints()
        constraints.mandatory.add(MediaConstraints.KeyValuePair("OfferToReceiveAudio", "false"))
        constraints.mandatory.add(MediaConstraints.KeyValuePair("OfferToReceiveVideo", "false"))

        // Increase timeout for ICE gathering to ensure we get candidates
        gatheringHandler.postDelayed(gatheringTimeoutRunnable, 6000)
        peerConnection?.createOffer(object : SimpleSdpObserver() {
            override fun onCreateSuccess(p0: SessionDescription?) {
                if (p0 == null) {
                    Log.e(tag, "createOffer: SessionDescription is NULL")
                    return
                }
                Log.d(tag, "createOffer Success: Setting local description")
                peerConnection?.setLocalDescription(SimpleSdpObserver(), p0)
            }
        }, constraints)
    }

    fun handleRemoteSdp(sdpStr: String, type: SessionDescription.Type, onAnswerReady: ((String) -> Unit)? = null) {
        Log.d(tag, "handleRemoteSdp: type=$type, sdpLength=${sdpStr.length}")
        
        // If we're getting an OFFER while already in a session or half-open state, reset.
        // However, if we're scanning an ANSWER, we MUST NOT reset as we need the local state.
        if (type == SessionDescription.Type.OFFER) {
            prepareForNewConnection()
        }

        // Ensure SDP has proper line endings before passing to native
        val normalizedSdp = sdpStr.lines().map { it.trim() }.filter { it.isNotBlank() }.joinToString("\r\n") + "\r\n"
        val sdp = SessionDescription(type, normalizedSdp)
        peerConnection?.setRemoteDescription(object : SimpleSdpObserver() {
            override fun onSetSuccess() {
                if (type == SessionDescription.Type.OFFER) {
                    onIceGatheringComplete = onAnswerReady
                    gatheringHandler.postDelayed(gatheringTimeoutRunnable, 3000)
                    createAnswer()
                }
            }
        }, sdp)
    }

    private fun createAnswer() {
        val constraints = MediaConstraints()
        constraints.mandatory.add(MediaConstraints.KeyValuePair("OfferToReceiveAudio", "false"))
        constraints.mandatory.add(MediaConstraints.KeyValuePair("OfferToReceiveVideo", "false"))

        peerConnection?.createAnswer(object : SimpleSdpObserver() {
            override fun onCreateSuccess(p0: SessionDescription?) {
                if (p0 == null) {
                    Log.e(tag, "createAnswer: SessionDescription is NULL")
                    return
                }
                Log.d(tag, "createAnswer Success: Setting local description")
                peerConnection?.setLocalDescription(SimpleSdpObserver(), p0)
            }
        }, constraints)
    }

    fun getConnectionQrData(callback: (String) -> Unit) {
        prepareForNewConnection()
        createOffer { sdp ->
            val qrData = identity?.callAttr("get_connection_qr", myUsername, sdp, "offer").toString()
            callback(qrData)
        }
    }

    private fun trySendAnswerViaDHT(fingerprint: String, answerSdp: String) {
        scope.launch(Dispatchers.IO) {
            try {
                val statusJson = getDhtStatus()
                val isDhtConnected = if (statusJson != null) {
                    JSONObject(statusJson).optBoolean("is_connected", false)
                } else false

                if (!isDhtConnected) {
                    Log.w(tag, "DHT disconnected, skipping auto-answer")
                    onStateChanged?.invoke("DHT Down: Peer must scan Answer QR")
                    return@launch
                }

                // 1. Encrypt and drop the answer in the "Post Box"
                val peerPublicKey = trustedPeers[fingerprint]?.publicKey // This would be passed or looked up
                val postBoxKey = "answer_for_$fingerprint"
                
                if (peerPublicKey != null) {
                    val cryptoModule = Python.getInstance().getModule("linkfront.crypto")
                    val encryptedAnswer = cryptoModule.callAttr("encrypt_for_peer", answerSdp, peerPublicKey)
                    val putSuccess = linkModule.callAttr("put_value", postBoxKey, encryptedAnswer).toBoolean()
                    if (putSuccess) {
                        Log.d(tag, "Encrypted Answer dropped in DHT Post Box for $fingerprint")
                    }
                } else {
                    // Fallback to unencrypted if for some reason we don't have the key
                    linkModule.callAttr("put_value", postBoxKey, answerSdp)
                }

                // 2. Also try the direct socket (faster if it works)
                var result: PyObject? = null
                for (i in 1..5) { // Reduced retries since we have the Post Box now
                    result = linkModule.callAttr("lookup_address", fingerprint)
                    if (result != null) break
                    kotlinx.coroutines.delay(1000)
                }

                if (result != null) {
                    val map = result.asMap()
                    val ip = map[PyObject.fromJava("ip")]?.toString()
                    val port = map[PyObject.fromJava("port")]?.toInt()
                    if (ip != null && port != null) {
                        try {
                            val socket = java.net.Socket()
                            socket.connect(java.net.InetSocketAddress(ip, port), 2000)
                            val request = JSONObject()
                            request.put("type", "answer")
                            
                            val cryptoModule = Python.getInstance().getModule("linkfront.crypto")
                            // Search for the peer identity by fingerprint
                            val peerId = trustedPeers.values.find { getFingerprint(it.publicKey) == fingerprint }
                            if (peerId != null) {
                                val encryptedSdp = cryptoModule.callAttr("encrypt_for_peer", answerSdp, peerId.publicKey)
                                request.put("sdp", encryptedSdp.toString())
                                request.put("encrypted", true)
                            } else {
                                request.put("sdp", answerSdp)
                                request.put("encrypted", false)
                            }

                            socket.getOutputStream().write(request.toString().toByteArray())
                            socket.getOutputStream().flush()
                            socket.close()
                            Log.d(tag, "Encrypted Answer delivered (Direct) to $ip:$port")
                            onStateChanged?.invoke("Answer delivered (Direct)")
                            return@launch
                        } catch (e: Exception) {
                            Log.d(tag, "Direct socket failed: ${e.message}")
                        }
                    }
                }
                
                onStateChanged?.invoke("Answer dropped in DHT. Waiting for peer...")
            } catch (e: Exception) {
                Log.e(tag, "DHT Answer logic error: ${e.message}")
            }
        }
    }

    fun getFingerprint(key: ByteArray? = null): String {
        return if (key == null) {
            identity?.callAttr("get_fingerprint")?.toString() ?: "UNKNOWN"
        } else {
            linkModule.callAttr("get_fingerprint", key).toString()
        }
    }

    fun processScannedQr(qrData: String, onAnswerReady: ((String) -> Unit)? = null) {
        try {
            Log.d(tag, "Processing scanned QR data...")
            val result = linkModule.callAttr("parse_qr_data", qrData)
            val scannedPeerUsername = result.asList()[0].toString()
            val peerIdentityKey = result.asList()[1].toJava(ByteArray::class.java)
            val timestamp = result.asList()[2].toLong()
            
            val sdpObj = result.asList()[3]
            val sdp = if (sdpObj == null || sdpObj.toString() == "None") null else sdpObj.toString()
            
            val sdpTypeObj = result.asList()[4]
            val sdpTypeStr = if (sdpTypeObj == null || sdpTypeObj.toString() == "None") null else sdpTypeObj.toString()
            
            Log.d(tag, "Parsed QR: User=$scannedPeerUsername, HasSDP=${sdp != null}, Type=$sdpTypeStr")

            val existing = trustedPeers[scannedPeerUsername]
            if (existing != null && !existing.publicKey.contentEquals(peerIdentityKey)) {
                Log.w(tag, "Identity mismatch for $scannedPeerUsername!")
                return
            }

            trustedPeers[scannedPeerUsername] = PeerIdentity(peerIdentityKey, timestamp)
            
            if (sdp != null && sdpTypeStr != null) {
                val type = if (sdpTypeStr == "offer") SessionDescription.Type.OFFER else SessionDescription.Type.ANSWER
                Log.d(tag, "Setting remote SDP from QR ($type)")
                if (type == SessionDescription.Type.ANSWER) {
                    handleRemoteSdp(sdp, type, null)
                } else {
                    handleRemoteSdp(sdp, type) { answerSdp ->
                        val answerQr = identity?.callAttr("get_connection_qr", myUsername, answerSdp, "answer").toString()
                        onAnswerReady?.invoke(answerQr)
                        
                        // Attempt to send answer back via DHT for 1-scan experience
                        trySendAnswerViaDHT(getFingerprint(peerIdentityKey), answerSdp)
                    }
                }
            } else {
                Log.d(tag, "QR contained identity but no SDP. Waiting for DHT or manual offer.")
            }
        } catch (e: Exception) {
            Log.e(tag, "Failed to parse QR data: ${e.message}")
        }
    }

    private fun sendHandshake() {
        if (dataChannel?.state() != DataChannel.State.OPEN) {
            Log.w(tag, "sendHandshake: DataChannel not open (State: ${dataChannel?.state()})")
            return
        }
        Log.d(tag, "Sending Handshake...")
        val result = linkModule.callAttr("generate_keypair")
        ephemeralPrivateKey = result.asList()[0].toJava(ByteArray::class.java)
        ephemeralPublicKey = result.asList()[1].toJava(ByteArray::class.java)

        val json = JSONObject()
        json.put("type", "handshake")
        json.put("username", myUsername)
        json.put("pubkey", android.util.Base64.encodeToString(ephemeralPublicKey, android.util.Base64.NO_WRAP))
        json.put("identity_key", android.util.Base64.encodeToString(identity?.callAttr("get_public_key_bytes")?.toJava(ByteArray::class.java), android.util.Base64.NO_WRAP))
        json.put("timestamp", identity?.callAttr("get_created_at")?.toLong() ?: 0L)
        json.put("signature", android.util.Base64.encodeToString(identity?.callAttr("sign", ephemeralPublicKey)?.toJava(ByteArray::class.java), android.util.Base64.NO_WRAP))

        val packet = json.toString().toByteArray(StandardCharsets.UTF_8)
        dataChannel?.send(DataChannel.Buffer(ByteBuffer.wrap(packet), false))
    }

    private fun handleHandshake(json: JSONObject) {
        val hPeerUsername = json.getString("username")
        this.peerUsername = hPeerUsername
        val peerPubKey = android.util.Base64.decode(json.getString("pubkey"), android.util.Base64.DEFAULT)
        val signature = android.util.Base64.decode(json.getString("signature"), android.util.Base64.DEFAULT)
        val peerIdKey = android.util.Base64.decode(json.getString("identity_key"), android.util.Base64.DEFAULT)
        val peerTimestamp = json.optLong("timestamp", 0L)

        val fingerprint = linkModule.callAttr("get_fingerprint", peerIdKey).toString()
        this.peerFingerprint = fingerprint
        val trusted = trustedPeers[hPeerUsername]

        if (trusted != null) {
            if (!peerIdKey.contentEquals(trusted.publicKey)) {
                dataChannel?.close()
                return
            }
            completeHandshake(peerPubKey, signature, trusted.publicKey)
        } else {
            onConnectionRequest(hPeerUsername, "$fingerprint (TS: $peerTimestamp)", peerIdKey) { accepted ->
                if (accepted) {
                    val finger = linkModule.callAttr("get_fingerprint", peerIdKey).toString()
                    scope.launch { peerDao.insert(PeerEntity(finger, hPeerUsername, peerIdKey)) }
                    trustedPeers[hPeerUsername] = PeerIdentity(peerIdKey, peerTimestamp)
                    completeHandshake(peerPubKey, signature, peerIdKey)
                } else dataChannel?.close()
            }
        }
    }

    private fun completeHandshake(peerPubKey: ByteArray, signature: ByteArray, idKey: ByteArray) {
        if (session != null) return
        val isValid = linkModule.callAttr("verify_signature", idKey, signature, peerPubKey).toBoolean()
        if (!isValid) return
        if (ephemeralPrivateKey == null) sendHandshake()

        val sharedKey = linkModule.callAttr("derive_shared", ephemeralPrivateKey, peerPubKey).toJava(ByteArray::class.java)
        session = linkModule["Session"]?.call(sharedKey)
        connectionStatus = "Connected"
        onStateChanged?.invoke("Secure Session Ready")
    }

    fun sendEncrypted(message: String) {
        val currentFingerprint = peerFingerprint ?: return
        scope.launch {
            val msgId = messageDao.insert(MessageEntity(
                peerFingerprint = currentFingerprint,
                text = message,
                isMe = true,
                messageType = "TEXT",
                transferStatus = "PENDING"
            )).toInt()

            try {
                val packet = JSONObject()
                packet.put("type", "chat")
                packet.put("id", msgId)
                packet.put("text", message)

                val encrypted = session?.callAttr("encrypt", packet.toString())?.toJava(ByteArray::class.java)
                if (encrypted != null && dataChannel?.state() == DataChannel.State.OPEN) {
                    dataChannel?.send(DataChannel.Buffer(ByteBuffer.wrap(encrypted), true))
                    // We wait for ACK for "Delivered" status, but for now mark as COMPLETED once sent
                    messageDao.updateTransferProgress(msgId, 100, "COMPLETED", "")
                } else {
                    messageDao.updateTransferProgress(msgId, 0, "FAILED", "")
                }
            } catch (_: Exception) {
                messageDao.updateTransferProgress(msgId, 0, "FAILED", "")
            }
        }
    }

    private fun sendAck(messageId: Int) {
        scope.launch {
            try {
                val packet = JSONObject()
                packet.put("type", "ack")
                packet.put("id", messageId)
                val encrypted = session?.callAttr("encrypt", packet.toString())?.toJava(ByteArray::class.java)
                if (encrypted != null && dataChannel?.state() == DataChannel.State.OPEN) {
                    dataChannel?.send(DataChannel.Buffer(ByteBuffer.wrap(encrypted), true))
                }
            } catch (_: Exception) {}
        }
    }

    fun getDhtStatus(): String? {
        return try {
            val dhtNode = py.getModule("linkfront.dht_node")
            val status = dhtNode.callAttr("get_dht_status").toString()
            val json = JSONObject(status)
            
            val isListening = json.optBoolean("protocol_listening")
            val neighbors = json.optInt("neighbor_count")

            if (isListening && neighbors == 0) {
                if (connectionStatus == "Disconnected") {
                    connectionStatus = "Restricted Network"
                }
                onStateChanged?.invoke("Restricted Network (UDP Blocked)")
            } else if (neighbors > 0 && connectionStatus == "Restricted Network") {
                connectionStatus = "Disconnected"
            }
            
            status
        } catch (_: Exception) {
            null
        }
    }

    private fun handleAck(json: JSONObject) {
        val msgId = json.getInt("id")
        scope.launch {
            messageDao.updateStatus(msgId, "DELIVERED")
        }
    }

    fun sendImage(imageBytes: ByteArray) {
        val currentFingerprint = peerFingerprint
        if (session == null || currentFingerprint == null) return

        scope.launch(Dispatchers.IO) {
            try {
                // 1. Create a local file to store the image
                val fileName = "img_${System.currentTimeMillis()}.jpg"
                val file = java.io.File(context.filesDir, fileName)
                file.writeBytes(imageBytes)

                // 2. Break into chunks and send
                val chunkSize = 32 * 1024 // 32KB chunks
                val totalChunks = (imageBytes.size + chunkSize - 1) / chunkSize
                val transferId = System.currentTimeMillis().toString()

                // 3. Save to DB with initial progress
                val messageId = messageDao.insert(MessageEntity(
                    peerFingerprint = currentFingerprint,
                    text = "[Image]",
                    isMe = true,
                    messageType = "IMAGE",
                    filePath = file.absolutePath,
                    transferStatus = "PENDING",
                    progress = 0
                )).toInt()

                for (i in 0 until totalChunks) {
                    val start = i * chunkSize
                    val end = minOf(start + chunkSize, imageBytes.size)
                    val chunkData = imageBytes.copyOfRange(start, end)
                    
                    val packet = JSONObject()
                    packet.put("type", "image_chunk")
                    packet.put("id", transferId)
                    packet.put("index", i)
                    packet.put("total", totalChunks)
                    packet.put("data", android.util.Base64.encodeToString(chunkData, android.util.Base64.NO_WRAP))

                    val encrypted = session?.callAttr("encrypt", packet.toString())?.toJava(ByteArray::class.java)
                    if (encrypted != null) {
                        dataChannel?.send(DataChannel.Buffer(ByteBuffer.wrap(encrypted), true))
                        val currentProgress = ((i + 1) * 100) / totalChunks
                        if (currentProgress == 100) {
                            messageDao.updateStatus(messageId, "COMPLETED")
                        } else {
                            messageDao.updateTransferProgress(messageId, currentProgress, "PENDING", file.absolutePath)
                        }
                    }
                    // Adjust delay to be smaller for better performance, but still prevent flooding
                    if (i % 5 == 0) kotlinx.coroutines.delay(10)
                }
            } catch (_: Exception) {
                Log.e(tag, "Failed to send image")
            }
        }
    }

    private val incomingImages = mutableMapOf<String, MutableMap<Int, ByteArray>>()
    private val incomingMessageIds = mutableMapOf<String, Long>()

    private fun handleImageChunk(json: JSONObject) {
        val transferId = json.getString("id")
        val index = json.getInt("index")
        val total = json.getInt("total")
        val data = android.util.Base64.decode(json.getString("data"), android.util.Base64.DEFAULT)

        val chunks = incomingImages.getOrPut(transferId) { mutableMapOf() }
        chunks[index] = data

        val currentFingerprint = peerFingerprint
        if (currentFingerprint != null) {
            val currentProgress = (chunks.size * 100) / total
            scope.launch {
                val existingId = incomingMessageIds[transferId]
                if (existingId == null) {
                    val fileName = "rcv_${transferId}.jpg"
                    val file = java.io.File(context.filesDir, fileName)
                    val newId = messageDao.insert(MessageEntity(
                        peerFingerprint = currentFingerprint,
                        text = "[Receiving Image...]",
                        isMe = false,
                        messageType = "IMAGE",
                        filePath = file.absolutePath,
                        transferStatus = "PENDING",
                        progress = currentProgress
                    ))
                    incomingMessageIds[transferId] = newId
                } else {
                    val fileName = "rcv_${transferId}.jpg"
                    val file = java.io.File(context.filesDir, fileName)
                    messageDao.updateTransferProgress(existingId.toInt(), currentProgress, "PENDING", file.absolutePath)
                }
            }
        }

        if (chunks.size == total) {
            // All chunks received, assemble image
            val out = java.io.ByteArrayOutputStream()
            for (i in 0 until total) {
                out.write(chunks[i]!!)
            }
            val fullImage = out.toByteArray()
            incomingImages.remove(transferId)

            val fileName = "rcv_${System.currentTimeMillis()}.jpg"
            val file = java.io.File(context.filesDir, fileName)
            file.writeBytes(fullImage)

            val currentFingerprint = peerFingerprint
            if (currentFingerprint != null) {
                scope.launch {
                    val existingId = incomingMessageIds.remove(transferId)
                    if (existingId != null) {
                        messageDao.updateTransferProgress(existingId.toInt(), 100, "COMPLETED", file.absolutePath)
                        sendAck(existingId.toInt())
                    } else {
                        val newId = messageDao.insert(MessageEntity(
                            peerFingerprint = currentFingerprint,
                            text = "[Image]",
                            isMe = false,
                            messageType = "IMAGE",
                            filePath = file.absolutePath,
                            transferStatus = "COMPLETED",
                            progress = 100
                        ))
                        sendAck(newId.toInt())
                    }
                    onMessageReceived?.invoke("[Image Received]")
                }
            }
        }
    }

    private fun onReceive(buffer: DataChannel.Buffer) {
        val data = ByteArray(buffer.data.remaining())
        buffer.data.get(data)
        try {
            val str = String(data, StandardCharsets.UTF_8)
            if (str.startsWith("{")) {
                val json = JSONObject(str)
                if (json.optString("type") == "handshake") {
                    handleHandshake(json)
                    return
                }
            }
        } catch (_: Exception) { /* Not a JSON/Handshake packet */ }

        try {
            val decrypted = session?.callAttr("decrypt", data).toString()
            Log.d(tag, "onReceive: decrypted length = ${decrypted.length}")
            
            if (decrypted.startsWith("{")) {
                val json = JSONObject(decrypted)
                val type = json.optString("type")
                
                if (type == "image_chunk") {
                    handleImageChunk(json)
                    return
                } else if (type == "chat") {
                    val msgId = json.getInt("id")
                    val text = json.getString("text")
                    val currentFingerprint = peerFingerprint
                    if (currentFingerprint != null) {
                        scope.launch { 
                            messageDao.insert(MessageEntity(peerFingerprint = currentFingerprint, text = text, isMe = false, messageType = "TEXT"))
                        }
                    }
                    sendAck(msgId)
                    onMessageReceived?.invoke(text)
                    return
                } else if (type == "ack") {
                    handleAck(json)
                    return
                }
            }

            // Fallback for raw text if old peer
            val currentFingerprint = peerFingerprint
            if (currentFingerprint != null) {
                scope.launch { 
                    messageDao.insert(MessageEntity(peerFingerprint = currentFingerprint, text = decrypted, isMe = false, messageType = "TEXT"))
                }
            }
            onMessageReceived?.invoke(decrypted)
        } catch (_: Exception) { Log.e(tag, "Decryption failed") }
    }
}

open class SimpleSdpObserver : SdpObserver {
    override fun onCreateSuccess(p0: SessionDescription?) {}
    override fun onSetSuccess() {}
    override fun onCreateFailure(p0: String?) { Log.e("WebRTC", "SDP Create Failed: $p0") }
    override fun onSetFailure(p0: String?) { Log.e("WebRTC", "SDP Set Failed: $p0") }
}
