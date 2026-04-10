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
    private val TAG = "WebRTCManager"
    private val scope = CoroutineScope(Dispatchers.IO)
    private val peerConnectionFactory: PeerConnectionFactory
    private var peerConnection: PeerConnection? = null
    private var dataChannel: DataChannel? = null
    
    private val py = Python.getInstance()
    private val handshakeModule = py.getModule("handshake")
    private val sessionModule = py.getModule("session")
    private val identityModule = py.getModule("identity")
    private val dhtModule = py.getModule("dht_node")

    private var identity: PyObject? = null
    var myUsername by mutableStateOf("")
        private set

    private var signalServerSocket: java.net.ServerSocket? = null
    private var signalPort: Int = 0

    fun updateUsername(newName: String) {
        myUsername = newName
        context.getSharedPreferences("link_identity", Context.MODE_PRIVATE)
            .edit().putString("my_username", newName).apply()
    }

    data class PeerIdentity(val publicKey: ByteArray, val createdAt: Long)
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
            Log.d(TAG, "Gathering finished early (or complete). Sending SDP.")
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
            identity = identityModule.get("Identity")?.call(seed, savedTimestamp)
            myUsername = prefs.getString("my_username", "User_" + (100..999).random())!!
        } else {
            identity = identityModule.get("Identity")?.call()
            val seed = identity?.callAttr("get_seed_bytes")?.toJava(ByteArray::class.java)
            val timestamp = identity?.callAttr("get_created_at")?.toLong() ?: 0L
            myUsername = "User_" + (100..999).random()
            
            if (seed != null) {
                val seedBase64 = android.util.Base64.encodeToString(seed, android.util.Base64.NO_WRAP)
                prefs.edit()
                    .putString("identity_seed", seedBase64)
                    .putLong("identity_timestamp", timestamp)
                    .putString("my_username", myUsername)
                    .apply()
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
                Log.d(TAG, "Signal Listener started on port $signalPort")
                
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
                        } catch (e: Exception) {
                            Log.e(TAG, "Signal handling error: ${e.message}")
                        } finally {
                            client.close()
                        }
                    }
                }
            } catch (e: Exception) {
                Log.e(TAG, "Signal Listener error: ${e.message}")
            }
        }
    }

    private fun publishMyAddress() {
        scope.launch(Dispatchers.IO) {
            try {
                val publicIp = dhtModule.callAttr("get_public_ip")?.toString()
                val myPublicKey = identity?.callAttr("get_public_key")?.toJava(ByteArray::class.java)
                val fingerprint = getFingerprint(myPublicKey)
                
                if (publicIp != null && fingerprint != null) {
                    Log.d(TAG, "Publishing to DHT: $fingerprint at $publicIp:$signalPort")
                    dhtModule.callAttr("publish_address", fingerprint, publicIp, signalPort)
                }
            } catch (e: Exception) {
                Log.e(TAG, "DHT Publish error: ${e.message}")
            }
        }
    }

    fun connectToPeerViaDHT(fingerprint: String) {
        scope.launch(Dispatchers.IO) {
            try {
                val result = dhtModule.callAttr("lookup_address", fingerprint)
                if (result != null) {
                    val ip = result.asMap()["ip"]?.toString()
                    val port = result.asMap()["port"]?.toInt()
                    
                    if (ip != null && port != null) {
                        Log.d(TAG, "Peer found at $ip:$port. Initiating...")
                        createOffer { offerSdp ->
                            sendOfferToAddress(ip, port, offerSdp)
                        }
                    }
                }
            } catch (e: Exception) {
                Log.e(TAG, "DHT Connect error: ${e.message}")
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
                Log.d(TAG, "Connection via DHT established")
            } catch (e: Exception) {
                Log.e(TAG, "Failed to send offer to $ip:$port : ${e.message}")
            }
        }
    }

    private fun createPeerConnection(config: PeerConnection.RTCConfiguration): PeerConnection? {
        return peerConnectionFactory.createPeerConnection(config, object : PeerConnection.Observer {
            override fun onIceCandidate(candidate: IceCandidate) {
                Log.d(TAG, "New ICE Candidate: $candidate")
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
            if (existing != null && !existing.publicKey.contentEquals(peerIdentityKey)) return

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

    private fun sendHandshake() {
        val result = handshakeModule.callAttr("generate_keypair")
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
        val peerUsername = json.getString("username")
        this.peerUsername = peerUsername
        val peerPubKey = android.util.Base64.decode(json.getString("pubkey"), android.util.Base64.DEFAULT)
        val signature = android.util.Base64.decode(json.getString("signature"), android.util.Base64.DEFAULT)
        val peerIdKey = android.util.Base64.decode(json.getString("identity_key"), android.util.Base64.DEFAULT)
        val peerTimestamp = json.optLong("timestamp", 0L)

        val fingerprint = identityModule.callAttr("get_fingerprint", peerIdKey).toString()
        this.peerFingerprint = fingerprint
        val trusted = trustedPeers[peerUsername]

        if (trusted != null) {
            if (!peerIdKey.contentEquals(trusted.publicKey)) {
                dataChannel?.close()
                return
            }
            completeHandshake(peerUsername, peerPubKey, signature, trusted.publicKey)
        } else {
            onConnectionRequest(peerUsername, "$fingerprint (TS: $peerTimestamp)", peerIdKey) { accepted ->
                if (accepted) {
                    val finger = identityModule.callAttr("get_fingerprint", peerIdKey).toString()
                    scope.launch { peerDao.insert(PeerEntity(finger, peerUsername, peerIdKey)) }
                    trustedPeers[peerUsername] = PeerIdentity(peerIdKey, peerTimestamp)
                    completeHandshake(peerUsername, peerPubKey, signature, peerIdKey)
                } else dataChannel?.close()
            }
        }
    }

    private fun completeHandshake(peerUsername: String, peerPubKey: ByteArray, signature: ByteArray, idKey: ByteArray) {
        if (session != null) return
        val isValid = identityModule.callAttr("verify_signature", idKey, signature, peerPubKey).toBoolean()
        if (!isValid) return
        if (ephemeralPrivateKey == null) sendHandshake()

        val sharedKey = handshakeModule.callAttr("derive_shared", ephemeralPrivateKey, peerPubKey).toJava(ByteArray::class.java)
        session = sessionModule.get("Session")?.call(sharedKey)
        connectionStatus = "Connected"
        onStateChanged?.invoke("Secure Session Ready")
    }

    fun sendEncrypted(message: String) {
        try {
            val encrypted = session?.callAttr("encrypt", message)?.toJava(ByteArray::class.java)
            val currentFingerprint = peerFingerprint
            if (encrypted != null && currentFingerprint != null) {
                dataChannel?.send(DataChannel.Buffer(ByteBuffer.wrap(encrypted), true))
                scope.launch { messageDao.insert(MessageEntity(peerFingerprint = currentFingerprint, text = message, isMe = true)) }
            }
        } catch (e: Exception) { Log.e(TAG, "Send failed", e) }
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
            val currentFingerprint = peerFingerprint
            if (currentFingerprint != null) {
                scope.launch { messageDao.insert(MessageEntity(peerFingerprint = currentFingerprint, text = decrypted, isMe = false)) }
            }
            onMessageReceived?.invoke(decrypted)
        } catch (e: Exception) { Log.e(TAG, "Decryption failed", e) }
    }
}

open class SimpleSdpObserver : SdpObserver {
    override fun onCreateSuccess(p0: SessionDescription?) {}
    override fun onSetSuccess() {}
    override fun onCreateFailure(p0: String?) { Log.e("WebRTC", "SDP Create Failed: $p0") }
    override fun onSetFailure(p0: String?) { Log.e("WebRTC", "SDP Set Failed: $p0") }
}
