package com.example.linkfront

import androidx.room.Dao
import androidx.room.Insert
import androidx.room.Query
import kotlinx.coroutines.flow.Flow

@Dao
interface MessageDao {
    @Query("SELECT * FROM messages WHERE peerFingerprint = :fingerprint ORDER BY timestamp ASC")
    fun getMessagesForPeer(fingerprint: String): Flow<List<MessageEntity>>

    @Insert
    suspend fun insert(message: MessageEntity)

    @Query("DELETE FROM messages WHERE peerFingerprint = :fingerprint")
    suspend fun deleteMessagesForPeer(fingerprint: String)

    @Query("DELETE FROM messages WHERE id = :id")
    suspend fun deleteMessageById(id: Int)

    @Query("DELETE FROM messages")
    suspend fun deleteAll()
}
