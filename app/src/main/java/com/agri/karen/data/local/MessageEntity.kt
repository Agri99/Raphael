package com.agri.karen.data.local

import androidx.room.Entity
import androidx.room.PrimaryKey
import androidx.room.ForeignKey
import androidx.room.Index

@Entity(
    tableName = "messages",
    indices = [Index("conversationId"), Index("createdAtEpochMs")],
    foreignKeys = [ForeignKey(
        entity = ConversationEntity::class,
        parentColumns = ["id"],
        childColumns = ["conversationId"],
        onDelete = ForeignKey.CASCADE
    )]
)

data class MessageEntity(
    @PrimaryKey val id: String,
    val conversationId: String,
    val role: String, // "user" or "assistant"
    val content: String,
    val createdAtEpochMs: Long,
    val status: String, // "streaming", "complete", "interrupted", "failed"
    val modelLabel: String? = null
)
