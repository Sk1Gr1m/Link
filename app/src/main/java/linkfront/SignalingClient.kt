package com.linkfront

import android.util.Log
import com.chaquo.python.PyObject
import com.chaquo.python.Python
import kotlinx.coroutines.*
import org.json.JSONArray
import org.json.JSONObject
import java.net.ServerSocket
import java.net.Socket
import org.webrtc.IceCandidate

// Handles direct TCP signaling and DHT message exchange
class SignalingClient(
    private val identityManager: LinkIdentityManager,
    private val onOfferReceived: (String, String, (String) -> Unit) -> Unit,
    private val onAnswerReceived: (String) -> Unit,
    private val onIceCandidateReceived: (IceCandidate) -> Unit
) {
    companion object {
        // Ports used for direct TCP signaling
        val PREFERRED_PORTS = listOf(8467, 8469, 8470, 8471, 8472, 8473, 8475)
    }

    private val tag = "SignalingClient"
    private val scope = CoroutineScope(Dispatchers.IO + SupervisorJob())
    private val py = Python.getInstance()
    private val linkModule = py.getModule("linkfront.dht_node")

    private var serverSocket: ServerSocket? = null
    var localPort: Int = 0
        private set

    // Start all signaling background tasks
    fun start() {
        startListener()
        startOfferPoller()
        startHeartbeat()
    }

    // Stop all tasks and close the server
    fun stop() {
        scope.cancel()
        serverSocket?.close()
    }

    // Listens for incoming direct signaling connections
    private fun startListener() {
        scope.launch {
            for (port in PREFERRED_PORTS) {
                try {
                    serverSocket = ServerSocket(port)
                    localPort = port
                    Log.i(tag, "Signaling listener started on port $port")
                    publishAddress()
                    break
                } catch (e: Exception) {
                    Log.w(tag, "Port $port busy, trying next...")
                }
            }
            
            if (serverSocket == null) {
                serverSocket = ServerSocket(0)
                localPort = serverSocket!!.localPort
                Log.i(tag, "Signaling listener started on random port $localPort")
                publishAddress()
            }

            while (isActive) {
                try {
                    val socket = serverSocket?.accept() ?: break
                    handleClient(socket)
                } catch (e: Exception) {
                    if (isActive) Log.e(tag, "Error accepting signaling connection: ${e.message}")
                }
            }
        }
    }

    // Handles an individual signaling connection
    private fun handleClient(socket: Socket) {
        scope.launch {
            try {
                val reader = socket.getInputStream().bufferedReader()
                val line = reader.readLine()
                if (line != null) {
                    val json = JSONObject(line)
                    val type = json.getString("type")
                    val sdp = json.optString("sdp", "")
                    val offerId = json.optString("offer_id", "legacy")

                    Log.d(tag, "Direct signaling received: $type from ${socket.inetAddress}")

                    when (type) {
                        "offer" -> {
                            onOfferReceived(sdp, offerId) { answerSdp ->
                                scope.launch {
                                    try {
                                        val response = JSONObject().apply {
                                            put("type", "answer")
                                            put("sdp", answerSdp)
                                            put("offer_id", offerId)
                                        }
                                        socket.getOutputStream().write((response.toString() + "\n").toByteArray())
                                        socket.getOutputStream().flush()
                                        socket.close()
                                    } catch (e: Exception) {
                                        Log.e(tag, "Failed to send direct answer: ${e.message}")
                                    }
                                }
                            }
                        }
                        "answer" -> {
                            onAnswerReceived(sdp)
                            socket.close()
                        }
                        else -> socket.close()
                    }
                }
            } catch (e: Exception) {
                Log.e(tag, "Error handling signaling client: ${e.message}")
                socket.close()
            }
        }
    }

    // Polls DHT for incoming WebRTC offers
    private fun startOfferPoller() {
        scope.launch {
            while (isActive) {
                try {
                    val result = linkModule.callAttr("receive_signal", identityManager.fingerprint, "", "OFFER")
                    if (result != null && result.toString() != "None") {
                        val signalJson = JSONObject(result.toString())
                        val senderFingerprint = signalJson.getString("from")
                        val contentStr = signalJson.getString("content")
                        val content = JSONObject(contentStr)
                        
                        val sdp = content.getString("sdp")
                        val offerId = content.optString("offer_id", "legacy")
                        val senderIdKeyHex = content.optString("sender_id_key", "")

                        Log.d(tag, "Received Offer via DHT from $senderFingerprint")

                        if (senderFingerprint.isNotEmpty() && senderIdKeyHex.isNotEmpty()) {
                            startCandidatePoller(senderFingerprint)
                            val senderIdKey = hexToBytes(senderIdKeyHex)
                            onOfferReceived(sdp, offerId) { answerSdp ->
                                sendAnswer(senderFingerprint, answerSdp, senderIdKey, offerId = offerId)
                            }
                        }
                    }
                } catch (e: Exception) {
                    Log.e(tag, "Offer poller error: ${e.message}")
                }
                delay(3000)
            }
        }
    }

    // Polls DHT for incoming ICE candidates
    fun startCandidatePoller(peerFingerprint: String) {
        scope.launch {
            val seen = mutableSetOf<String>()
            while (isActive) {
                try {
                    val result = linkModule.callAttr("receive_signal", identityManager.fingerprint, peerFingerprint, "CANDIDATE")
                    if (result != null && result.toString() != "None") {
                        val candidatesArr = JSONArray(result.toString())
                        for (i in 0 until candidatesArr.length()) {
                            val cStr = candidatesArr.getString(i)
                            if (seen.add(cStr)) {
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

    // Encrypt and send a WebRTC offer through the DHT
    fun sendOfferViaDHT(targetFingerprint: String, sdp: String, targetPublicKey: ByteArray, offerId: String) {
        scope.launch {
            try {
                val packet = JSONObject().apply {
                    put("sdp", sdp)
                    put("offer_id", offerId)
                    put("sender_fingerprint", identityManager.fingerprint)
                    put("sender_id_key", bytesToHex(identityManager.getPublicKeyBytes()!!))
                }
                
                linkModule.callAttr("send_signal", targetFingerprint, identityManager.fingerprint, "OFFER", packet.toString())
            } catch (e: Exception) {
                Log.e(tag, "Failed to send offer via DHT: ${e.message}")
            }
        }
    }

    // Send an ICE candidate to a peer via DHT
    fun sendCandidateViaDHT(targetFingerprint: String, candidate: IceCandidate, alternateChannel: String? = null) {
        scope.launch {
            val cJson = JSONObject().apply {
                put("sdpMid", candidate.sdpMid)
                put("sdpMLineIndex", candidate.sdpMLineIndex)
                put("sdp", candidate.sdp)
            }
            try {
                // Send to primary fingerprint channel
                linkModule.callAttr("send_signal", targetFingerprint, identityManager.fingerprint, "CANDIDATE", cJson.toString())
                
                // If we have an offerId/handshake channel, also post there for discovery
                if (alternateChannel != null && alternateChannel != targetFingerprint) {
                    linkModule.callAttr("send_signal", targetFingerprint, alternateChannel, "CANDIDATE", cJson.toString())
                }
            } catch (e: Exception) {
                Log.e(tag, "Failed to send candidate via DHT: ${e.message}")
            }
        }
    }

    private fun bytesToHex(bytes: ByteArray): String = bytes.joinToString("") { "%02x".format(it) }
    private fun hexToBytes(hex: String): ByteArray = hex.chunked(2).map { it.toInt(16).toByte() }.toByteArray()

    private fun startHeartbeat() {
        scope.launch {
            while (isActive) {
                try {
                    publishAddress()
                } catch (e: Exception) {
                    Log.e(tag, "Heartbeat error: ${e.message}")
                }
                delay(300000)
            }
        }
    }

    // Check current connection status of the DHT node
    fun getDhtStatus(): String? {
        return try {
            linkModule.callAttr("get_dht_status").toString()
        } catch (e: Exception) {
            null
        }
    }

    // Publishes local address info to the DHT
    fun publishAddress() {
        scope.launch {
            try {
                val statusJson = getDhtStatus() ?: return@launch
                val status = JSONObject(statusJson)
                val publicIp = status.optString("public_ip", "None")
                val localIp = status.optString("local_ip", "127.0.0.1")
                
                // Use public_port if available from STUN, otherwise fallback to local listen_port
                val publicDhtPort = status.optInt("public_port", 0)
                val dhtPort = if (publicDhtPort != 0) publicDhtPort else status.optInt("listen_port", 0)
                
                linkModule.callAttr("publish_address", identityManager.fingerprint, publicIp, localIp, localPort, dhtPort)
            } catch (e: Exception) {
                Log.e(tag, "Failed to publish address: ${e.message}")
            }
        }
    }

    // Find a peer's IP addresses stored in the DHT
    fun lookupAddress(fingerprint: String): List<Pair<String, Int>> {
        val result = mutableListOf<Pair<String, Int>>()
        try {
            val addrJson = linkModule.callAttr("lookup_address", fingerprint)
            if (addrJson != null && addrJson.toString() != "None") {
                val data = JSONObject(addrJson.toString())
                val publicIp = data.optString("public_ip")
                val localIp = data.optString("local_ip")
                val port = data.optInt("port")
                
                if (publicIp != "None" && publicIp.isNotEmpty()) {
                    result.add(publicIp to port)
                }
                if (localIp.isNotEmpty() && localIp != "127.0.0.1") {
                    result.add(localIp to port)
                }
            }
        } catch (e: Exception) {
            Log.e(tag, "Address lookup failed: ${e.message}")
        }
        return result
    }

    // Attempt to send an offer directly via TCP socket
    fun sendOfferDirectly(ip: String, port: Int, sdp: String, offerId: String): String? {
        return try {
            val socket = Socket()
            socket.connect(java.net.InetSocketAddress(ip, port), 5000)
            val request = JSONObject().apply {
                put("type", "offer")
                put("sdp", sdp)
                put("offer_id", offerId)
            }
            socket.getOutputStream().write((request.toString() + "\n").toByteArray())
            socket.getOutputStream().flush()
            
            val reader = socket.getInputStream().bufferedReader()
            val responseLine = reader.readLine()
            socket.close()
            
            if (responseLine != null) {
                val response = JSONObject(responseLine)
                if (response.getString("type") == "answer") {
                    return response.getString("sdp")
                }
            }
            null
        } catch (e: Exception) {
            Log.d(tag, "Direct offer to $ip:$port failed: ${e.message}")
            null
        }
    }

    // Sends a WebRTC answer via DHT and direct TCP
    fun sendAnswer(targetFingerprint: String, answerSdp: String, peerPublicKey: ByteArray?, directIps: List<String> = emptyList(), directPort: Int = 0, offerId: String = "legacy") {
        scope.launch {
            // 1. DHT signaling
            try {
                Log.d(tag, "Sending Answer to $targetFingerprint via DHT")
                linkModule.callAttr("send_signal", targetFingerprint, identityManager.fingerprint, "ANSWER", answerSdp)
                
                if (offerId != "legacy") {
                    // Fallback channel
                    linkModule.callAttr("send_signal", targetFingerprint, offerId, "ANSWER", answerSdp)
                }
            } catch (e: Exception) {
                Log.e(tag, "Failed to send answer via DHT: ${e.message}")
            }

            // 2. Direct TCP racing
            val addresses = lookupAddress(targetFingerprint).toMutableList()
            for (ip in directIps) {
                if (ip.isNotEmpty() && directPort != 0) {
                    if (addresses.none { it.first == ip && it.second == directPort }) {
                        addresses.add(0, ip to directPort)
                    }
                }
            }

            addresses.forEach { (ip, port) ->
                scope.launch {
                    try {
                        val socket = Socket()
                        socket.connect(java.net.InetSocketAddress(ip, port), 3000)
                        val request = JSONObject().apply {
                            put("type", "answer")
                            put("offer_id", offerId)
                            put("sdp", answerSdp)
                        }
                        socket.getOutputStream().write((request.toString() + "\n").toByteArray())
                        socket.getOutputStream().flush()
                        socket.close()
                        Log.i(tag, "Direct answer RACING SUCCESS to $ip:$port")
                    } catch (_: Exception) {}
                }
            }
        }
    }

    // Watches DHT for an answer on a specific channel
    fun watchPostBox(channel: String, callback: (String) -> Unit) {
        scope.launch {
            for (i in 1..60) {
                try {
                    val result = linkModule.callAttr("receive_signal", identityManager.fingerprint, channel, "ANSWER")
                    if (result != null && result.toString() != "None") {
                        val signalJson = JSONObject(result.toString())
                        val answerSdp = signalJson.getString("content")
                        Log.d(tag, "Received Answer via DHT from $channel")
                        callback(answerSdp)
                        break
                    }
                } catch (e: Exception) {
                    Log.e(tag, "Post box handling error: ${e.message}")
                }
                delay(2000)
            }
        }
    }
}
