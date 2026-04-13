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
    private val trustedPeers = mutableMapOf<String, PeerIdentity>()

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
                peers.forEach { trustedPeers[it.username] = PeerIdentity(it.identityKey, 0L) }
            }
        }
    }

    fun updateUsername(newName: String) = identityManager.updateUsername(newName)

    fun renewConnection() {
        prepareForNewConnection()
        peerUsername = "Waiting for peer..."
        peerFingerprint = null
        connectionStatus = "Disconnected"
        signalingClient.publishAddress()
        onStateChanged?.invoke("Connection Reset")
    }

    private fun prepareForNewConnection() {
        dataChannel?.dispose()
        peerConnection?.close()
        peerConnection = createPeerConnection(createRtcConfig())
        session = null
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
            PeerConnection.IceServer.builder("turn:openrelay.metered.ca:443?transport=tcp")
                .setUsername("openrelayproject").setPassword("openrelayproject").createIceServer()
        )
        return PeerConnection.RTCConfiguration(iceServers).apply {
            sdpSemantics = PeerConnection.SdpSemantics.UNIFIED_PLAN
            continualGatheringPolicy = PeerConnection.ContinualGatheringPolicy.GATHER_CONTINUALLY
        }
    }

    private fun createPeerConnection(config: PeerConnection.RTCConfiguration): PeerConnection? {
        return peerConnectionFactory.createPeerConnection(config, object : PeerConnection.Observer {
            override fun onIceCandidate(candidate: IceCandidate) {}
            override fun onIceGatheringChange(state: PeerConnection.IceGatheringState) {
                if (state == PeerConnection.IceGatheringState.COMPLETE) finishGathering()
            }
            override fun onIceConnectionChange(state: PeerConnection.IceConnectionState) {
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
            override fun onDataChannel(dc: DataChannel) { setupDataChannel(dc) }
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
        dataChannel = dc
        dc.registerObserver(object : DataChannel.Observer {
            override fun onBufferedAmountChange(p0: Long) {}
            override fun onStateChange() {
                if (dc.state() == DataChannel.State.OPEN) {
                    sendHandshake()
                    onStateChanged?.invoke("Data Channel Open")
                }
            }
            override fun onMessage(buffer: DataChannel.Buffer) { protocol.onReceive(buffer) }
        })
        protocol.setSession(session, dc, peerFingerprint)
    }

    fun createOffer(onSdpReady: (String) -> Unit) {
        prepareForNewConnection()
        dataChannel = peerConnection?.createDataChannel("chat", DataChannel.Init())
        dataChannel?.let { setupDataChannel(it) }

        onIceGatheringComplete = onSdpReady
        gatheringHandler.postDelayed(gatheringTimeoutRunnable, 3000)

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
                    gatheringHandler.postDelayed(gatheringTimeoutRunnable, 3000)
                    peerConnection?.createAnswer(object : SimpleSdpObserver() {
                        override fun onCreateSuccess(desc: SessionDescription?) {
                            peerConnection?.setLocalDescription(object : SimpleSdpObserver() {}, desc)
                        }
                    }, MediaConstraints())
                }
            }
        }, desc)
    }

    fun processScannedQr(qrData: String, onStatus: ((String) -> Unit)?) {
        try {
            val json = JSONObject(qrData)
            val type = json.getString("type")
            peerUsername = json.getString("username")
            val pubKeyHex = json.getString("id_key")
            val pubKey = hexToBytes(pubKeyHex)
            peerFingerprint = identityManager.getFingerprint(pubKey)
            
            if (type == "offer") {
                val sdp = json.getString("sdp")
                handleRemoteSdp(sdp, SessionDescription.Type.OFFER) { answerSdp ->
                    signalingClient.sendAnswer(peerFingerprint!!, answerSdp, pubKey)
                    onStatus?.invoke("Answer Sent")
                }
            }
        } catch (e: Exception) {
            onStatus?.invoke("Error: ${e.message}")
        }
    }

    fun getConnectionQrData(callback: (String) -> Unit) {
        createOffer { sdp ->
            val json = JSONObject().apply {
                put("type", "offer")
                put("username", myUsername)
                put("id_key", bytesToHex(identityManager.getPublicKeyBytes()!!))
                put("sdp", sdp)
            }
            callback(json.toString())
            signalingClient.watchPostBox { answerSdp ->
                handleRemoteSdp(answerSdp, SessionDescription.Type.ANSWER, null)
            }
        }
    }

    private fun sendHandshake() {
        scope.launch {
            val crypto = Python.getInstance().getModule("linkfront.crypto")
            val pair = crypto.callAttr("generate_ephemeral_keypair")
            ephemeralPrivateKey = pair.callAttr("get_private_key_bytes").toJava(ByteArray::class.java)
            ephemeralPublicKey = pair.callAttr("get_public_key_bytes").toJava(ByteArray::class.java)

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
    fun sendEncrypted(message: String) = protocol.sendText(message)
    fun sendImage(bytes: ByteArray) = protocol.sendImage(bytes)
    fun getDhtStatus() = signalingClient.publishAddress() // Dummy/Cleanup needed

    private fun handleHandshake(json: JSONObject) {
        val peerIdKey = hexToBytes(json.getString("id_key"))
        val peerEphemeralKey = hexToBytes(json.getString("ephemeral_key"))
        val peerUsernameReceived = json.getString("username")

        val fingerprint = identityManager.getFingerprint(peerIdKey)
        
        val peerId = trustedPeers[peerUsernameReceived]
        if (peerId != null) {
            completeHandshake(peerIdKey, peerEphemeralKey, peerId.publicKey)
        } else {
            onConnectionRequest(peerUsernameReceived, fingerprint, peerIdKey) { accepted ->
                if (accepted) {
                    scope.launch {
                        peerDao.insert(PeerEntity(peerUsernameReceived, fingerprint, peerIdKey))
                        completeHandshake(peerIdKey, peerEphemeralKey, peerIdKey)
                    }
                }
            }
        }
    }

    private fun completeHandshake(peerIdKey: ByteArray, peerEphemeralKey: ByteArray, trustedIdKey: ByteArray) {
        val crypto = Python.getInstance().getModule("linkfront.crypto")
        session = crypto.callAttr("establish_session", 
            identityManager.identity?.callAttr("get_private_key_bytes"),
            ephemeralPrivateKey,
            peerIdKey,
            peerEphemeralKey
        )
        peerFingerprint = identityManager.getFingerprint(peerIdKey)
        peerUsername = "Connected"
        connectionStatus = "Connected"
        protocol.setSession(session, dataChannel, peerFingerprint)
        onStateChanged?.invoke("Connected")
    }

    private fun thinSdp(sdp: String): String {
        val lines = sdp.lines()
        val filtered = mutableListOf<String>()
        var skipCurrentSection = false
        
        for (line in lines) {
            val trimmed = line.trim()
            if (trimmed.isEmpty()) continue
            if (trimmed.startsWith("m=")) skipCurrentSection = trimmed.startsWith("m=audio") || trimmed.startsWith("m=video")
            if (!skipCurrentSection) {
                if (trimmed.startsWith("a=candidate:")) {
                    if (trimmed.contains("127.0.0.1") || trimmed.contains("::1") || trimmed.contains("localhost")) continue
                    filtered.add(trimmed.split(" ").filter { it !in listOf("generation", "network-id", "network-cost") }.joinToString(" "))
                    continue
                }
                val blacklist = listOf("a=extmap:", "a=rtcp-fb:", "a=msid:", "a=ssrc:", "a=mid:audio", "a=mid:video", "a=max-ptime:", "a=ptime:")
                if (blacklist.any { trimmed.startsWith(it) }) continue
                filtered.add(trimmed)
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
