package com.example.linkfront

import androidx.room.Entity
import androidx.room.PrimaryKey

@Entity(tableName = "messages")
data class MessageEntity(
    @PrimaryKey(autoGenerate = true) val id: Int = 0,
    val text: String,
    val isMe: Boolean,
    val timestamp: Long = System.currentTimeMillis()
)
