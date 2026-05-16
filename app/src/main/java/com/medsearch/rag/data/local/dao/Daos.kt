package com.medsearch.rag.data.local.dao

import androidx.room.Dao
import androidx.room.Insert
import androidx.room.OnConflictStrategy
import androidx.room.Query
import androidx.room.Update
import com.medsearch.rag.data.local.entity.BookEntity
import com.medsearch.rag.data.local.entity.PageChunkEntity
import kotlinx.coroutines.flow.Flow

@Dao
interface BookDao {

    @Insert(onConflict = OnConflictStrategy.REPLACE)
    suspend fun insert(book: BookEntity): Long

    @Update
    suspend fun update(book: BookEntity)

    @Query("SELECT * FROM books WHERE uri = :uri LIMIT 1")
    suspend fun findByUri(uri: String): BookEntity?

    @Query("SELECT * FROM books ORDER BY displayName ASC")
    fun observeAll(): Flow<List<BookEntity>>

    @Query("SELECT COUNT(*) FROM books")
    fun observeCount(): Flow<Int>

    @Query("SELECT COUNT(*) FROM page_chunks")
    fun observePageCount(): Flow<Int>

    @Query("DELETE FROM books")
    suspend fun deleteAll()

    @Query("DELETE FROM books WHERE uri = :uri")
    suspend fun deleteByUri(uri: String)

    @Query("SELECT * FROM books WHERE id = :id")
    suspend fun findById(id: Long): BookEntity?
}

data class SearchHit(
    val chunkId: Long,
    val bookId: Long,
    val bookTitle: String,
    val pageNumber: Int,
    val snippet: String,
    val fullText: String,
    val rank: Double
)

@Dao
interface PageChunkDao {

    @Insert(onConflict = OnConflictStrategy.REPLACE)
    suspend fun insertChunks(chunks: List<PageChunkEntity>): List<Long>

    @Query("SELECT * FROM page_chunks WHERE id = :id")
    suspend fun findById(id: Long): PageChunkEntity?

    @Query("SELECT * FROM page_chunks WHERE id IN (:ids)")
    suspend fun findByIds(ids: List<Long>): List<PageChunkEntity>

    @Query(
        """
        SELECT
            pc.id            AS chunkId,
            pc.bookId        AS bookId,
            b.displayName    AS bookTitle,
            pc.pageNumber    AS pageNumber,
            snippet(page_chunks_fts, '[[HIT]]', '[[/HIT]]', '…', -1, 32) AS snippet,
            pc.text          AS fullText,
            CAST(length(pc.text) AS REAL) / 
                (length(pc.text) - length(replace(lower(pc.text), lower(:plainTerm), '')) + 1) AS rank
        FROM page_chunks_fts
        JOIN page_chunks pc ON pc.id = page_chunks_fts.rowid
        JOIN books b ON b.id = pc.bookId
        WHERE page_chunks_fts MATCH :query
        ORDER BY rank ASC
        LIMIT :limit
        """
    )
    suspend fun search(query: String, plainTerm: String, limit: Int): List<SearchHit>
}
