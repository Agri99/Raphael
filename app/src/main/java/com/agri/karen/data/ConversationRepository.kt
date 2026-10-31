package com.agri.karen.data

import com.agri.karen.data.local.ConversationDao
import com.agri.karen.data.local.ConversationEntity
import com.agri.karen.data.local.MessageDao
import com.agri.karen.data.local.MessageEntity
import kotlinx.coroutines.flow.Flow
import java.util.UUID

class ConversationRepository(
    private val conversationDao: ConversationDao,
    private val messageDao: MessageDao
) {
    fun getAllConversations(): Flow<List<ConversationEntity>> =
        conversationDao.getAllConversations()

    fun getMessages(conversationId: String): Flow<List<MessageEntity>> =
        messageDao.getMessagesForConversation(conversationId)

    suspend fun createConversation(title: String = "New Conversation"): ConversationEntity {
        val now = System.currentTimeMillis()
        val conv = ConversationEntity(
            id = UUID.randomUUID().toString(),
            title = title,
            createdAtEpochMs = now,
            updatedAtEpochMs = now,
            archived = false
        )
        conversationDao.insertConversation(conv)
        return conv
    }

    suspend fun saveMessage(message: MessageEntity) {
        messageDao.insertMessage(message)
        // Update conversation timestamp
        val conv = conversationDao.getConversationById(message.conversationId)
        if (conv != null) {
            conversationDao.updateConversation(conv.copy(updatedAtEpochMs = message.createdAtEpochMs))
        }
    }

    suspend fun updateMessage(message: MessageEntity) {
        messageDao.updateMessage(message)
    }

    suspend fun getRecentMessages(conversationId: String, limit: Int = 10): List<MessageEntity> {
        return messageDao.getRecentMessages(conversationId, limit)
    }

    suspend fun deleteConversation(id: String) {
        conversationDao.deleteConversationById(id)
    }

    suspend fun deleteMessage(id: String) {
        messageDao.deleteMessageById(id)
    }
}
