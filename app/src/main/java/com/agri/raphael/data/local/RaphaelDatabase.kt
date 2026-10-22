package com.agri.raphael.data.local

import android.content.Context
import androidx.room.Database
import androidx.room.Room
import androidx.room.RoomDatabase

@Database(
    entities = [ConversationEntity::class, MessageEntity::class],
    version = 1,
    exportSchema = false
)
abstract class RaphaelDatabase : RoomDatabase() {
    abstract fun conversationDao(): ConversationDao
    abstract fun messageDao(): MessageDao

    companion object {
        @Volatile
        private var INSTANCE: RaphaelDatabase? = null

        fun getDatabase(context: Context): RaphaelDatabase {
            return INSTANCE ?: synchronized(this) {
                val instance = Room.databaseBuilder(
                    context.applicationContext,
                    RaphaelDatabase::class.java,
                    "raphael_chat.db"
                ).build()
                INSTANCE = instance
                instance
            }
        }
    }
}

