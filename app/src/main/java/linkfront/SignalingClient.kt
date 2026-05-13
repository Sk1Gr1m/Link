package com.linkfront

import android.util.Log
import com.chaquo.python.PyObject
import com.chaquo.python.Python
import kotlinx.coroutines.*
import org.json.JSONObject
import java.net.ServerSocket
import java.net.Socket
import org.webrtc.IceCandidate

class SignalingClient(
    private val identityManager: LinkIdentityManager,
    private val onOfferReceived: (String, (String) -> Unit) -> Unit,
    private val onAnswerReceived: (String) -> Unit,
    private val onIceCandidateReceived: (IceCandidate) -> Unit
) {
    companion object {
        val PREFERRED_PORTS = listOf(8467, 8469, 8470, 8471, 8472, 8473, 8475)
    }

    private val tag = "SignalingClient"
    private val scope = CoroutineScope(Dispatchers.IO + SupervisorJob())
    private val py = Python.getInstance()
    private val linkModule = py.getModule("linkfront")
    
    private var serverSocket: ServerSocket? = null
    var localPort: Int = 0
        private set

    fun start() {
        startListener() // Keep TCP for local fast-path
        startHeartbeat()
        startOfferPoller() // New DHT-based signaling
    }

    fun stop() {
        scope.cancel()
        serverSocket?.close()
    }

    private fun startListener() {
        scope.launch {
            try {
                // Try preferred ports first
                var success = false
                for (port in PREFERRED_PORTS) {
                    try {
                        serverSocket = java.net.ServerSocket()
                        serverSocket?.reuseAddress = true 
                        serverSocket?.bind(java.net.InetSocketAddress(port))
                        localPort = port
                        success = true
                        Log.d(tag, "Listener started on preferred port $localPort")
                        break
                    } catch (e: Exception) {
                        serverSocket?.close()
                        serverSocket = null
                        continue
                    }
                }

                if (!success) {
                    serverSocket = java.net.ServerSocket(0)
                    serverSocket?.reuseAddress = true
                    localPort = serverSocket!!.localPort
                    Log.d(tag, "Listener started on fallback port $localPort")
                }
                
                while (isActive) {
                    val client = serverSocket?.accept() ?: break
                    scope.launch {
                        handleClient(client)
                    }
                }
            } catch (e: Exception) {
                Log.e(tag, "Listener error: ${e.message}")
            }
        }
    }

    private suspend fun handleClient(socket: Socket) {
        try {
            val reader = socket.getInputStream().bufferedReader()
            val input = reader.readLine() ?: return
            val json = JSONObject(input)
            val type = json.getString("type")
            val isEncrypted = json.optBoolean("encrypted", false)
            var sdp = json.optString("sdp", "")

            if (isEncrypted && sdp.isNotEmpty()) {
                val cryptoModule = py.getModule("linkfront.crypto")
                val myPrivKey = identityManager.identity?.callAttr("get_seed_bytes")
                val encryptedSdp = android.util.Base64.decode(sdp, android.util.Base64.DEFAULT)
                sdp = cryptoModule.callAttr("decrypt_with_my_key", encryptedSdp, myPrivKey).toString()
            }

            when (type.lowercase()) {
                "offer" -> {
                    val offerId = json.optString("offer_id", "legacy")
                    val answerDeferred = CompletableDeferred<String>()
                    onOfferReceived(sdp) { answer ->
                        answerDeferred.complete(answer)
                    }
                    try {
                        val answer = withTimeout(15000) { answerDeferred.await() }
                        val response = JSONObject()
                        response.put("type", "answer")
                        response.put("sdp", answer)
                        response.put("offer_id", offerId)
                        try {
                            if (socket.isConnected && !socket.isOutputShutdown) {
                                socket.getOutputStream().write((response.toString() + "\n").toByteArray())
                                socket.getOutputStream().flush()
                            }
                        } catch (e: Exception) {
                            Log.e(tag, "Failed to send answer to socket: ${e.message}")
                        }
                    } catch (e: TimeoutCancellationException) {
                        Log.e(tag, "Timed out waiting for WebRTC answer")
                    }
                }
                "answer" -> {
                    onAnswerReceived(sdp)
                }
                "candidate" -> {
                    val candidateJson = json.getJSONObject("candidate")
                    val candidate = IceCandidate(
                        candidateJson.getString("sdpMid"),
                        candidateJson.getInt("sdpMLineIndex"),
                        candidateJson.getString("sdp")
                    )
                    onIceCandidateReceived(candidate)
                }
            }
        } catch (e: Exception) {
            Log.e(tag, "Error handling signal: ${e.message}")
        } finally {
            socket.close()
        }
    }

    private fun startOfferPoller() {
        scope.launch {
            val offerBoxKey = "offer_for_${identityManager.fingerprint}"
            while (isActive) {
                try {
                    val encryptedOffer = linkModule.callAttr("get_value", offerBoxKey)
                    if (encryptedOffer != null && encryptedOffer.toString() != "None") {
                        val offerStr = encryptedOffer.toString()
                        Log.d(tag, "Received Offer via DHT Postbox (len: ${offerStr.length})")
                        
                        val cryptoModule = py.getModule("linkfront.crypto")
                        val myPrivKey = identityManager.identity?.callAttr("get_seed_bytes")
                        
                        val decrypted = try {
                            val encryptedBytes = android.util.Base64.decode(offerStr, android.util.Base64.NO_WRAP)
                            cryptoModule.callAttr("decrypt_with_my_key", encryptedBytes, myPrivKey).toString()
                        } catch (e: Exception) {
                            Log.w(tag, "Failed to decrypt DHT offer, trying legacy plain-text: ${e.message}")
                            offerStr
                        }
                        
                        val offerJson = try {
                            JSONObject(decrypted)
                        } catch (e: Exception) {
                            Log.e(tag, "Failed to parse DHT offer JSON: ${e.message}")
                            null
                        }

                        if (offerJson != null) {
                            val sdp = offerJson.getString("sdp")
                            val offerId = offerJson.optString("offer_id", "legacy")
                            val senderFingerprint = offerJson.optString("sender_fingerprint", "")
                            val senderIdKeyHex = offerJson.optString("sender_id_key", "")

                            // Consume the offer immediately
                            linkModule.callAttr("put_value", offerBoxKey, "None")

                            if (senderFingerprint.isNotEmpty() && senderIdKeyHex.isNotEmpty()) {
                                startCandidatePoller(offerId)
                                val senderIdKey = hexToBytes(senderIdKeyHex)
                                onOfferReceived(sdp) { answerSdp ->
                                    // Send answer back via DHT to the actual sender
                                    sendAnswer(senderFingerprint, answerSdp, senderIdKey, offerId = offerId)
                                }
                            } else {
                                onOfferReceived(sdp) { answerSdp ->
                                    // Send answer back via DHT (legacy fallback)
                                    sendAnswer(identityManager.fingerprint, answerSdp, null, offerId = offerId)
                                }
                            }
                        }
                    }
                } catch (e: Exception) {
                    Log.e(tag, "Offer poller error: ${e.message}")
                }
                delay(1000) // Poll every 1 second
            }
        }
    }

    fun startCandidatePoller(offerId: String) {
        scope.launch {
            val key = "candidates_$offerId"
            val seen = mutableSetOf<String>()
            while (isActive) {
                try {
                    val result = linkModule.callAttr("get_value", key)
                    if (result != null && result.toString() != "None") {
                        val candidatesArr = result.toString().split("|||")
                        for (cStr in candidatesArr) {
                            if (cStr.isNotEmpty() && seen.add(cStr)) {
                                try {
                                    val json = JSONObject(cStr)
                                    val candidate = IceCandidate(
                                        json.getString("sdpMid"),
                                        json.getInt("sdpMLineIndex"),
                                        json.getString("sdp")
                                    )
                                    onIceCandidateReceived(candidate)
                                } catch (e: Exception) {
                                    Log.e(tag, "Failed to parse candidate: ${e.message}")
                                }
                            }
                        }
                    }
                } catch (e: Exception) {
                    Log.e(tag, "Candidate poller error: ${e.message}")
                }
                delay(2000)
            }
        }
    }

    fun sendOfferViaDHT(targetFingerprint: String, sdp: String, targetPublicKey: ByteArray, offerId: String) {
        scope.launch {
            val offerBoxKey = "offer_for_$targetFingerprint"
            val cryptoModule = py.getModule("linkfront.crypto")
            
            try {
                val packet = JSONObject().apply {
                    put("sdp", sdp)
                    put("offer_id", offerId)
                    put("sender_fingerprint", identityManager.fingerprint)
                    put("sender_id_key", bytesToHex(identityManager.getPublicKeyBytes()!!))
                }
                
                val encrypted = cryptoModule.callAttr("encrypt_for_peer", packet.toString(), targetPublicKey).toJava(ByteArray::class.java)
                val encryptedB64 = android.util.Base64.encodeToString(encrypted, android.util.Base64.NO_WRAP)
                
                Log.d(tag, "Sending Offer $offerId to DHT Postbox for $targetFingerprint")
                linkModule.callAttr("put_value", offerBoxKey, encryptedB64)
            } catch (e: Exception) {
                Log.e(tag, "Failed to send offer via DHT: ${e.message}")
            }
        }
    }

    fun sendCandidateViaDHT(offerId: String, candidate: IceCandidate) {
        scope.launch {
            val key = "candidates_$offerId"
            val cJson = JSONObject().apply {
                put("sdpMid", candidate.sdpMid)
                put("sdpMLineIndex", candidate.sdpMLineIndex)
                put("sdp", candidate.sdp)
            }
            try {
                val current = linkModule.callAttr("get_value", key)?.toString() ?: ""
                val newVal = if (current.isEmpty() || current == "None") {
                    cJson.toString()
                } else {
                    "$current|||${cJson.toString()}"
                }
                linkModule.callAttr("put_value", key, newVal)
            } catch (e: Exception) {
                Log.e(tag, "Failed to send candidate via DHT: ${e.message}")
            }
        }
    }

    private fun bytesToHex(bytes: ByteArray) = bytes.joinToString("") { "%02x".format(it) }
    private fun hexToBytes(hex: String) = hex.chunked(2).map { it.toInt(16).toByte() }.toByteArray()

    private fun startHeartbeat() {
        scope.launch {
            var iterations = 0
            while (isActive) {
                publishAddress()
                
                // Aggressive start: every 5 seconds for the first minute
                // Then back off to every 2 minutes
                if (iterations < 12) {
                    delay(5000)
                } else if (iterations < 24) {
                    delay(30000)
                } else {
                    delay(2 * 60 * 1000)
                }
                iterations++
            }
        }
    }

    fun getDhtStatus(): String? {
        val py = Python.getInstance()
        return try {
            val dhtNode = py.getModule("linkfront.dht_node")
            dhtNode.callAttr("get_dht_status").toString()
        } catch (e: Exception) {
            null
        }
    }

    fun publishAddress() {
        scope.launch {
            try {
                // Restore IP cache clearing on heartbeat for freshness
                linkModule.callAttr("clear_ip_cache")

                val statusJson = linkModule.callAttr("get_dht_status").toString()
                val status = JSONObject(statusJson)
                var publicIp = status.optString("public_ip", "None")
                val localIp = status.optString("local_ip", "0.0.0.0")
                val dhtPort = status.optInt("listen_port", 0)
                
                if (publicIp == "Checking...") {
                    publicIp = "None" 
                }

                // RELAXED: Publish as long as we have a local IP and DHT is listening
                if (localIp != "0.0.0.0" && localIp != "127.0.0.1" && dhtPort != 0) {
                    val fingerprint = identityManager.fingerprint
                    if (localPort != 0) {
                        Log.i(tag, "Publishing address: $localIp:$localPort (Public: $publicIp, DHT: $dhtPort)")
                        linkModule.callAttr("publish_address", fingerprint, publicIp, localIp, localPort, dhtPort)
                    }
                } else {
                    Log.d(tag, "Delaying publish: IP not ready ($localIp) or DHT not listening ($dhtPort)")
                }
            } catch (e: Exception) {
                Log.e(tag, "Publish error: ${e.message}")
            }
        }
    }

    suspend fun lookupAddress(fingerprint: String): List<Pair<String, Int>> {
        return withContext(Dispatchers.IO) {
            val addresses = mutableListOf<Pair<String, Int>>()
            try {
                val result = linkModule.callAttr("lookup_address", fingerprint)
                if (result != null && result.toString() != "None") {
                    val map = result.asMap()
                    val signalingPort = map[PyObject.fromJava("port")]?.toInt() ?: 0
                    val dhtPort = map[PyObject.fromJava("dht_port")]?.toInt() ?: 0
                    
                    val publicIp = map[PyObject.fromJava("public_ip")]?.toString()
                    val localIp = map[PyObject.fromJava("local_ip")]?.toString()

                    if (signalingPort != 0) {
                        val ips = listOfNotNull(publicIp, localIp).filter { it != "None" }
                        for (ip in ips) {
                            // Add the explicitly published port first
                            addresses.add(ip to signalingPort)
                            
                            // Speculatively add other preferred ports in case DHT is stale
                            for (p in PREFERRED_PORTS) {
                                if (p != signalingPort) {
                                    addresses.add(ip to p)
                                }
                            }
                        }
                    }
                    
                    // Also use the discovered DHT port to bootstrap our DHT
                    if (dhtPort != 0) {
                        if (publicIp != null && publicIp != "None") {
                            linkModule.callAttr("add_dht_bootstrap", publicIp, dhtPort)
                        }
                        if (localIp != null && localIp != "None") {
                            linkModule.callAttr("add_dht_bootstrap", localIp, dhtPort)
                        }
                    }
                }
            } catch (e: Exception) {
                Log.e(tag, "Lookup error: ${e.message}")
            }
            addresses
        }
    }

    suspend fun sendOfferDirectly(ip: String, port: Int, sdp: String, offerId: String): String? {
        return withContext(Dispatchers.IO) {
            var lastError: String? = null
            for (attempt in 1..3) {
                try {
                    val socket = Socket()
                    socket.connect(java.net.InetSocketAddress(ip, port), 5000)
                    val request = JSONObject().apply {
                        put("type", "offer")
                        put("sdp", sdp)
                        put("offer_id", offerId)
                    }
                    socket.getOutputStream().write((request.toString() + "\n").toByteArray())
                    socket.getOutputStream().flush()
                    
                    val responseText = socket.getInputStream().bufferedReader().readLine()
                    if (responseText == null) {
                        socket.close()
                        continue
                    }
                    val responseJson = JSONObject(responseText)
                    socket.close()
                    return@withContext responseJson.getString("sdp")
                } catch (e: Exception) {
                    lastError = e.message
                    Log.e(tag, "Direct offer attempt $attempt failed for $ip:$port: ${e.message}")
                    if (attempt < 3) delay(1000L * attempt) // Exponential-ish backoff
                }
            }
            null
        }
    }

    fun sendAnswer(fingerprint: String, answerSdp: String, peerPublicKey: ByteArray?, directIps: List<String> = emptyList(), directPort: Int = 0, offerId: String = "legacy") {
        scope.launch {
            // 1. DHT Post Box
            val postBoxKey = if (offerId == "legacy") "answer_for_$fingerprint" else "answer_$offerId"
            val cryptoModule = py.getModule("linkfront.crypto")
            
            if (peerPublicKey != null) {
                val encrypted = cryptoModule.callAttr("encrypt_for_peer", answerSdp, peerPublicKey).toJava(ByteArray::class.java)
                val encryptedB64 = android.util.Base64.encodeToString(encrypted, android.util.Base64.NO_WRAP)
                linkModule.callAttr("put_value", postBoxKey, encryptedB64)
            } else {
                linkModule.callAttr("put_value", postBoxKey, answerSdp)
            }

            // 2. Direct Socket to all known addresses (RACING ATTEMPT)
            val addresses = lookupAddress(fingerprint).toMutableList()
            for (ip in directIps) {
                if (ip.isNotEmpty() && directPort != 0) {
                    if (addresses.none { it.first == ip && it.second == directPort }) {
                        addresses.add(0, ip to directPort)
                    }
                }
            }

            // Launch all connection attempts in parallel
            addresses.forEach { (ip, port) ->
                scope.launch {
                    var success = false
                    for (attempt in 1..2) { // 2 attempts is enough for racing
                        try {
                            val socket = Socket()
                            // Short timeout for racing: 3 seconds
                            socket.connect(java.net.InetSocketAddress(ip, port), 3000)
                            val request = JSONObject().apply {
                                put("type", "answer")
                                put("offer_id", offerId)
                                if (peerPublicKey != null) {
                                    val encrypted = cryptoModule.callAttr("encrypt_for_peer", answerSdp, peerPublicKey).toJava(ByteArray::class.java)
                                    val encryptedB64 = android.util.Base64.encodeToString(encrypted, android.util.Base64.NO_WRAP)
                                    put("sdp", encryptedB64)
                                    put("encrypted", true)
                                } else {
                                    put("sdp", answerSdp)
                                    put("encrypted", false)
                                }
                            }
                            socket.getOutputStream().write((request.toString() + "\n").toByteArray())
                            socket.getOutputStream().flush()
                            socket.close()
                            Log.i(tag, "Direct answer RACING SUCCESS to $ip:$port")
                            success = true
                            break
                        } catch (e: Exception) {
                            Log.d(tag, "Race attempt $attempt failed for $ip:$port: ${e.message}")
                            if (attempt < 2) delay(500)
                        }
                    }
                }
            }
        }
    }

    fun watchPostBox(offerId: String, callback: (String) -> Unit) {
        scope.launch {
            val postBoxKey = "answer_$offerId"
            for (i in 1..60) {
                val result = linkModule.callAttr("get_value", postBoxKey)
                if (result != null && result.toString() != "None") {
                    val resultStr = result.toString()
                    try {
                        val cryptoModule = py.getModule("linkfront.crypto")
                        val myPrivKey = identityManager.identity?.callAttr("get_seed_bytes")
                        
                        val decrypted = try {
                            val encryptedBytes = android.util.Base64.decode(resultStr, android.util.Base64.NO_WRAP)
                            cryptoModule.callAttr("decrypt_with_my_key", encryptedBytes, myPrivKey).toString()
                        } catch (e: Exception) {
                            // Fallback to raw if decryption/decode fails
                            resultStr
                        }
                        
                        callback(decrypted)
                        linkModule.callAttr("put_value", postBoxKey, "None")
                        break
                    } catch (e: Exception) {
                        Log.e(tag, "Post box handling error: ${e.message}")
                    }
                }
                delay(2000)
            }
        }
    }
}
