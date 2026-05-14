package com.linkfront

import androidx.room.Dao
import androidx.room.Insert
import androidx.room.OnConflictStrategy
import androidx.room.Query
import kotlinx.coroutines.flow.Flow

// Data access object for peer information in the Room database
@Dao
interface PeerDao {
    @Query("SELECT * FROM peers ORDER BY lastSeen DESC")
    fun getAllPeers(): Flow<List<PeerEntity>>

    @Query("SELECT * FROM peers")
    suspend fun getAllPeersOnce(): List<PeerEntity>

    @Insert(onConflict = OnConflictStrategy.REPLACE)
    suspend fun insert(peer: PeerEntity)

    @androidx.room.Delete
    suspend fun delete(peer: PeerEntity)

    @Query("SELECT * FROM peers WHERE fingerprint = :fingerprint")
    fun getPeerFlowByFingerprint(fingerprint: String): kotlinx.coroutines.flow.Flow<PeerEntity?>

    @Query("SELECT * FROM peers WHERE fingerprint = :fingerprint")
    suspend fun getPeerByFingerprint(fingerprint: String): PeerEntity?

    @Query("UPDATE peers SET sharedSecret = :secret, lastSentCounter = :sent, lastReceivedCounter = :received WHERE fingerprint = :fingerprint")
    suspend fun updateSession(fingerprint: String, secret: ByteArray, sent: Int, received: Int)

    @Query("UPDATE peers SET lastKnownIp = :ip, lastKnownPort = :port, lastSeen = :lastSeen WHERE fingerprint = :fingerprint")
    suspend fun updateAddress(fingerprint: String, ip: String, port: Int, lastSeen: Long = System.currentTimeMillis())

    @Query("DELETE FROM peers")
    suspend fun deleteAll()
}
