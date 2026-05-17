package com.medsearch.rag.data.repository

import com.medsearch.rag.data.local.FtsQueryBuilder
import com.medsearch.rag.data.local.dao.BookDao
import com.medsearch.rag.data.local.dao.PageChunkDao
import com.medsearch.rag.data.local.dao.SearchHit
import com.medsearch.rag.data.local.entity.BookEntity
import kotlinx.coroutines.flow.Flow
import javax.inject.Inject
import javax.inject.Singleton

/**
 * Repositorio mínimo: solo búsqueda FTS y conteos.
 * Sin LLM, sin extractive, sin resúmenes.
 */
@Singleton
class SearchRepository @Inject constructor(
    private val pageChunkDao: PageChunkDao,
    private val bookDao: BookDao
) {

    fun observeBooks(): Flow<List<BookEntity>> = bookDao.observeAll()
    fun observeBookCount(): Flow<Int> = bookDao.observeCount()
    fun observePageCount(): Flow<Int> = bookDao.observePageCount()

    suspend fun search(rawTerm: String, limit: Int = 100): List<SearchHit> {
        val fts = FtsQueryBuilder.build(rawTerm)
        if (fts.isBlank()) return emptyList()
        val plain = FtsQueryBuilder.plainForm(rawTerm)
        return pageChunkDao.search(fts, plain, limit)
    }

    suspend fun bookUriById(bookId: Long): String? =
        bookDao.findById(bookId)?.uri

    suspend fun clearAll() = bookDao.deleteAll()
}
