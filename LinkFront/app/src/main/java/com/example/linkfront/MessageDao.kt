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
    suspend fun insert(message: MessageEntity): Long

    @Query("DELETE FROM messages WHERE peerFingerprint = :fingerprint")
    suspend fun deleteMessagesForPeer(fingerprint: String)

    @Query("DELETE FROM messages WHERE id = :id")
    suspend fun deleteMessageById(id: Int)

    @Query("DELETE FROM messages")
    suspend fun deleteAll()

    @Query("UPDATE messages SET progress = :progress, transferStatus = :status, filePath = :filePath WHERE id = :id")
    suspend fun updateTransferProgress(id: Int, progress: Int, status: String, filePath: String)

    @Query("SELECT MAX(id) FROM messages")
    suspend fun getLastInsertedId(): Int
}
