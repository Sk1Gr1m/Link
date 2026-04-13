package com.example.linkfront

import android.content.Context
import android.util.Base64
import android.util.Log
import com.chaquo.python.PyObject
import com.chaquo.python.Python
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import org.json.JSONObject
import org.webrtc.DataChannel
import java.io.ByteArrayOutputStream
import java.io.File
import java.nio.ByteBuffer

class LinkProtocol(
    private val context: Context,
    private val messageDao: MessageDao,
    private val scope: CoroutineScope,
    private val onMessageReceived: ((String) -> Unit)?
) {
    private val tag = "LinkProtocol"
    private var session: PyObject? = null
    private var dataChannel: DataChannel? = null
    private var peerFingerprint: String? = null

    private val incomingImages = mutableMapOf<String, MutableMap<Int, ByteArray>>()
    private val incomingMessageIds = mutableMapOf<String, Long>()

    fun setSession(session: PyObject?, dataChannel: DataChannel?, peerFingerprint: String?) {
        this.session = session
        this.dataChannel = dataChannel
        this.peerFingerprint = peerFingerprint
    }

    fun sendText(message: String) {
        val currentFingerprint = peerFingerprint ?: return
        scope.launch(Dispatchers.IO) {
            val msgId = messageDao.insert(MessageEntity(
                peerFingerprint = currentFingerprint,
                text = message,
                isMe = true,
                messageType = "TEXT",
                transferStatus = "PENDING"
            )).toInt()

            try {
                val packet = JSONObject().apply {
                    put("type", "chat")
                    put("id", msgId)
                    put("text", message)
                }
                if (encryptAndSend(packet)) {
                    messageDao.updateTransferProgress(msgId, 100, "COMPLETED", "")
                } else {
                    messageDao.updateTransferProgress(msgId, 0, "FAILED", "")
                }
            } catch (e: Exception) {
                messageDao.updateTransferProgress(msgId, 0, "FAILED", "")
            }
        }
    }

    fun sendImage(imageBytes: ByteArray) {
        val currentFingerprint = peerFingerprint ?: return
        scope.launch(Dispatchers.IO) {
            try {
                val fileName = "img_${System.currentTimeMillis()}.jpg"
                val file = File(context.filesDir, fileName).apply { writeBytes(imageBytes) }
                
                val chunkSize = 32 * 1024
                val totalChunks = (imageBytes.size + chunkSize - 1) / chunkSize
                val transferId = System.currentTimeMillis().toString()

                val messageId = messageDao.insert(MessageEntity(
                    peerFingerprint = currentFingerprint,
                    text = "[Image]",
                    isMe = true,
                    messageType = "IMAGE",
                    filePath = file.absolutePath,
                    transferStatus = "PENDING",
                    progress = 0
                )).toInt()

                for (i in 0 until totalChunks) {
                    val start = i * chunkSize
                    val end = minOf(start + chunkSize, imageBytes.size)
                    val chunkData = imageBytes.copyOfRange(start, end)
                    
                    val packet = JSONObject().apply {
                        put("type", "image_chunk")
                        put("id", transferId)
                        put("index", i)
                        put("total", totalChunks)
                        put("data", Base64.encodeToString(chunkData, Base64.NO_WRAP))
                    }

                    if (encryptAndSend(packet)) {
                        val progress = ((i + 1) * 100) / totalChunks
                        if (progress == 100) {
                            messageDao.updateStatus(messageId, "COMPLETED")
                        } else {
                            messageDao.updateTransferProgress(messageId, progress, "PENDING", file.absolutePath)
                        }
                    }
                    if (i % 5 == 0) delay(10)
                }
            } catch (e: Exception) {
                Log.e(tag, "Failed to send image: ${e.message}")
            }
        }
    }

    private fun encryptAndSend(json: JSONObject): Boolean {
        val s = session ?: return false
        val dc = dataChannel ?: return false
        if (dc.state() != DataChannel.State.OPEN) return false
        
        return try {
            val encrypted = s.callAttr("encrypt", json.toString()).toJava(ByteArray::class.java)
            dc.send(DataChannel.Buffer(ByteBuffer.wrap(encrypted), true))
            true
        } catch (e: Exception) {
            false
        }
    }

    fun onReceive(buffer: DataChannel.Buffer) {
        val data = ByteArray(buffer.data.remaining())
        buffer.data.get(data)
        
        // 1. Try to handle unencrypted handshake
        if (session == null) {
            try {
                val text = String(data, Charsets.UTF_8)
                val json = JSONObject(text)
                if (json.optString("type") == "handshake") {
                    // Handshake handling will be called from WebRTCManager for now 
                    // or we pass a callback here. Let's use a callback for session establishment.
                    onHandshakeReceived?.invoke(json)
                    return
                }
            } catch (e: Exception) {
                Log.e(tag, "Failed to parse potential handshake: ${e.message}")
            }
        }

        // 2. Handle encrypted protocol messages
        val s = session ?: return
        try {
            val decrypted = s.callAttr("decrypt", data).toString()
            val json = JSONObject(decrypted)
            when (json.getString("type")) {
                "chat" -> handleChat(json)
                "ack" -> handleAck(json)
                "image_chunk" -> handleImageChunk(json)
            }
        } catch (e: Exception) {
            Log.e(tag, "Protocol decrypt error: ${e.message}")
        }
    }

    var onHandshakeReceived: ((JSONObject) -> Unit)? = null

    private fun handleChat(json: JSONObject) {
        val text = json.getString("text")
        val currentFingerprint = peerFingerprint ?: return
        
        scope.launch(Dispatchers.IO) {
            val newId = messageDao.insert(MessageEntity(
                peerFingerprint = currentFingerprint,
                text = text,
                isMe = false,
                messageType = "TEXT",
                transferStatus = "COMPLETED"
            ))
            sendAck(newId.toInt())
            onMessageReceived?.invoke(text)
        }
    }

    private fun handleAck(json: JSONObject) {
        val msgId = json.getInt("id")
        scope.launch(Dispatchers.IO) {
            messageDao.updateStatus(msgId, "DELIVERED")
        }
    }

    private fun sendAck(messageId: Int) {
        val packet = JSONObject().apply {
            put("type", "ack")
            put("id", messageId)
        }
        encryptAndSend(packet)
    }

    private fun handleImageChunk(json: JSONObject) {
        val transferId = json.getString("id")
        val index = json.getInt("index")
        val total = json.getInt("total")
        val data = Base64.decode(json.getString("data"), Base64.DEFAULT)

        val chunks = incomingImages.getOrPut(transferId) { mutableMapOf() }
        chunks[index] = data

        val currentFingerprint = peerFingerprint ?: return
        val progress = (chunks.size * 100) / total
        
        scope.launch(Dispatchers.IO) {
            val existingId = incomingMessageIds[transferId]
            val fileName = "rcv_$transferId.jpg"
            val file = File(context.filesDir, fileName)
            
            if (existingId == null) {
                val newId = messageDao.insert(MessageEntity(
                    peerFingerprint = currentFingerprint,
                    text = "[Receiving Image...]",
                    isMe = false,
                    messageType = "IMAGE",
                    filePath = file.absolutePath,
                    transferStatus = "PENDING",
                    progress = progress
                ))
                incomingMessageIds[transferId] = newId
            } else {
                messageDao.updateTransferProgress(existingId.toInt(), progress, "PENDING", file.absolutePath)
            }

            if (chunks.size == total) {
                assembleAndSaveImage(transferId, total, file)
            }
        }
    }

    private suspend fun assembleAndSaveImage(transferId: String, total: Int, file: File) {
        val chunks = incomingImages.remove(transferId) ?: return
        val out = ByteArrayOutputStream()
        for (i in 0 until total) {
            out.write(chunks[i]!!)
        }
        file.writeBytes(out.toByteArray())

        val msgId = incomingMessageIds.remove(transferId)
        if (msgId != null) {
            messageDao.updateTransferProgress(msgId.toInt(), 100, "COMPLETED", file.absolutePath)
            sendAck(msgId.toInt())
        }
        onMessageReceived?.invoke("[Image Received]")
    }
}
