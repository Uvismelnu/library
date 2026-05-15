package com.medsearch.rag.data.local

import androidx.room.Database
import androidx.room.RoomDatabase
import com.medsearch.rag.data.local.dao.BookDao
import com.medsearch.rag.data.local.dao.PageChunkDao
import com.medsearch.rag.data.local.entity.BookEntity
import com.medsearch.rag.data.local.entity.PageChunkEntity
import com.medsearch.rag.data.local.entity.PageChunkFts

@Database(
    entities = [
        BookEntity::class,
        PageChunkEntity::class,
        PageChunkFts::class
    ],
    version = 1,
    exportSchema = false
)
abstract class MedSearchDatabase : RoomDatabase() {
    abstract fun bookDao(): BookDao
    abstract fun pageChunkDao(): PageChunkDao

    companion object {
        const val DB_NAME = "medsearch.db"
    }
}
