package com.linkfront

import android.content.Context
import android.os.Handler
import android.os.Looper
import android.util.Log
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import com.chaquo.python.PyObject
import com.chaquo.python.Python
import kotlinx.coroutines.*
import org.json.JSONObject
import org.webrtc.*
import java.io.File
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
    private val mainHandler = Handler(Looper.getMainLooper())
    private val scope = CoroutineScope(Dispatchers.IO + SupervisorJob())
    private val identityManager = LinkIdentityManager(context)
    private val protocol = LinkProtocol(context, messageDao, scope, onMessageReceived)
    private val signalingClient = SignalingClient(
        identityManager,
        onOfferReceived = { sdp, callback -> handleRemoteSdp(sdp, SessionDescription.Type.OFFER, callback) },
        onAnswerReceived = { sdp -> handleRemoteSdp(sdp, SessionDescription.Type.ANSWER, null) },
        onIceCandidateReceived = { candidate -> addIceCandidate(candidate) }
    )

    private var peerConnectionFactory: PeerConnectionFactory? = null
    private var peerConnection: PeerConnection? = null
    private var dataChannel: DataChannel? = null
    private var isDestroyed = false
    private var currentOfferId: String? = null

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
    private var gatheringAttempts = 0
    private var gatheringTimeoutReached = false
    private val gatheringTimeoutRunnable = Runnable { 
        Log.w(tag, "ICE Gathering timeout reached")
        gatheringTimeoutReached = true
        finishGathering() 
    }

    init {
        mainHandler.post {
            PeerConnectionFactory.initialize(
                PeerConnectionFactory.InitializationOptions.builder(context).createInitializationOptions()
            )
            peerConnectionFactory = PeerConnectionFactory.builder().createPeerConnectionFactory()
            peerConnection = createPeerConnection(createRtcConfig())
        }

        signalingClient.start()
        
        Log.i(tag, "WebRTCManager initialized. Online: ${isOnline()}")

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

        startPendingRetryLoop()
    }

    private fun startPendingRetryLoop() {
        scope.launch {
            while (!isDestroyed) {
                if (isOnline()) {
                    val allPeersWithPending = messageDao.getPeersWithPendingMessages()
                    if (allPeersWithPending.isNotEmpty()) {
                        Log.d(tag, "Found ${allPeersWithPending.size} peers with pending messages. Retrying connections.")
                        allPeersWithPending.forEach { fingerprint ->
                            if (peerFingerprint != fingerprint || connectionStatus != "Connected") {
                                Log.i(tag, "Background retry for $fingerprint")
                                connectToPeerViaDHT(fingerprint)
                            }
                        }
                    }
                }
                delay(60000) // Retry every minute
            }
        }
    }

    fun updateUsername(newName: String): Boolean = identityManager.updateUsername(newName)

    fun publishMyAddress() {
        if (!isOnline()) {
            Log.w(tag, "Cannot publish address: Device is offline")
            return
        }
        signalingClient.publishAddress()
    }

    private fun isOnline(): Boolean {
        val cm = context.getSystemService(Context.CONNECTIVITY_SERVICE) as android.net.ConnectivityManager
        val net = cm.activeNetwork ?: return false
        val caps = cm.getNetworkCapabilities(net) ?: return false
        return caps.hasCapability(android.net.NetworkCapabilities.NET_CAPABILITY_INTERNET)
    }

    private var lastRenewTime = 0L

    fun renewConnection() {
        val now = System.currentTimeMillis()
        if (now - lastRenewTime < 5000) {
            Log.d(tag, "Renew connection cooldown active. Skipping.")
            return
        }
        lastRenewTime = now

        mainHandler.post {
            if (isDestroyed) return@post
            peerFingerprint = null
            prepareForNewConnection()
            peerUsername = "Waiting for peer..."
            connectionStatus = "Disconnected"
            signalingClient.publishAddress()
            onStateChanged?.invoke("Connection Reset")
        }
    }

    private fun prepareForNewConnection() {
        if (isDestroyed) return
        if (Looper.myLooper() != Looper.getMainLooper()) {
            mainHandler.post { prepareForNewConnection() }
            return
        }
        dataChannel?.let {
            it.unregisterObserver()
            try {
                it.close()
            } catch (e: Exception) {
                Log.e(tag, "Error closing data channel: ${e.message}")
            }
        }
        dataChannel = null
        protocol.setSession(null, null, null)
        
        peerConnection?.let {
            try {
                it.close()
            } catch (e: Exception) {
                Log.e(tag, "Error closing peer connection: ${e.message}")
            }
        }
        peerConnection = null
        
        if (!isDestroyed && peerConnectionFactory != null) {
            peerConnection = createPeerConnection(createRtcConfig())
        }
        session = null
        currentOfferId = null
    }

    fun destroy() {
        isDestroyed = true
        signalingClient.stop()
        mainHandler.post {
            disposeWebRTC()
            peerConnectionFactory?.dispose()
            peerConnectionFactory = null
        }
        scope.cancel()
    }

    private fun disposeWebRTC() {
        dataChannel?.let {
            it.unregisterObserver()
            try {
                it.close()
            } catch (e: Exception) {
                Log.e(tag, "Error closing data channel in destroy: ${e.message}")
            }
        }
        dataChannel = null
        
        peerConnection?.let {
            try {
                it.close()
            } catch (e: Exception) {
                Log.e(tag, "Error closing peer connection: ${e.message}")
            }
        }
        peerConnection = null
    }

    private fun finishGathering() {
        mainHandler.post {
            if (onIceGatheringComplete == null) return@post
            
            val currentLocalDesc = peerConnection?.localDescription
            val sdp = currentLocalDesc?.description ?: ""
            
            // NAT BYPASS Logic:
            val hasWanCandidate = sdp.contains("typ srflx") || sdp.contains("typ relay")
            val hasAnyCandidate = sdp.contains("a=candidate:")
            
            if (!gatheringTimeoutReached) {
                if (!hasAnyCandidate || (!hasWanCandidate && gatheringAttempts < 16)) {
                    gatheringAttempts++
                    Log.d(tag, "Gathering in progress (WAN: $hasWanCandidate). Waiting... ($gatheringAttempts)")
                    mainHandler.postDelayed({ finishGathering() }, 500)
                    return@post
                }
            }

            mainHandler.removeCallbacks(gatheringTimeoutRunnable)
            gatheringAttempts = 0
            
            currentLocalDesc?.let {
                val fullSdp = it.description
                val thinned = thinSdp(fullSdp)
                val candidateCount = fullSdp.split("\n").count { line -> line.contains("a=candidate:") }
                
                Log.i(tag, "Gathering finished. WAN: $hasWanCandidate, Total Candidates: $candidateCount. SDP Length: ${thinned.length}")
                
                onIceGatheringComplete?.invoke(thinned)
                onIceGatheringComplete = null
            } ?: run {
                Log.e(tag, "Local description is null in finishGathering!")
            }
        }
    }

    private fun createRtcConfig(): PeerConnection.RTCConfiguration {
        val iceServers = listOf(
            PeerConnection.IceServer.builder("stun:stun.l.google.com:19302").createIceServer(),
            PeerConnection.IceServer.builder("stun:stun1.l.google.com:19302").createIceServer(),
            PeerConnection.IceServer.builder("stun:stun2.l.google.com:19302").createIceServer(),
            PeerConnection.IceServer.builder("stun:stun.services.mozilla.com").createIceServer(),
            PeerConnection.IceServer.builder("stun:stun.relay.metered.ca:80").createIceServer(),
            PeerConnection.IceServer.builder("turn:openrelay.metered.ca:443?transport=tcp")
                .setUsername("openrelayproject").setPassword("openrelayproject").createIceServer(),
            PeerConnection.IceServer.builder("turn:openrelay.metered.ca:443?transport=udp")
                .setUsername("openrelayproject").setPassword("openrelayproject").createIceServer(),
            PeerConnection.IceServer.builder("turn:openrelay.metered.ca:80")
                .setUsername("openrelayproject").setPassword("openrelayproject").createIceServer()
        )
        return PeerConnection.RTCConfiguration(iceServers).apply {
            sdpSemantics = PeerConnection.SdpSemantics.UNIFIED_PLAN
            continualGatheringPolicy = PeerConnection.ContinualGatheringPolicy.GATHER_CONTINUALLY
            iceTransportsType = PeerConnection.IceTransportsType.ALL
        }
    }

    private fun createPeerConnection(config: PeerConnection.RTCConfiguration): PeerConnection? {
        if (isDestroyed || peerConnectionFactory == null) return null
        return peerConnectionFactory!!.createPeerConnection(config, object : PeerConnection.Observer {
            override fun onIceCandidate(candidate: IceCandidate) {
                mainHandler.post {
                    Log.d(tag, "Local Candidate discovered: ${candidate.sdp}")
                    
                    // Forward candidate via Trickle ICE
                    currentOfferId?.let { signalingClient.sendCandidateViaDHT(it, candidate) }

                    // EXTRA BULLETPROOF: If we discover our public IP via STUN, update the DHT node!
                    if (candidate.sdp.contains("typ srflx")) {
                        val parts = candidate.sdp.split(" ")
                        if (parts.size > 4) {
                            val ip = parts[4]
                            scope.launch {
                                try {
                                    val dhtNode = Python.getInstance().getModule("linkfront.dht_node")
                                    dhtNode.callAttr("set_public_ip", ip)
                                } catch (e: Exception) {
                                    Log.e(tag, "Failed to update DHT IP: ${e.message}")
                                }
                            }
                        }
                    }
                }
            }
            override fun onIceGatheringChange(state: PeerConnection.IceGatheringState) {
                if (isDestroyed) return
                Log.d(tag, "ICE Gathering State: $state")
                if (state == PeerConnection.IceGatheringState.COMPLETE) finishGathering()
            }
            override fun onIceConnectionChange(state: PeerConnection.IceConnectionState) {
                mainHandler.post {
                    if (isDestroyed) return@post
                    Log.d(tag, "ICE Connection State Changed: $state")
                    connectionStatus = when(state) {
                        PeerConnection.IceConnectionState.CONNECTED,
                        PeerConnection.IceConnectionState.COMPLETED -> "Verifying..."
                        PeerConnection.IceConnectionState.DISCONNECTED -> "Reconnecting..."
                        PeerConnection.IceConnectionState.FAILED,
                        PeerConnection.IceConnectionState.CLOSED -> {
                            if (peerFingerprint != null && state == PeerConnection.IceConnectionState.FAILED) {
                                 renewConnection() 
                            }
                            "Disconnected"
                        }
                        else -> "Connecting..."
                    }
                    onStateChanged?.invoke(connectionStatus)
                }
            }
            override fun onDataChannel(dc: DataChannel) { 
                mainHandler.post {
                    if (isDestroyed) return@post
                    setupDataChannel(dc) 
                }
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
        dataChannel = dc
        dc.registerObserver(object : DataChannel.Observer {
            override fun onBufferedAmountChange(p0: Long) {}
            override fun onStateChange() {
                mainHandler.post {
                    if (isDestroyed) return@post
                    Log.d(tag, "Data Channel State: ${dc.state()}")
                    if (dc.state() == DataChannel.State.OPEN) {
                        sendHandshake()
                        onStateChanged?.invoke("Data Channel Open")
                    }
                }
            }
            override fun onMessage(buffer: DataChannel.Buffer) { 
                if (isDestroyed) return
                protocol.onReceive(buffer) 
            }
        })
        protocol.setSession(session, dc, peerFingerprint)
    }

    private fun addIceCandidate(candidate: IceCandidate) {
        mainHandler.post {
            if (isDestroyed) return@post
            Log.d(tag, "Adding remote ICE candidate: ${candidate.sdp}")
            peerConnection?.addIceCandidate(candidate)
        }
    }

    fun createOffer(onSdpReady: (String) -> Unit) {
        mainHandler.post {
            if (isDestroyed) return@post
            
            prepareForNewConnection()
            if (peerConnection == null) {
                Log.e(tag, "Failed to create PeerConnection in createOffer")
                return@post
            }

            dataChannel = peerConnection?.createDataChannel("chat", DataChannel.Init())
            dataChannel?.let { setupDataChannel(it) }

            gatheringTimeoutReached = false
            onIceGatheringComplete = onSdpReady
            mainHandler.postDelayed(gatheringTimeoutRunnable, 10000)

            peerConnection?.createOffer(object : SimpleSdpObserver() {
                override fun onCreateSuccess(desc: SessionDescription?) {
                    mainHandler.post {
                        Log.d(tag, "Offer Created Successfully")
                        peerConnection?.setLocalDescription(object : SimpleSdpObserver() {
                            override fun onSetFailure(p0: String?) {
                                Log.e(tag, "SetLocalDescription Failed: $p0")
                            }
                        }, desc)
                    }
                }
                override fun onCreateFailure(p0: String?) {
                    Log.e(tag, "CreateOffer Failed: $p0")
                }
            }, MediaConstraints())
        }
    }

    private fun handleRemoteSdp(sdp: String, type: SessionDescription.Type, onAnswerReady: ((String) -> Unit)?) {
        mainHandler.post {
            if (isDestroyed) return@post

            val currentState = peerConnection?.signalingState()
            Log.d(tag, "Handling Remote SDP type: $type. Current state: $currentState")
            
            // AVOID DUPLICATES AND RACES
            if (type == SessionDescription.Type.ANSWER && currentState != PeerConnection.SignalingState.HAVE_LOCAL_OFFER) {
                Log.w(tag, "Ignoring duplicate or late ANSWER. State: $currentState")
                return@post
            }
            
            if (type == SessionDescription.Type.OFFER) {
                if (currentState == PeerConnection.SignalingState.HAVE_LOCAL_OFFER) {
                    // GLARE RESOLUTION: Deterministic winner based on fingerprint
                    if (myFingerprint < (peerFingerprint ?: "")) {
                        Log.d(tag, "Glare detected: My fingerprint is lower. I win. Ignoring remote offer.")
                        return@post
                    } else {
                        Log.d(tag, "Glare detected: Remote fingerprint is lower. Remote wins. Resetting to handle.")
                        prepareForNewConnection()
                    }
                } else {
                    prepareForNewConnection()
                }
            }
            
            if (peerConnection == null) {
                 Log.e(tag, "PeerConnection is null in handleRemoteSdp!")
                 return@post
            }

            val desc = SessionDescription(type, sdp)
            peerConnection?.setRemoteDescription(object : SimpleSdpObserver() {
                override fun onSetSuccess() {
                    mainHandler.post {
                        Log.d(tag, "SetRemoteDescription Success")
                        if (type == SessionDescription.Type.OFFER) {
                            gatheringTimeoutReached = false
                            onIceGatheringComplete = onAnswerReady
                            mainHandler.postDelayed(gatheringTimeoutRunnable, 10000)
                            peerConnection?.createAnswer(object : SimpleSdpObserver() {
                                override fun onCreateSuccess(desc: SessionDescription?) {
                                    mainHandler.post {
                                        Log.d(tag, "Answer Created Successfully")
                                        peerConnection?.setLocalDescription(object : SimpleSdpObserver() {
                                            override fun onSetFailure(p0: String?) {
                                                Log.e(tag, "SetLocalDescription Answer Failed: $p0")
                                            }
                                        }, desc)
                                    }
                                }
                                override fun onCreateFailure(p0: String?) {
                                    Log.e(tag, "CreateAnswer Failed: $p0")
                                }
                            }, MediaConstraints())
                        }
                    }
                }
                override fun onSetFailure(p0: String?) {
                    Log.e(tag, "SetRemoteDescription Failed ($type): $p0")
                }
            }, desc)
        }
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
            val peerPublicIp = json.optString("public_ip", "")
            val peerDhtPort = json.optInt("dht_port", 0)
            
            if (peerDhtPort != 0) {
                scope.launch {
                    val dhtNode = Python.getInstance().getModule("linkfront.dht_node")
                    if (peerIp.isNotEmpty()) dhtNode.callAttr("add_dht_bootstrap", peerIp, peerDhtPort)
                    if (peerPublicIp.isNotEmpty()) dhtNode.callAttr("add_dht_bootstrap", peerPublicIp, peerDhtPort)
                }
            }

            val sdp = json.getString("sdp")
            val offerId = json.optString("offer_id", "legacy")
            currentOfferId = offerId
            val peerSignalingPort = json.optInt("port", 0)

            if (type == "offer") {
                handleRemoteSdp(sdp, SessionDescription.Type.OFFER) { answerSdp ->
                    // 1. Try background signaling via DHT AND direct fast-path
                    val directIps = mutableListOf<String>()
                    if (peerPublicIp.isNotEmpty()) directIps.add(peerPublicIp)
                    if (peerIp.isNotEmpty()) directIps.add(peerIp)
                    
                    signalingClient.sendAnswer(
                        peerFingerprint!!, 
                        answerSdp, 
                        pubKey, 
                        directIps = directIps,
                        directPort = peerSignalingPort,
                        offerId = offerId
                    )
                    
                    // 2. Prepare Answer JSON for manual QR fallback
                    val answerJson = JSONObject().apply {
                        put("type", "answer")
                        put("username", myUsername)
                        put("id_key", bytesToHex(identityManager.getPublicKeyBytes()!!))
                        put("sdp", thinSdp(answerSdp))
                        put("offer_id", offerId)
                    }
                    onAnswerReady?.invoke(answerJson.toString())
                }
                // Start listening for trickle ICE candidates
                signalingClient.startCandidatePoller(offerId)
            } else if (type == "answer") {
                // Peer A scans Peer B's answer - just set the remote description
                handleRemoteSdp(sdp, SessionDescription.Type.ANSWER, null)
                Log.d(tag, "Handshake completed via QR scan")
            }
        } catch (e: Exception) {
            Log.e(tag, "QR Process Error: ${e.message}")
        }
    }

    fun getConnectionQrData(callback: (String) -> Unit) {
        createOffer { sdp ->
            val offerId = "qr_${System.currentTimeMillis()}"
            currentOfferId = offerId
            scope.launch {
                val py = Python.getInstance()
                val dhtNode = py.getModule("linkfront.dht_node")
                
                // EXTRACT PUBLIC IP FROM SDP IF DHT IS SLOW
                val sdpIp = sdp.lines()
                    .find { it.contains("typ srflx") }
                    ?.split(" ")?.getOrNull(4)
                
                if (sdpIp != null && sdpIp.isNotEmpty()) {
                    Log.i(tag, "Found Public IP in SDP: $sdpIp. Updating DHT cache.")
                    try {
                        dhtNode.callAttr("set_public_ip", sdpIp)
                    } catch (e: Exception) {
                        Log.e(tag, "Failed to set public IP from SDP: ${e.message}")
                    }
                }

                val statusJson = dhtNode.callAttr("get_dht_status").toString()
                Log.d(tag, "DHT Status for QR: $statusJson")
                
                val status = JSONObject(statusJson)
                val localIp = status.optString("local_ip", "")
                val publicIp = status.optString("public_ip", "")
                val dhtPort = status.optInt("listen_port", 0)

                val json = JSONObject().apply {
                    put("type", "offer")
                    put("username", myUsername)
                    put("id_key", bytesToHex(identityManager.getPublicKeyBytes()!!))
                    put("sdp", sdp)
                    put("ip", localIp)
                    put("public_ip", if (publicIp != "Checking..." && publicIp != "Error") publicIp else (sdpIp ?: ""))
                    put("port", signalingClient.localPort)
                    put("dht_port", dhtPort)
                    put("offer_id", offerId)
                }
                callback(json.toString())

                signalingClient.watchPostBox(offerId) { answerSdp ->
                    handleRemoteSdp(answerSdp, SessionDescription.Type.ANSWER, null)
                }
                // Start listening for trickle ICE candidates
                signalingClient.startCandidatePoller(offerId)
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
    fun sendEncrypted(targetFingerprint: String, message: String) {
        if (targetFingerprint != "SELF" && (peerFingerprint != targetFingerprint || connectionStatus != "Connected")) {
            Log.d(tag, "Sending message to $targetFingerprint while disconnected. Triggering DHT connection.")
            connectToPeerViaDHT(targetFingerprint)
        }
        protocol.sendText(targetFingerprint, message)
    }

    fun sendImage(targetFingerprint: String, bytes: ByteArray) {
        if (targetFingerprint != "SELF" && (peerFingerprint != targetFingerprint || connectionStatus != "Connected")) {
            Log.d(tag, "Sending image to $targetFingerprint while disconnected. Triggering DHT connection.")
            connectToPeerViaDHT(targetFingerprint)
        }
        protocol.sendImage(targetFingerprint, bytes)
    }

    fun getDhtStatus() = signalingClient.getDhtStatus()

    fun connectToPeerViaDHT(fingerprint: String) {
        if (!isOnline()) {
            Log.e(tag, "Aborting DHT connection: Device is offline")
            onStateChanged?.invoke("Offline")
            return
        }
        scope.launch {
            val peer = peerDao.getPeerByFingerprint(fingerprint) ?: return@launch
            val peerPubKey = peer.identityKey
            val offerId = "dht_${System.currentTimeMillis()}_${(1000..9999).random()}"
            currentOfferId = offerId

            createOffer { sdp ->
                // 1. Try DHT Signaling (Robust for NAT/WAN)
                signalingClient.sendOfferViaDHT(fingerprint, sdp, peerPubKey, offerId)
                
                // 2. Poll for the answer in the Postbox
                signalingClient.watchPostBox(offerId) { answerSdp ->
                    handleRemoteSdp(answerSdp, SessionDescription.Type.ANSWER, null)
                }

                // Start candidate poller for Trickle ICE
                signalingClient.startCandidatePoller(offerId)

                // 3. Simultaneously try direct TCP signaling (Fast for LAN/Cached)
                scope.launch {
                    val addresses = signalingClient.lookupAddress(fingerprint).toMutableList()
                    Log.d(tag, "DHT Lookup for $fingerprint returned ${addresses.size} addresses")
                    
                    // Add cached address if it exists
                    if (peer.lastKnownIp != null && peer.lastKnownPort != 0) {
                        if (addresses.none { it.first == peer.lastKnownIp && it.second == peer.lastKnownPort }) {
                            addresses.add(0, peer.lastKnownIp!! to peer.lastKnownPort)
                        }
                    }

                    for ((ip, port) in addresses) {
                        Log.d(tag, "Attempting direct offer to $ip:$port")
                        val answerSdp = signalingClient.sendOfferDirectly(ip, port, sdp, offerId)
                        if (answerSdp != null) {
                            // We found them! Update last seen address
                            peerDao.updateAddress(fingerprint, ip, port)
                            handleRemoteSdp(answerSdp, SessionDescription.Type.ANSWER, null)
                            break
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

        // FLUSH PENDING MESSAGES
        val target = peerFingerprint!!
        scope.launch(Dispatchers.IO) {
            delay(1000) // Small delay to let DataChannel stabilize
            val pending = messageDao.getPendingMessagesForPeer(target)
            if (pending.isNotEmpty()) {
                Log.i(tag, "Flushing ${pending.size} pending messages for $target")
                pending.forEach { msg ->
                    // Re-send through protocol
                    if (msg.messageType == "TEXT") {
                        messageDao.deleteMessageById(msg.id)
                        protocol.sendText(target, msg.text)
                    } else if (msg.messageType == "IMAGE" && msg.filePath != null) {
                        val file = File(msg.filePath)
                        if (file.exists()) {
                            messageDao.deleteMessageById(msg.id)
                            protocol.sendImage(target, file.readBytes())
                        }
                    }
                }
            }
        }
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
