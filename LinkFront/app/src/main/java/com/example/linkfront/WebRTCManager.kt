package com.example.linkfront

import android.content.Context
import android.os.Handler
import android.os.Looper
import android.util.Log
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import com.chaquo.python.PyObject
import com.chaquo.python.Python
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.runBlocking
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
    private val identityManager = LinkIdentityManager(context)
    private val protocol = LinkProtocol(context, messageDao, scope, onMessageReceived)
    private val signalingClient = SignalingClient(
        identityManager,
        onOfferReceived = { sdp, callback -> handleRemoteSdp(sdp, SessionDescription.Type.OFFER, callback) },
        onAnswerReceived = { sdp -> handleRemoteSdp(sdp, SessionDescription.Type.ANSWER, null) }
    )

    private val peerConnectionFactory: PeerConnectionFactory
    private var peerConnection: PeerConnection? = null
    private var dataChannel: DataChannel? = null
    private var isDestroyed = false

    val myUsername get() = identityManager.username
    val myFingerprint get() = identityManager.fingerprint

    var peerUsername: String by mutableStateOf("Waiting for peer...")
        private set
    var peerFingerprint: String? by mutableStateOf(null)
        private set
    var connectionStatus by mutableStateOf("Disconnected")
        private set

    private var session: PyObject? = null
    private var ephemeralPrivateKey: ByteArray? = null
    private var ephemeralPublicKey: ByteArray? = null
    private val trustedPeers = mutableMapOf<String, PeerIdentity>() // Key: Fingerprint

    data class PeerIdentity(val publicKey: ByteArray, val createdAt: Long)

    private var onIceGatheringComplete: ((String) -> Unit)? = null
    private val gatheringHandler = Handler(Looper.getMainLooper())
    private val gatheringTimeoutRunnable = Runnable { finishGathering() }

    init {
        PeerConnectionFactory.initialize(
            PeerConnectionFactory.InitializationOptions.builder(context).createInitializationOptions()
        )
        peerConnectionFactory = PeerConnectionFactory.builder().createPeerConnectionFactory()
        peerConnection = createPeerConnection(createRtcConfig())

        signalingClient.start()

        protocol.onHandshakeReceived = { json ->
            handleHandshake(json)
        }
        
        scope.launch {
            peerDao.getAllPeers().collect { peers ->
                trustedPeers.clear()
                peers.forEach { 
                    trustedPeers[it.fingerprint] = PeerIdentity(it.identityKey, 0L) 
                }
            }
        }
    }

    fun updateUsername(newName: String): Boolean = identityManager.updateUsername(newName)

    fun publishMyAddress() = signalingClient.publishAddress()

    fun renewConnection() {
        if (Looper.myLooper() != Looper.getMainLooper()) {
            Handler(Looper.getMainLooper()).post { renewConnection() }
            return
        }
        peerFingerprint = null
        prepareForNewConnection()
        peerUsername = "Waiting for peer..."
        connectionStatus = "Disconnected"
        signalingClient.publishAddress()
        onStateChanged?.invoke("Connection Reset")
    }

    private fun prepareForNewConnection() {
        if (isDestroyed) return
        if (Looper.myLooper() != Looper.getMainLooper()) {
            runBlocking(Dispatchers.Main) { prepareForNewConnection() }
            return
        }
        dataChannel?.let {
            it.unregisterObserver()
            try {
                it.dispose()
            } catch (e: Exception) {
                Log.e(tag, "Error disposing data channel: ${e.message}")
            }
        }
        dataChannel = null
        protocol.setSession(null, null, null)
        
        peerConnection?.let {
            try {
                it.close()
                it.dispose()
            } catch (e: Exception) {
                Log.e(tag, "Error disposing peer connection: ${e.message}")
            }
        }
        peerConnection = null
        
        if (!isDestroyed) {
            peerConnection = createPeerConnection(createRtcConfig())
        }
        session = null
    }

    fun destroy() {
        isDestroyed = true
        signalingClient.stop()
        if (Looper.myLooper() == Looper.getMainLooper()) {
            disposeWebRTC()
        } else {
            runBlocking(Dispatchers.Main) { disposeWebRTC() }
        }
    }

    private fun disposeWebRTC() {
        dataChannel?.let {
            it.unregisterObserver()
            try {
                it.dispose()
            } catch (e: Exception) {
                Log.e(tag, "Error disposing data channel in destroy: ${e.message}")
            }
        }
        dataChannel = null
        peerConnection?.let {
            try {
                it.close()
                it.dispose()
            } catch (e: Exception) {
                Log.e(tag, "Error disposing peer connection in destroy: ${e.message}")
            }
        }
        peerConnection = null
        try {
            peerConnectionFactory.dispose()
        } catch (e: Exception) {
            Log.e(tag, "Error disposing factory: ${e.message}")
        }
    }

    private fun finishGathering() {
        gatheringHandler.removeCallbacks(gatheringTimeoutRunnable)
        peerConnection?.localDescription?.let {
            onIceGatheringComplete?.invoke(thinSdp(it.description))
            onIceGatheringComplete = null
        }
    }

    private fun createRtcConfig(): PeerConnection.RTCConfiguration {
        val iceServers = listOf(
            PeerConnection.IceServer.builder("stun:stun.l.google.com:19302").createIceServer(),
            PeerConnection.IceServer.builder("stun:stun1.l.google.com:19302").createIceServer(),
            PeerConnection.IceServer.builder("stun:stun2.l.google.com:19302").createIceServer(),
            PeerConnection.IceServer.builder("turn:openrelay.metered.ca:443?transport=tcp")
                .setUsername("openrelayproject").setPassword("openrelayproject").createIceServer(),
            PeerConnection.IceServer.builder("turn:openrelay.metered.ca:443")
                .setUsername("openrelayproject").setPassword("openrelayproject").createIceServer()
        )
        return PeerConnection.RTCConfiguration(iceServers).apply {
            sdpSemantics = PeerConnection.SdpSemantics.UNIFIED_PLAN
            continualGatheringPolicy = PeerConnection.ContinualGatheringPolicy.GATHER_CONTINUALLY
            // Prioritize TURN if P2P is failing
        }
    }

    private fun createPeerConnection(config: PeerConnection.RTCConfiguration): PeerConnection? {
        if (isDestroyed) return null
        return peerConnectionFactory.createPeerConnection(config, object : PeerConnection.Observer {
            override fun onIceCandidate(candidate: IceCandidate) {}
            override fun onIceGatheringChange(state: PeerConnection.IceGatheringState) {
                if (isDestroyed) return
                if (state == PeerConnection.IceGatheringState.COMPLETE) finishGathering()
            }
            override fun onIceConnectionChange(state: PeerConnection.IceConnectionState) {
                if (isDestroyed) return
                Log.d(tag, "ICE Connection State Changed: $state")
                connectionStatus = when(state) {
                    PeerConnection.IceConnectionState.CONNECTED,
                    PeerConnection.IceConnectionState.COMPLETED -> "Verifying..."
                    PeerConnection.IceConnectionState.DISCONNECTED -> "Reconnecting..."
                    PeerConnection.IceConnectionState.FAILED,
                    PeerConnection.IceConnectionState.CLOSED -> {
                        peerFingerprint?.let { renewConnection() }
                        "Disconnected"
                    }
                    else -> "Connecting..."
                }
                onStateChanged?.invoke(connectionStatus)
            }
            override fun onDataChannel(dc: DataChannel) { 
                if (isDestroyed) return
                setupDataChannel(dc) 
            }
            override fun onSignalingChange(p0: PeerConnection.SignalingState?) {}
            override fun onIceConnectionReceivingChange(p0: Boolean) {}
            override fun onIceCandidatesRemoved(p0: Array<out IceCandidate>?) {}
            override fun onAddStream(p0: MediaStream?) {}
            override fun onRemoveStream(p0: MediaStream?) {}
            override fun onRenegotiationNeeded() {}
            override fun onAddTrack(p0: RtpReceiver?, p1: Array<out MediaStream>?) {}
        })
    }

    private fun setupDataChannel(dc: DataChannel) {
        if (isDestroyed) return
        dataChannel = dc
        dc.registerObserver(object : DataChannel.Observer {
            override fun onBufferedAmountChange(p0: Long) {}
            override fun onStateChange() {
                if (isDestroyed) return
                if (dc.state() == DataChannel.State.OPEN) {
                    sendHandshake()
                    onStateChanged?.invoke("Data Channel Open")
                }
            }
            override fun onMessage(buffer: DataChannel.Buffer) { 
                if (isDestroyed) return
                protocol.onReceive(buffer) 
            }
        })
        protocol.setSession(session, dc, peerFingerprint)
    }

    fun createOffer(onSdpReady: (String) -> Unit) {
        prepareForNewConnection()
        dataChannel = peerConnection?.createDataChannel("chat", DataChannel.Init())
        dataChannel?.let { setupDataChannel(it) }

        onIceGatheringComplete = onSdpReady
        gatheringHandler.postDelayed(gatheringTimeoutRunnable, 5000)

        peerConnection?.createOffer(object : SimpleSdpObserver() {
            override fun onCreateSuccess(desc: SessionDescription?) {
                peerConnection?.setLocalDescription(object : SimpleSdpObserver() {}, desc)
            }
        }, MediaConstraints())
    }

    private fun handleRemoteSdp(sdp: String, type: SessionDescription.Type, onAnswerReady: ((String) -> Unit)?) {
        val desc = SessionDescription(type, sdp)
        peerConnection?.setRemoteDescription(object : SimpleSdpObserver() {
            override fun onSetSuccess() {
                if (type == SessionDescription.Type.OFFER) {
                    onIceGatheringComplete = onAnswerReady
                    gatheringHandler.postDelayed(gatheringTimeoutRunnable, 5000)
                    peerConnection?.createAnswer(object : SimpleSdpObserver() {
                        override fun onCreateSuccess(desc: SessionDescription?) {
                            peerConnection?.setLocalDescription(object : SimpleSdpObserver() {}, desc)
                        }
                    }, MediaConstraints())
                }
            }
        }, desc)
    }

    fun processScannedQr(qrData: String, onAnswerReady: ((String) -> Unit)?) {
        try {
            val json = JSONObject(qrData)
            val type = json.getString("type")
            val rawPeerUsername = json.optString("username", "")
            peerUsername = identityManager.sanitizeUsername(rawPeerUsername).ifEmpty { "Peer" }

            val pubKeyHex = json.getString("id_key")
            val pubKey = hexToBytes(pubKeyHex)
            peerFingerprint = identityManager.getFingerprint(pubKey)
            
            // Add peer as a DHT bootstrap node if address is present
            val peerIp = json.optString("ip", "")
            val peerDhtPort = json.optInt("dht_port", 0)
            if (peerIp.isNotEmpty() && peerDhtPort != 0) {
                scope.launch {
                    val dhtNode = Python.getInstance().getModule("linkfront.dht_node")
                    dhtNode.callAttr("add_dht_bootstrap", peerIp, peerDhtPort)
                }
            }

            val sdp = json.getString("sdp")
            if (type == "offer") {
                handleRemoteSdp(sdp, SessionDescription.Type.OFFER) { answerSdp ->
                    // 1. Try background signaling via DHT
                    signalingClient.sendAnswer(peerFingerprint!!, answerSdp, pubKey)
                    
                    // 2. Prepare Answer JSON for manual QR fallback
                    val answerJson = JSONObject().apply {
                        put("type", "answer")
                        put("username", myUsername)
                        put("id_key", bytesToHex(identityManager.getPublicKeyBytes()!!))
                        put("sdp", answerSdp)
                    }
                    onAnswerReady?.invoke(answerJson.toString())
                }
            } else if (type == "answer") {
                // Peer A scans Peer B's answer - just set the remote description
                handleRemoteSdp(sdp, SessionDescription.Type.ANSWER, null)
                // We do NOT call onAnswerReady here, so no "recursion" QR is shown
                Log.d(tag, "Handshake completed via QR scan")
            }
        } catch (e: Exception) {
            Log.e(tag, "QR Process Error: ${e.message}")
        }
    }

    fun getConnectionQrData(callback: (String) -> Unit) {
        createOffer { sdp ->
            scope.launch {
                val py = Python.getInstance()
                val dhtNode = py.getModule("linkfront.dht_node")
                val status = JSONObject(dhtNode.callAttr("get_dht_status").toString())
                val myIp = status.optString("local_ip", "")
                val dhtPort = status.optInt("listen_port", 0)

                val json = JSONObject().apply {
                    put("type", "offer")
                    put("username", myUsername)
                    put("id_key", bytesToHex(identityManager.getPublicKeyBytes()!!))
                    put("sdp", thinSdp(sdp))
                    put("ip", myIp)
                    put("port", signalingClient.localPort)
                    put("dht_port", dhtPort)
                }
                callback(json.toString())
                signalingClient.watchPostBox { answerSdp ->
                    handleRemoteSdp(answerSdp, SessionDescription.Type.ANSWER, null)
                }
            }
        }
    }

    private fun sendHandshake() {
        scope.launch {
            val crypto = Python.getInstance().getModule("linkfront.crypto")
            val pair = crypto.callAttr("generate_ephemeral_keypair").toJava(Array<ByteArray>::class.java)
            ephemeralPrivateKey = pair[0]
            ephemeralPublicKey = pair[1]

            val handshake = JSONObject().apply {
                put("type", "handshake")
                put("username", myUsername)
                put("id_key", bytesToHex(identityManager.getPublicKeyBytes()!!))
                put("ephemeral_key", bytesToHex(ephemeralPublicKey!!))
            }
            // Use a raw message for handshake
            dataChannel?.send(DataChannel.Buffer(ByteBuffer.wrap(handshake.toString().toByteArray()), false))
        }
    }

    // Protocol glue
    fun sendEncrypted(targetFingerprint: String, message: String) = protocol.sendText(targetFingerprint, message)
    fun sendImage(targetFingerprint: String, bytes: ByteArray) = protocol.sendImage(targetFingerprint, bytes)
    fun getDhtStatus() = signalingClient.getDhtStatus()

    fun connectToPeerViaDHT(fingerprint: String) {
        scope.launch {
            val addresses = signalingClient.lookupAddress(fingerprint)
            if (addresses.isNotEmpty()) {
                createOffer { sdp ->
                    scope.launch {
                        for ((ip, port) in addresses) {
                            Log.d(tag, "Attempting direct offer to $ip:$port")
                            val answerSdp = signalingClient.sendOfferDirectly(ip, port, sdp)
                            if (answerSdp != null) {
                                handleRemoteSdp(answerSdp, SessionDescription.Type.ANSWER, null)
                                Log.d(tag, "Direct offer successful to $ip:$port")
                                break
                            }
                        }
                    }
                }
            }
        }
    }

    private fun handleHandshake(json: JSONObject) {
        val peerIdKey = hexToBytes(json.getString("id_key"))
        val peerEphemeralKey = hexToBytes(json.getString("ephemeral_key"))
        val fingerprint = identityManager.getFingerprint(peerIdKey)
        
        // RESOLVE IDENTITY: Trust local data over handshake data
        var peerUsernameReceived = "Unknown Peer"
        runBlocking {
            val existingPeer = peerDao.getPeerByFingerprint(fingerprint)
            val incomingNameFromHandshake = identityManager.sanitizeUsername(json.optString("username", ""))
            val fingerprintRegex = Regex("^([0-9A-F]{4}:){3}[0-9A-F]{4}.*")
            
            peerUsernameReceived = when {
                // 1. If this matches the QR we just scanned, use the name from the QR
                fingerprint == peerFingerprint -> peerUsername
                
                // 2. If they are in the database, use our saved name
                existingPeer != null -> existingPeer.username
                
                // 3. If they sent a fingerprint as a name, sanitize it
                fingerprintRegex.matches(incomingNameFromHandshake) -> {
                    val sanitized = identityManager.sanitizeUsername(incomingNameFromHandshake)
                    if (fingerprintRegex.matches(sanitized)) "Peer ${fingerprint.take(8)}" else sanitized
                }
                
                // 4. New peer, use their provided name or fallback
                else -> incomingNameFromHandshake.ifEmpty { "Unknown Peer" }
            }
        }
        
        val peerId = trustedPeers[fingerprint]
        if (peerId != null) {
            if (!peerId.publicKey.contentEquals(peerIdKey)) {
                Log.e(tag, "Identity mismatch for fingerprint: $fingerprint")
                return
            }
            completeHandshake(peerUsernameReceived, peerIdKey, peerEphemeralKey)
        } else {
            onConnectionRequest(peerUsernameReceived, fingerprint, peerIdKey) { accepted ->
                if (accepted) {
                    scope.launch {
                        peerDao.insert(PeerEntity(fingerprint, peerUsernameReceived, peerIdKey))
                        completeHandshake(peerUsernameReceived, peerIdKey, peerEphemeralKey)
                    }
                }
            }
        }
    }

    private fun completeHandshake(username: String, peerIdKey: ByteArray, peerEphemeralKey: ByteArray) {
        val crypto = Python.getInstance().getModule("linkfront.crypto")
        session = crypto.callAttr("establish_session", 
            identityManager.identity?.callAttr("get_seed_bytes"),
            ephemeralPrivateKey,
            peerIdKey,
            peerEphemeralKey
        )
        peerFingerprint = identityManager.getFingerprint(peerIdKey)
        peerUsername = username
        connectionStatus = "Connected"
        protocol.setSession(session, dataChannel, peerFingerprint)
        onStateChanged?.invoke("Connected")
    }

    private fun thinSdp(sdp: String): String {
        val lines = sdp.lines()
        val filtered = mutableListOf<String>()
        
        // Essential attributes for WebRTC handshake and basic media
        val essentialPrefixes = listOf(
            "v=", "o=", "s=", "t=", "c=", "a=group:", "a=msid-semantic:",
            "m=", "a=mid:", "a=setup:", "a=fingerprint:", "a=ice-ufrag:", "a=ice-pwd:",
            "a=candidate:", "a=sctpmap:", "a=sctp-port:", "a=max-message-size:",
            "a=rtpmap:", "a=fmtp:", "a=rtcp-mux", "a=rtcp:"
        )

        for (line in lines) {
            val trimmed = line.trim()
            if (trimmed.isEmpty()) continue
            
            // Keep essential lines and anything not blacklisted
            if (essentialPrefixes.any { trimmed.startsWith(it) }) {
                filtered.add(trimmed)
            } else {
                // Skip large metadata that isn't strictly required for basic connectivity
                val blacklist = listOf("a=extmap:", "a=msid:", "a=ssrc:", "a=max-ptime:", "a=ptime:", "a=rtcp-fb:")
                if (!blacklist.any { trimmed.startsWith(it) }) {
                    filtered.add(trimmed)
                }
            }
        }
        return filtered.joinToString("\r\n") + "\r\n"
    }

    private fun hexToBytes(hex: String) = hex.chunked(2).map { it.toInt(16).toByte() }.toByteArray()
    private fun bytesToHex(bytes: ByteArray) = bytes.joinToString("") { "%02x".format(it) }

    open class SimpleSdpObserver : SdpObserver {
        override fun onCreateSuccess(p0: SessionDescription?) {}
        override fun onSetSuccess() {}
        override fun onCreateFailure(p0: String?) {}
        override fun onSetFailure(p0: String?) {}
    }
}
