package com.example.linkfront

import androidx.room.Entity
import androidx.room.PrimaryKey

@Entity(tableName = "peers")
data class PeerEntity(
    @PrimaryKey val fingerprint: String, // Fingerprint is a unique identifier
    val username: String,
    val identityKey: ByteArray, // The long-term public key
    val lastSeen: Long = System.currentTimeMillis()
)
