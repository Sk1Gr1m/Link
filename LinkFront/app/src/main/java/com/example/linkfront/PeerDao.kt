package com.example.linkfront

import androidx.room.Dao
import androidx.room.Insert
import androidx.room.OnConflictStrategy
import androidx.room.Query
import kotlinx.coroutines.flow.Flow

@Dao
interface PeerDao {
    @Query("SELECT * FROM peers ORDER BY lastSeen DESC")
    fun getAllPeers(): Flow<List<PeerEntity>>

    @Insert(onConflict = OnConflictStrategy.REPLACE)
    suspend fun insert(peer: PeerEntity)

    @Query("SELECT * FROM peers WHERE fingerprint = :fingerprint")
    suspend fun getPeerByFingerprint(fingerprint: String): PeerEntity?

    @Query("DELETE FROM peers")
    suspend fun deleteAll()
}
