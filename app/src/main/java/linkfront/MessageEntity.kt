package com.linkfront

import androidx.room.Entity
import androidx.room.PrimaryKey

// Database model for a chat message or file transfer
@Entity(tableName = "messages")
data class MessageEntity(
    @PrimaryKey(autoGenerate = true) val id: Int = 0,
    val peerFingerprint: String, // Fingerprint of the sender or receiver
    val text: String,
    val isMe: Boolean, // True if sent by this device
    val timestamp: Long = System.currentTimeMillis(),
    val messageType: String = "TEXT", // "TEXT" or "IMAGE"
    val filePath: String? = null,     // Path to local image file
    val transferStatus: String = "COMPLETED", // "PENDING", "COMPLETED", "DELIVERED", "FAILED"
    val progress: Int = 100
)
