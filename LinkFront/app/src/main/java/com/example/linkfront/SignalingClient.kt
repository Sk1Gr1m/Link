package com.example.linkfront

import android.util.Log
import com.chaquo.python.PyObject
import com.chaquo.python.Python
import kotlinx.coroutines.*
import org.json.JSONObject
import java.net.ServerSocket
import java.net.Socket

class SignalingClient(
    private val identityManager: LinkIdentityManager,
    private val onOfferReceived: (String, (String) -> Unit) -> Unit,
    private val onAnswerReceived: (String) -> Unit
) {
    companion object {
        val PREFERRED_PORTS = listOf(8467, 8469, 8470)
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
            var sdp = json.getString("sdp")

            if (isEncrypted) {
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
                        Log.d(tag, "Received Offer via DHT Postbox")
                        
                        val cryptoModule = py.getModule("linkfront.crypto")
                        val myPrivKey = identityManager.identity?.callAttr("get_seed_bytes")
                        val encryptedBytes = android.util.Base64.decode(encryptedOffer.toString(), android.util.Base64.DEFAULT)
                        val decrypted = cryptoModule.callAttr("decrypt_with_my_key", encryptedBytes, myPrivKey).toString()
                        
                        val offerJson = JSONObject(decrypted)
                        val sdp = offerJson.getString("sdp")
                        val offerId = offerJson.optString("offer_id", "legacy")

                        // Consume the offer immediately
                        linkModule.callAttr("put_value", offerBoxKey, "None")

                        onOfferReceived(sdp) { answerSdp ->
                            // Send answer back via DHT
                            sendAnswer(identityManager.fingerprint, answerSdp, null, offerId = offerId)
                        }
                    }
                } catch (e: Exception) {
                    Log.e(tag, "Offer poller error: ${e.message}")
                }
                delay(3000) // Poll every 3 seconds
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

    private fun startHeartbeat() {
        scope.launch {
            var iterations = 0
            while (isActive) {
                publishAddress()
                
                // First 60 seconds: publish every 5 seconds for fast discovery
                // After that: every 2 minutes
                if (iterations < 12) {
                    delay(5000)
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
                val statusJson = linkModule.callAttr("get_dht_status").toString()
                val status = JSONObject(statusJson)
                val publicIp = status.optString("public_ip", "None")
                val localIp = status.optString("local_ip", "0.0.0.0")
                val dhtPort = status.optInt("listen_port", 0)
                
                // CRITICAL: Only publish if we have a real IP address and the DHT is listening
                if (localIp != "0.0.0.0" && localIp != "127.0.0.1" && localIp != "Checking..." && dhtPort != 0) {
                    val fingerprint = identityManager.fingerprint
                    if (localPort != 0) {
                        Log.i(tag, "Publishing address: $localIp:$localPort (DHT: $dhtPort)")
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
                    socket.connect(java.net.InetSocketAddress(ip, port), 3000)
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
                    Log.e(tag, "Direct offer attempt $attempt failed: ${e.message}")
                    if (attempt < 3) delay(1000L * attempt) // Exponential-ish backoff
                }
            }
            Log.e(tag, "Direct offer failed after 3 attempts: $lastError")
            null
        }
    }

    fun sendAnswer(fingerprint: String, answerSdp: String, peerPublicKey: ByteArray?, directIp: String? = null, directPort: Int = 0, offerId: String = "legacy") {
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

            // 2. Direct Socket to all known addresses
            val addresses = lookupAddress(fingerprint).toMutableList()
            if (directIp != null && directPort != 0) {
                if (addresses.none { it.first == directIp && it.second == directPort }) {
                    addresses.add(0, directIp to directPort)
                }
            }

            for ((ip, port) in addresses) {
                var success = false
                for (attempt in 1..3) {
                    try {
                        val socket = Socket()
                        socket.connect(java.net.InetSocketAddress(ip, port), 3000)
                        val request = JSONObject()
                        request.put("type", "answer")
                        request.put("offer_id", offerId)
                        
                        if (peerPublicKey != null) {
                            val encrypted = cryptoModule.callAttr("encrypt_for_peer", answerSdp, peerPublicKey).toJava(ByteArray::class.java)
                            val encryptedB64 = android.util.Base64.encodeToString(encrypted, android.util.Base64.NO_WRAP)
                            request.put("sdp", encryptedB64)
                            request.put("encrypted", true)
                        } else {
                            request.put("sdp", answerSdp)
                            request.put("encrypted", false)
                        }
                        socket.getOutputStream().write((request.toString() + "\n").toByteArray())
                        socket.getOutputStream().flush()
                        socket.close()
                        Log.d(tag, "Direct answer sent to $ip:$port on attempt $attempt")
                        success = true
                        break
                    } catch (e: Exception) {
                        Log.d(tag, "Direct answer attempt $attempt failed for $ip:$port: ${e.message}")
                        if (attempt < 3) delay(1000)
                    }
                }
                if (success) break // Successfully sent to one address
            }
        }
    }

    fun watchPostBox(offerId: String, callback: (String) -> Unit) {
        scope.launch {
            val postBoxKey = "answer_$offerId"
            for (i in 1..60) {
                val encrypted = linkModule.callAttr("get_value", postBoxKey)
                if (encrypted != null && encrypted.toString() != "None") {
                    try {
                        val cryptoModule = py.getModule("linkfront.crypto")
                        val myPrivKey = identityManager.identity?.callAttr("get_seed_bytes")
                        val encryptedBytes = android.util.Base64.decode(encrypted.toString(), android.util.Base64.DEFAULT)
                        val decrypted = cryptoModule.callAttr("decrypt_with_my_key", encryptedBytes, myPrivKey).toString()
                        callback(decrypted)
                        linkModule.callAttr("put_value", postBoxKey, "None")
                        break
                    } catch (e: Exception) {
                        Log.e(tag, "Post box decrypt error: ${e.message}")
                    }
                }
                delay(2000)
            }
        }
    }
}
