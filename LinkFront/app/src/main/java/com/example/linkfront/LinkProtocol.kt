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
import java.nio.charset.StandardCharsets

class LinkProtocol(
    private val context: Context,
    private val messageDao: MessageDao,
    private val peerDao: PeerDao,
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

    fun sendText(targetFingerprint: String, message: String) {
        Log.i(tag, "Sending text message to $targetFingerprint: ${message.take(20)}...")
        scope.launch(Dispatchers.IO) {
            val isSelf = targetFingerprint == "SELF"
            val msgId = messageDao.insert(MessageEntity(
                peerFingerprint = targetFingerprint,
                text = message,
                isMe = true,
                messageType = "TEXT",
                transferStatus = if (isSelf) "COMPLETED" else "PENDING"
            )).toInt()

            if (isSelf) return@launch

            try {
                if (targetFingerprint == peerFingerprint) {
                    val packet = JSONObject().apply {
                        put("type", "chat")
                        put("id", msgId)
                        put("text", message)
                    }
                    if (encryptAndSend(packet)) {
                        Log.d(tag, "Text message $msgId sent successfully")
                        messageDao.updateTransferProgress(msgId, 100, "COMPLETED", "")
                        return@launch
                    }
                }
                
                Log.e(tag, "Failed to send text message $msgId (not connected to $targetFingerprint)")
                messageDao.updateTransferProgress(msgId, 0, "FAILED", "")
            } catch (e: Exception) {
                Log.e(tag, "Error sending text message $msgId: ${e.message}")
                messageDao.updateTransferProgress(msgId, 0, "FAILED", "")
            }
        }
    }

    fun sendImage(targetFingerprint: String, imageBytes: ByteArray) {
        Log.i(tag, "Starting image transfer to $targetFingerprint, size: ${imageBytes.size} bytes")
        scope.launch(Dispatchers.IO) {
            try {
                val isSelf = targetFingerprint == "SELF"
                val fileName = if (isSelf) "note_${System.currentTimeMillis()}.jpg" else "img_${System.currentTimeMillis()}.jpg"
                val file = File(context.filesDir, fileName).apply { writeBytes(imageBytes) }
                
                val chunkSize = 32 * 1024
                val totalChunks = (imageBytes.size + chunkSize - 1) / chunkSize
                val transferId = System.currentTimeMillis().toString()

                val messageId = messageDao.insert(MessageEntity(
                    peerFingerprint = targetFingerprint,
                    text = "[Image]",
                    isMe = true,
                    messageType = "IMAGE",
                    filePath = file.absolutePath,
                    transferStatus = if (isSelf) "COMPLETED" else "PENDING",
                    progress = if (isSelf) 100 else 0
                )).toInt()

                if (isSelf) return@launch

                if (targetFingerprint != peerFingerprint) {
                    messageDao.updateTransferProgress(messageId, 0, "FAILED", file.absolutePath)
                    return@launch
                }

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
                        Log.d(tag, "Sent image chunk ${i+1}/$totalChunks for transfer $transferId")
                        if (progress == 100) {
                            Log.i(tag, "Image transfer $transferId completed")
                            messageDao.updateStatus(messageId, "COMPLETED")
                        } else {
                            messageDao.updateTransferProgress(messageId, progress, "PENDING", file.absolutePath)
                        }
                    } else {
                        Log.e(tag, "Failed to send image chunk $i for transfer $transferId")
                    }
                    if (i % 5 == 0) delay(10)
                }
            } catch (e: Exception) {
                Log.e(tag, "Failed to send image: ${e.message}")
            }
        }
    }

    private fun encryptAndSend(json: JSONObject): Boolean {
        val s = session
        val dc = dataChannel
        val fingerprint = peerFingerprint
        
        if (s == null || dc == null) {
            Log.w(tag, "Cannot send packet: No session or data channel")
            return false
        }
        
        return try {
            val state = dc.state()
            if (state != DataChannel.State.OPEN) {
                Log.w(tag, "Cannot send packet: Data channel state is $state")
                return false
            }
            val encrypted = s.callAttr("encrypt", json.toString()).toJava(ByteArray::class.java)
            dc.send(DataChannel.Buffer(ByteBuffer.wrap(encrypted), true))
            
            // Persist the new counter
            if (fingerprint != null) {
                saveSessionState(fingerprint, s)
            }
            
            true
        } catch (e: IllegalStateException) {
            Log.e(tag, "DataChannel disposed or invalid state: ${e.message}")
            false
        } catch (e: Exception) {
            Log.e(tag, "Encryption or transmission error: ${e.message}")
            false
        }
    }

    fun onReceive(buffer: DataChannel.Buffer) {
        val data = ByteArray(buffer.data.remaining())
        buffer.data.get(data)
        
        // 1. Try to handle unencrypted handshake
        if (session == null) {
            try {
                val text = String(data, StandardCharsets.UTF_8)
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
            val fingerprint = peerFingerprint
            
            // Persist the new last_seen counter
            if (fingerprint != null) {
                saveSessionState(fingerprint, s)
            }

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

    private fun saveSessionState(fingerprint: String, s: PyObject) {
        scope.launch(Dispatchers.IO) {
            try {
                val secret = s["shared_key"]?.toJava(ByteArray::class.java) ?: return@launch
                val sent = s["counter"]?.toInt() ?: 0
                val received = s["last_seen"]?.toInt() ?: -1
                peerDao.updateSession(fingerprint, secret, sent, received)
            } catch (e: Exception) {
                Log.e(tag, "Failed to save session state: ${e.message}")
            }
        }
    }

    var onHandshakeReceived: ((JSONObject) -> Unit)? = null

    private fun handleChat(json: JSONObject) {
        val text = json.getString("text")
        val currentFingerprint = peerFingerprint ?: return
        Log.i(tag, "Received chat from $currentFingerprint: ${text.take(20)}...")
        
        scope.launch(Dispatchers.IO) {
            val newId = messageDao.insert(MessageEntity(
                peerFingerprint = currentFingerprint,
                text = text,
                isMe = false,
                messageType = "TEXT",
                transferStatus = "COMPLETED"
            ))
            Log.d(tag, "Inserted received message $newId, sending ACK")
            sendAck(newId.toInt())
            onMessageReceived?.invoke(text)
        }
    }

    private fun handleAck(json: JSONObject) {
        val msgId = json.getInt("id")
        Log.d(tag, "Received ACK for message $msgId")
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
