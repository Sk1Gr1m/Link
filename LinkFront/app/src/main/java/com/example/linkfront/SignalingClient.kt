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
            val input = socket.getInputStream().bufferedReader().readText()
            val json = JSONObject(input)
            val type = json.getString("type")
            val isEncrypted = json.optBoolean("encrypted", false)
            var sdp = json.getString("sdp")

            if (isEncrypted) {
                val cryptoModule = py.getModule("linkfront.crypto")
                val myPrivKey = identityManager.getEncryptionPrivateKeyBytes()
                sdp = cryptoModule.callAttr("decrypt_with_my_key", sdp, myPrivKey).toString()
            }

            when (type.lowercase()) {
                "offer" -> {
                    onOfferReceived(sdp) { answer ->
                        val response = JSONObject()
                        response.put("type", "answer")
                        response.put("sdp", answer)
                        socket.getOutputStream().write(response.toString().toByteArray())
                        socket.getOutputStream().flush()
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

    private fun startHeartbeat() {
        scope.launch {
            while (isActive) {
                publishAddress()
                delay(5 * 60 * 1000) // 5 minutes
            }
        }
    }

    fun publishAddress() {
        scope.launch {
            try {
                val publicIp = linkModule.callAttr("get_public_ip")?.toString()
                val fingerprint = identityManager.fingerprint
                if (publicIp != null && localPort != 0) {
                    linkModule.callAttr("publish_address", fingerprint, publicIp, localPort)
                }
            } catch (e: Exception) {
                Log.e(tag, "Publish error: ${e.message}")
            }
        }
    }

    fun sendAnswer(fingerprint: String, answerSdp: String, peerPublicKey: ByteArray?) {
        scope.launch {
            // 1. DHT Post Box
            val postBoxKey = "answer_for_$fingerprint"
            val cryptoModule = py.getModule("linkfront.crypto")
            
            if (peerPublicKey != null) {
                val encrypted = cryptoModule.callAttr("encrypt_for_peer", answerSdp, peerPublicKey)
                linkModule.callAttr("put_value", postBoxKey, encrypted)
            } else {
                linkModule.callAttr("put_value", postBoxKey, answerSdp)
            }

            // 2. Direct Socket
            val peerAddr = linkModule.callAttr("lookup_address", fingerprint)
            if (peerAddr != null) {
                val ip = peerAddr.asMap()[PyObject.fromJava("ip")]?.toString()
                val port = peerAddr.asMap()[PyObject.fromJava("port")]?.toInt()
                if (ip != null && port != null) {
                    try {
                        val socket = Socket()
                        socket.connect(java.net.InetSocketAddress(ip, port), 2000)
                        val request = JSONObject()
                        request.put("type", "answer")
                        
                        if (peerPublicKey != null) {
                            val encrypted = cryptoModule.callAttr("encrypt_for_peer", answerSdp, peerPublicKey)
                            request.put("sdp", encrypted.toString())
                            request.put("encrypted", true)
                        } else {
                            request.put("sdp", answerSdp)
                            request.put("encrypted", false)
                        }
                        socket.getOutputStream().write(request.toString().toByteArray())
                        socket.close()
                    } catch (e: Exception) {
                        Log.d(tag, "Direct answer failed: ${e.message}")
                    }
                }
            }
        }
    }

    fun watchPostBox(callback: (String) -> Unit) {
        scope.launch {
            val postBoxKey = "answer_for_${identityManager.fingerprint}"
            for (i in 1..60) {
                val encrypted = linkModule.callAttr("get_value", postBoxKey)
                if (encrypted != null && encrypted.toString() != "None") {
                    try {
                        val cryptoModule = py.getModule("linkfront.crypto")
                        val myPrivKey = identityManager.getEncryptionPrivateKeyBytes()
                        val decrypted = cryptoModule.callAttr("decrypt_with_my_key", encrypted, myPrivKey).toString()
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
