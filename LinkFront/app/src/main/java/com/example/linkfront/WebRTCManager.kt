package com.example.linkfront

import android.content.Context
import android.os.Handler
import android.os.Looper
import android.util.Log
import android.widget.Toast
import com.chaquo.python.PyObject
import com.chaquo.python.Python
import org.json.JSONObject
import org.webrtc.*
import java.nio.ByteBuffer
import java.nio.charset.StandardCharsets

class WebRTCManager(
    private val context: Context,
    private val onConnectionRequest: (String, String, ByteArray, (Boolean) -> Unit) -> Unit,
    var onMessageReceived: ((String) -> Unit)? = null,
    var onStateChanged: ((String) -> Unit)? = null
) {
    private val TAG = "WebRTCManager"
    private val peerConnectionFactory: PeerConnectionFactory
    private var peerConnection: PeerConnection? = null
    private var dataChannel: DataChannel? = null
    
    private val py = Python.getInstance()
    private val handshakeModule = py.getModule("handshake")
    private val sessionModule = py.getModule("session")
    private val identityModule = py.getModule("identity")

    private var identity: PyObject? = null
    private var myUsername: String = "User_" + (100..999).random()

    // Store scanned peers: Map<Username, PeerIdentity>
    data class PeerIdentity(val publicKey: ByteArray, val createdAt: Long)
    private val trustedPeers = mutableMapOf<String, PeerIdentity>()

    private var ephemeralPrivateKey: ByteArray? = null
    private var ephemeralPublicKey: ByteArray? = null
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
            Log.d(TAG, "Gathering finished early (or complete). Sending SDP.")
            val thinnedSdp = thinSdp(localDescription.description)
            onIceGatheringComplete?.invoke(thinnedSdp)
            onIceGatheringComplete = null
        }
    }

    private fun thinSdp(sdp: String): String {
        // Removes unnecessary lines to make the QR code smaller
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
            identity = identityModule.get("Identity")?.call(seed, savedTimestamp)
            Log.d(TAG, "Loaded existing identity from storage")
        } else {
            identity = identityModule.get("Identity")?.call()
            val seed = identity?.callAttr("get_seed_bytes")?.toJava(ByteArray::class.java)
            val timestamp = identity?.callAttr("get_created_at")?.toLong() ?: 0L
            
            if (seed != null) {
                val seedBase64 = android.util.Base64.encodeToString(seed, android.util.Base64.NO_WRAP)
                prefs.edit()
                    .putString("identity_seed", seedBase64)
                    .putLong("identity_timestamp", timestamp)
                    .apply()
                Log.d(TAG, "Generated and saved new identity")
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
    }

    private fun createPeerConnection(config: PeerConnection.RTCConfiguration): PeerConnection? {
        return peerConnectionFactory.createPeerConnection(config, object : PeerConnection.Observer {
            override fun onIceCandidate(candidate: IceCandidate) {
                Log.d(TAG, "New ICE Candidate: $candidate")
            }

            override fun onIceGatheringChange(state: PeerConnection.IceGatheringState) {
                Log.d(TAG, "ICE Gathering State: $state")
                if (state == PeerConnection.IceGatheringState.COMPLETE) {
                    finishGathering()
                }
            }

            override fun onSignalingChange(state: PeerConnection.SignalingState) {}
            override fun onIceConnectionChange(state: PeerConnection.IceConnectionState) {
                Log.d(TAG, "ICE Connection State: $state")
                onStateChanged?.invoke("ICE: $state")
            }
            override fun onIceConnectionReceivingChange(p0: Boolean) {}
            override fun onIceCandidatesRemoved(p0: Array<out IceCandidate>) {}
            override fun onDataChannel(channel: DataChannel) {
                Log.d(TAG, "Received remote DataChannel: ${channel.label()}")
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
                Log.d(TAG, "DataChannel State: ${dataChannel?.state()}")
                if (dataChannel?.state() == DataChannel.State.OPEN) {
                    sendHandshake()
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
        if (channel != null) {
            setupDataChannel(channel)
        }
    }

    // --- Signaling Layer ---

    fun createOffer(callback: (String) -> Unit) {
        onIceGatheringComplete = callback
        initDataChannel()
        
        // Start a 3-second timeout. If gathering isn't complete by then, we just use what we have.
        gatheringHandler.postDelayed(gatheringTimeoutRunnable, 3000)

        peerConnection?.createOffer(object : SimpleSdpObserver() {
            override fun onCreateSuccess(sdp: SessionDescription?) {
                peerConnection?.setLocalDescription(SimpleSdpObserver(), sdp)
            }
        }, MediaConstraints())
    }

    fun handleRemoteSdp(sdpStr: String, type: SessionDescription.Type, onAnswerReady: ((String) -> Unit)? = null) {
        val sdp = SessionDescription(type, sdpStr)
        peerConnection?.setRemoteDescription(object : SimpleSdpObserver() {
            override fun onSetSuccess() {
                if (type == SessionDescription.Type.OFFER) {
                    onIceGatheringComplete = onAnswerReady
                    // Start a 3-second timeout for the Answer gathering
                    gatheringHandler.postDelayed(gatheringTimeoutRunnable, 3000)
                    createAnswer()
                }
            }
        }, sdp)
    }

    private fun createAnswer() {
        peerConnection?.createAnswer(object : SimpleSdpObserver() {
            override fun onCreateSuccess(sdp: SessionDescription?) {
                peerConnection?.setLocalDescription(SimpleSdpObserver(), sdp)
            }
        }, MediaConstraints())
    }

    // --- Identity & QR Logic ---

    fun getMyQrData(): String {
        return identity?.callAttr("get_qr_data", myUsername).toString()
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
            identityModule.callAttr("get_fingerprint", key).toString()
        }
    }

    fun processScannedQr(qrData: String, onAnswerReady: ((String) -> Unit)? = null) {
        try {
            val result = identityModule.callAttr("parse_qr_data", qrData)
            val peerUsername = result.asList()[0].toString()
            val peerIdentityKey = result.asList()[1].toJava(ByteArray::class.java)
            val timestamp = result.asList()[2].toLong()
            val sdp = result.asList()[3]?.toString()
            val sdpTypeStr = result.asList()[4]?.toString()
            
            val existing = trustedPeers[peerUsername]
            if (existing != null && !existing.publicKey.contentEquals(peerIdentityKey)) {
                Log.e(TAG, "SECURITY ALERT: Scanned key for $peerUsername does not match trusted key!")
                return
            }

            trustedPeers[peerUsername] = PeerIdentity(peerIdentityKey, timestamp)
            
            if (sdp != null && sdpTypeStr != null) {
                val type = if (sdpTypeStr == "offer") SessionDescription.Type.OFFER else SessionDescription.Type.ANSWER
                handleRemoteSdp(sdp, type) { answerSdp ->
                    val answerQr = identity?.callAttr("get_connection_qr", myUsername, answerSdp, "answer").toString()
                    onAnswerReady?.invoke(answerQr)
                }
            }
        } catch (e: Exception) {
            Log.e(TAG, "Failed to parse QR data", e)
        }
    }

    // --- Handshake Layer ---

    private fun sendHandshake() {
        val result = handshakeModule.callAttr("generate_keypair")
        ephemeralPrivateKey = result.asList()[0].toJava(ByteArray::class.java)
        ephemeralPublicKey = result.asList()[1].toJava(ByteArray::class.java)

        val json = JSONObject()
        json.put("type", "handshake")
        json.put("username", myUsername)
        
        val pubKeyBase64 = android.util.Base64.encodeToString(ephemeralPublicKey, android.util.Base64.NO_WRAP)
        json.put("pubkey", pubKeyBase64)

        val myIdKey = identity?.callAttr("get_public_key_bytes")?.toJava(ByteArray::class.java)
        val myIdKeyBase64 = android.util.Base64.encodeToString(myIdKey, android.util.Base64.NO_WRAP)
        json.put("identity_key", myIdKeyBase64)

        val myTimestamp = identity?.callAttr("get_created_at")?.toLong() ?: 0L
        json.put("timestamp", myTimestamp)

        val signature = identity?.callAttr("sign", ephemeralPublicKey)?.toJava(ByteArray::class.java)
        val sigBase64 = android.util.Base64.encodeToString(signature, android.util.Base64.NO_WRAP)
        json.put("signature", sigBase64)

        val packet = json.toString().toByteArray(StandardCharsets.UTF_8)
        dataChannel?.send(DataChannel.Buffer(ByteBuffer.wrap(packet), false))
        Log.d(TAG, "Sent handshake")
    }

    private fun handleHandshake(json: JSONObject) {
        val peerUsername = json.getString("username")
        val peerPubKey = android.util.Base64.decode(json.getString("pubkey"), android.util.Base64.DEFAULT)
        val signature = android.util.Base64.decode(json.getString("signature"), android.util.Base64.DEFAULT)
        val peerIdKey = android.util.Base64.decode(json.getString("identity_key"), android.util.Base64.DEFAULT)
        val peerTimestamp = json.optLong("timestamp", 0L)

        val trusted = trustedPeers[peerUsername]

        if (trusted != null) {
            if (!peerIdKey.contentEquals(trusted.publicKey)) {
                Log.e(TAG, "SECURITY ALERT: Handshake identity for $peerUsername does not match trusted key!")
                dataChannel?.close()
                return
            }
            completeHandshake(peerUsername, peerPubKey, signature, trusted.publicKey)
        } else {
            // Unknown peer - trigger TOFU dialog
            val fingerprint = identityModule.callAttr("get_fingerprint", peerIdKey).toString()
            onConnectionRequest(peerUsername, "$fingerprint (TS: $peerTimestamp)", peerIdKey) { accepted ->
                if (accepted) {
                    trustedPeers[peerUsername] = PeerIdentity(peerIdKey, peerTimestamp)
                    completeHandshake(peerUsername, peerPubKey, signature, peerIdKey)
                } else {
                    Log.d(TAG, "Connection from $peerUsername rejected by user.")
                    dataChannel?.close()
                }
            }
        }
    }

    private fun completeHandshake(peerUsername: String, peerPubKey: ByteArray, signature: ByteArray, idKey: ByteArray) {
        if (session != null) {
            Log.d(TAG, "Session already established with $peerUsername, ignoring redundant handshake.")
            return
        }

        val isValid = identityModule.callAttr("verify_signature", idKey, signature, peerPubKey).toBoolean()
        if (!isValid) {
            Log.e(TAG, "Handshake verification failed for $peerUsername")
            return
        }

        if (ephemeralPrivateKey == null) {
            Log.d(TAG, "Received handshake first, sending ours in response.")
            sendHandshake()
        }

        val sharedKey = handshakeModule.callAttr("derive_shared", ephemeralPrivateKey, peerPubKey).toJava(ByteArray::class.java)
        session = sessionModule.get("Session")?.call(sharedKey)
        Log.d(TAG, "Session established with $peerUsername")
        onStateChanged?.invoke("Secure Session Ready")
    }

    // --- Transport Layer ---

    fun sendEncrypted(message: String) {
        try {
            val encrypted = session?.callAttr("encrypt", message)?.toJava(ByteArray::class.java)
            if (encrypted != null) {
                dataChannel?.send(DataChannel.Buffer(ByteBuffer.wrap(encrypted), true))
            }
        } catch (e: Exception) {
            Log.e(TAG, "Send failed", e)
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
        } catch (e: Exception) {}

        try {
            val decrypted = session?.callAttr("decrypt", data).toString()
            Log.d(TAG, "Received: $decrypted")
            onMessageReceived?.invoke(decrypted)
        } catch (e: Exception) {
            Log.e(TAG, "Decryption failed", e)
        }
    }
}

open class SimpleSdpObserver : SdpObserver {
    override fun onCreateSuccess(p0: SessionDescription?) {}
    override fun onSetSuccess() {}
    override fun onCreateFailure(p0: String?) { Log.e("WebRTC", "SDP Create Failed: $p0") }
    override fun onSetFailure(p0: String?) { Log.e("WebRTC", "SDP Set Failed: $p0") }
}
