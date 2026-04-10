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
        return sdp.split("\n").filter { line ->
            !line.startsWith("a=extmap:") &&
            !line.startsWith("a=rtcp-fb:") &&
            !line.startsWith("a=fmtp:") &&
            !line.startsWith("a=msid:") &&
            !line.startsWith("a=ssrc:") &&
            !line.startsWith("a=mid:") &&
            !line.startsWith("a=bundle-only") &&
            line.isNotBlank()
        }.joinToString("\n")
    }

    init {
        val prefs = context.getSharedPreferences("link_identity", Context.MODE_PRIVATE)
        val savedSeedBase64 = prefs.getString("identity_seed", null)
        val savedTimestamp = prefs.getLong("identity_timestamp", -1L)

        if (savedSeedBase64 != null && savedTimestamp != -1L) {
            val seed = android.util.Base64.decode(savedSeedBase64, android.util.Base64.DEFAULT)
            identity = linkModule["Identity"]?.call(seed, savedTimestamp)
            myUsername = prefs.getString("my_username", "User_" + (100..999).random())!!
        } else {
            identity = linkModule["Identity"]?.call()
            val seed = identity?.callAttr("get_seed_bytes")?.toJava(ByteArray::class.java)
            val timestamp = identity?.callAttr("get_created_at")?.toLong() ?: 0L
            myUsername = "User_" + (100..999).random()
            
            if (seed != null) {
                val seedBase64 = android.util.Base64.encodeToString(seed, android.util.Base64.NO_WRAP)
                prefs.edit {
                    putString("identity_seed", seedBase64)
                    putLong("identity_timestamp", timestamp)
                    putString("my_username", myUsername)
                }
            }
        }

        PeerConnectionFactory.initialize(
            PeerConnectionFactory.InitializationOptions.builder(context)
                .createInitializationOptions()
        )

        val options = PeerConnectionFactory.Options()
        peerConnectionFactory = PeerConnectionFactory.builder()
            .setOptions(options)
            .createPeerConnectionFactory()

        val iceServers = listOf(
            PeerConnection.IceServer.builder("stun:stun.l.google.com:19302").createIceServer()
        )
        val rtcConfig = PeerConnection.RTCConfiguration(iceServers)
        peerConnection = createPeerConnection(rtcConfig)

        startSignalListener()
        publishMyAddress()

        scope.launch {
            peerDao.getAllPeers().collect { peers ->
                peers.forEach { peer ->
                    trustedPeers[peer.username] = PeerIdentity(peer.identityKey, 0L)
                }
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
                            val sdp = json.getString("sdp")
                            val type = json.getString("type")
                            
                            if (type == "offer") {
                                handleRemoteSdp(sdp, SessionDescription.Type.OFFER) { answer ->
                                    val response = JSONObject()
                                    response.put("type", "answer")
                                    response.put("sdp", answer)
                                    client.getOutputStream().write(response.toString().toByteArray())
                                    client.getOutputStream().flush()
                                }
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

    private fun publishMyAddress() {
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
                    PeerConnection.IceConnectionState.CLOSED -> "Disconnected"
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
        dataChannel = channel
        dataChannel?.registerObserver(object : DataChannel.Observer {
            override fun onBufferedAmountChange(p0: Long) {}
            override fun onStateChange() {
                val state = dataChannel?.state()
                if (state == DataChannel.State.OPEN) {
                    sendHandshake()
                } else if (state == DataChannel.State.CLOSED || state == DataChannel.State.CLOSING) {
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
        gatheringHandler.postDelayed(gatheringTimeoutRunnable, 3000)
        peerConnection?.createOffer(object : SimpleSdpObserver() {
            override fun onCreateSuccess(p0: SessionDescription?) {
                peerConnection?.setLocalDescription(SimpleSdpObserver(), p0)
            }
        }, MediaConstraints())
    }

    fun handleRemoteSdp(sdpStr: String, type: SessionDescription.Type, onAnswerReady: ((String) -> Unit)? = null) {
        val sdp = SessionDescription(type, sdpStr)
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
        peerConnection?.createAnswer(object : SimpleSdpObserver() {
            override fun onCreateSuccess(p0: SessionDescription?) {
                peerConnection?.setLocalDescription(SimpleSdpObserver(), p0)
            }
        }, MediaConstraints())
    }

    fun getConnectionQrData(callback: (String) -> Unit) {
        createOffer { sdp ->
            val qrData = identity?.callAttr("get_connection_qr", myUsername, sdp, "offer").toString()
            callback(qrData)
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
            val result = linkModule.callAttr("parse_qr_data", qrData)
            val scannedPeerUsername = result.asList()[0].toString()
            val peerIdentityKey = result.asList()[1].toJava(ByteArray::class.java)
            val timestamp = result.asList()[2].toLong()
            val sdp = result.asList()[3]?.toString()
            val sdpTypeStr = result.asList()[4]?.toString()
            
            val existing = trustedPeers[scannedPeerUsername]
            if (existing != null && !existing.publicKey.contentEquals(peerIdentityKey)) return

            trustedPeers[scannedPeerUsername] = PeerIdentity(peerIdentityKey, timestamp)
            
            if (sdp != null && sdpTypeStr != null) {
                val type = if (sdpTypeStr == "offer") SessionDescription.Type.OFFER else SessionDescription.Type.ANSWER
                handleRemoteSdp(sdp, type) { answerSdp ->
                    val answerQr = identity?.callAttr("get_connection_qr", myUsername, answerSdp, "answer").toString()
                    onAnswerReady?.invoke(answerQr)
                }
            }
        } catch (_: Exception) {
            Log.e(tag, "Failed to parse QR data")
        }
    }

    private fun sendHandshake() {
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
        Log.d(tag, "sendEncrypted: original text = '$message'")
        try {
            val encrypted = session?.callAttr("encrypt", message)?.toJava(ByteArray::class.java)
            val currentFingerprint = peerFingerprint
            if (encrypted != null && currentFingerprint != null) {
                Log.d(tag, "sendEncrypted: data encrypted, sending via DataChannel to $currentFingerprint")
                dataChannel?.send(DataChannel.Buffer(ByteBuffer.wrap(encrypted), true))
                scope.launch { 
                    messageDao.insert(MessageEntity(peerFingerprint = currentFingerprint, text = message, isMe = true, messageType = "TEXT"))
                    Log.d(tag, "sendEncrypted: inserted into DB: $message")
                }
            } else {
                Log.w(tag, "sendEncrypted: session or fingerprint null. session=$session, fingerprint=$currentFingerprint")
            }
        } catch (_: Exception) { Log.e(tag, "Send failed") }
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
                        messageDao.updateTransferProgress(messageId, currentProgress, if (currentProgress == 100) "COMPLETED" else "PENDING", file.absolutePath)
                    }
                    // Small delay to prevent flooding the buffer
                    kotlinx.coroutines.delay(20)
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
                    } else {
                        messageDao.insert(MessageEntity(
                            peerFingerprint = currentFingerprint,
                            text = "[Image]",
                            isMe = false,
                            messageType = "IMAGE",
                            filePath = file.absolutePath,
                            transferStatus = "COMPLETED",
                            progress = 100
                        ))
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
            Log.d(tag, "onReceive: decrypted text = '$decrypted'")
            
            if (decrypted.startsWith("{")) {
                val json = JSONObject(decrypted)
                if (json.optString("type") == "image_chunk") {
                    handleImageChunk(json)
                    return
                }
            }

            val currentFingerprint = peerFingerprint
            if (currentFingerprint != null) {
                scope.launch { 
                    messageDao.insert(MessageEntity(peerFingerprint = currentFingerprint, text = decrypted, isMe = false, messageType = "TEXT"))
                    Log.d(tag, "onReceive: inserted into DB: $decrypted")
                }
            } else {
                Log.w(tag, "onReceive: decrypted but currentFingerprint is null")
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
