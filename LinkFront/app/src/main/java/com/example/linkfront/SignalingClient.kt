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
    private val onAnswerReceived: (String) -> Unit,
) {
    private val tag = "SignalingClient"
    private val scope = CoroutineScope(Dispatchers.IO + SupervisorJob())
    private val py = Python.getInstance()
    private val linkModule = py.getModule("linkfront")
    
    private var serverSocket: ServerSocket? = null
    var localPort: Int = 0
        private set

    fun start() {
        startListener()
        startHeartbeat()
    }

    fun stop() {
        scope.cancel()
        serverSocket?.close()
    }

    private fun startListener() {
        scope.launch {
            try {
                serverSocket = ServerSocket(0)
                localPort = serverSocket!!.localPort
                Log.d(tag, "Listener started on port $localPort")
                
                // Fast-track: Publish address immediately once port is known
                publishAddress()
                
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
            val input = withContext(Dispatchers.IO) {
                socket.getInputStream().bufferedReader().readLine()
            } ?: return
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
                    val answerDeferred = CompletableDeferred<String>()
                    onOfferReceived(sdp) { answer ->
                        answerDeferred.complete(answer)
                    }
                    try {
                        val answer = withTimeout(15000) { answerDeferred.await() }
                        val response = JSONObject()
                        response.put("type", "answer")
                        response.put("sdp", answer)
                        try {
                            if (socket.isConnected && !socket.isOutputShutdown) {
                                withContext(Dispatchers.IO) {
                                    socket.getOutputStream().write((response.toString() + "\n").toByteArray())
                                    socket.getOutputStream().flush()
                                }
                            }
                        } catch (e: Exception) {
                            Log.e(tag, "Failed to send answer to socket: ${e.message}")
                        }
                    } catch (_: TimeoutCancellationException) {
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
            withContext(Dispatchers.IO) {
                socket.close()
            }
        }
    }

    private fun startHeartbeat() {
        scope.launch {
            var iterations = 0
            while (isActive) {
                publishAddress()
                
                // First 60 seconds: publish every 5 seconds for fast discovery
                // After that: every 5 minutes
                if (iterations < 12) {
                    delay(5000)
                } else {
                    delay(5 * 60 * 1000)
                }
                iterations++
            }
        }
    }

    fun getDhtStatus(): String? {
        return try {
            val dhtNode = py.getModule("linkfront.dht_node")
            dhtNode.callAttr("get_dht_status").toString()
        } catch (_: Exception) {
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
                if ((localIp != "0.0.0.0") && (localIp != "127.0.0.1") && (localIp != "Checking...") && (dhtPort != 0)) {
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
                        if (publicIp != null && publicIp != "None") addresses.add(publicIp to signalingPort)
                        if (localIp != null && localIp != "None") addresses.add(localIp to signalingPort)
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

    suspend fun sendOfferDirectly(ip: String, port: Int, sdp: String): String? {
        return withContext(Dispatchers.IO) {
            var lastError: String? = null
            for (attempt in 1..3) {
                try {
                    val socket = Socket()
                    socket.connect(java.net.InetSocketAddress(ip, port), 3000)
                    val request = JSONObject().apply {
                        put("type", "offer")
                        put("sdp", sdp)
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

    fun sendAnswer(fingerprint: String, answerSdp: String, peerPublicKey: ByteArray?) {
        scope.launch {
            // 1. DHT Post Box
            val postBoxKey = "answer_for_$fingerprint"
            val cryptoModule = py.getModule("linkfront.crypto")
            
            if (peerPublicKey != null) {
                val encrypted = cryptoModule.callAttr("encrypt_for_peer", answerSdp, peerPublicKey).toJava(ByteArray::class.java)
                val encryptedB64 = android.util.Base64.encodeToString(encrypted, android.util.Base64.NO_WRAP)
                linkModule.callAttr("put_value", postBoxKey, encryptedB64)
            } else {
                linkModule.callAttr("put_value", postBoxKey, answerSdp)
            }

            // 2. Direct Socket to all known addresses
            val addresses = lookupAddress(fingerprint)
            for ((ip, port) in addresses) {
                try {
                    val socket = Socket()
                    socket.connect(java.net.InetSocketAddress(ip, port), 2000)
                    val request = JSONObject()
                    request.put("type", "answer")
                    
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
                    Log.d(tag, "Direct answer sent to $ip:$port")
                    break // Successfully sent to one address
                } catch (e: Exception) {
                    Log.d(tag, "Direct answer failed for $ip:$port: ${e.message}")
                }
            }
        }
    }

    fun watchPostBox(callback: (String) -> Unit) {
        scope.launch {
            val postBoxKey = "answer_for_${identityManager.fingerprint}"
            repeat(60) {
                val encrypted = linkModule.callAttr("get_value", postBoxKey)
                if (encrypted != null && encrypted.toString() != "None") {
                    try {
                        val cryptoModule = py.getModule("linkfront.crypto")
                        val myPrivKey = identityManager.identity?.callAttr("get_seed_bytes")
                        val encryptedBytes = android.util.Base64.decode(encrypted.toString(), android.util.Base64.DEFAULT)
                        val decrypted = cryptoModule.callAttr("decrypt_with_my_key", encryptedBytes, myPrivKey).toString()
                        callback(decrypted)
                        linkModule.callAttr("put_value", postBoxKey, "None")
                        return@launch
                    } catch (e: Exception) {
                        Log.e(tag, "Post box decrypt error: ${e.message}")
                    }
                }
                delay(2000)
            }
        }
    }
}
