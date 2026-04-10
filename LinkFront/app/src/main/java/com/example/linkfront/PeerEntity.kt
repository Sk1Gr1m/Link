package com.example.linkfront

import androidx.room.Entity
import androidx.room.PrimaryKey

@Entity(tableName = "peers")
data class PeerEntity(
    @PrimaryKey val fingerprint: String, // Fingerprint is a unique identifier
    val username: String,
    val identityKey: ByteArray, // The long-term public key
    val lastSeen: Long = System.currentTimeMillis()
) {
    override fun equals(other: Any?): Boolean {
        if (this === other) return true
        if (javaClass != other?.javaClass) return false

        other as PeerEntity

        if (fingerprint != other.fingerprint) return false
        if (username != other.username) return false
        if (!identityKey.contentEquals(other.identityKey)) return false
        if (lastSeen != other.lastSeen) return false

        return true
    }

    override fun hashCode(): Int {
        var result = fingerprint.hashCode()
        result = 31 * result + username.hashCode()
        result = 31 * result + identityKey.contentHashCode()
        result = 31 * result + lastSeen.hashCode()
        return result
    }
}
