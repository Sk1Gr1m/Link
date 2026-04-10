package com.example.linkfront

import androidx.room.Entity
import androidx.room.PrimaryKey

@Entity(tableName = "messages")
data class MessageEntity(
    @PrimaryKey(autoGenerate = true) val id: Int = 0,
    val peerFingerprint: String,
    val text: String,
    val isMe: Boolean,
    val timestamp: Long = System.currentTimeMillis(),
    val messageType: String = "TEXT", // "TEXT" or "IMAGE"
    val filePath: String? = null,     // Path to local image file
    val transferStatus: String = "COMPLETED", // "PENDING", "COMPLETED", "FAILED"
    val progress: Int = 100
)
