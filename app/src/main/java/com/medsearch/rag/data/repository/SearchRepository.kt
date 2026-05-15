package com.medsearch.rag.data.repository

import com.medsearch.rag.data.llm.LlmEngine
import com.medsearch.rag.data.llm.PromptBuilder
import com.medsearch.rag.data.local.FtsQueryBuilder
import com.medsearch.rag.data.local.dao.BookDao
import com.medsearch.rag.data.local.dao.PageChunkDao
import com.medsearch.rag.data.local.dao.SearchHit
import com.medsearch.rag.data.local.entity.BookEntity
import kotlinx.coroutines.flow.Flow
import javax.inject.Inject
import javax.inject.Singleton

@Singleton
class SearchRepository @Inject constructor(
    private val pageChunkDao: PageChunkDao,
    private val bookDao: BookDao,
    private val llmEngine: LlmEngine
) {

    fun observeBooks(): Flow<List<BookEntity>> = bookDao.observeAll()
    fun observeBookCount(): Flow<Int> = bookDao.observeCount()
    fun observePageCount(): Flow<Int> = bookDao.observePageCount()

    suspend fun search(rawTerm: String, limit: Int = 60): List<SearchHit> {
        val fts = FtsQueryBuilder.build(rawTerm)
        if (fts.isBlank()) return emptyList()
        val plain = FtsQueryBuilder.plainForm(rawTerm)
        return pageChunkDao.search(fts, plain, limit)
    }

    suspend fun clearAll() = bookDao.deleteAll()

    /**
     * Pipeline RAG completo:
     *  1. Recupera top-K hits con FTS
     *  2. Construye prompt con esos pasajes
     *  3. Invoca al LLM local
     */
    suspend fun ragSummarize(question: String, topK: Int = 6): Result<RagResult> {
        val hits = search(question, limit = topK)
        if (hits.isEmpty()) {
            return Result.failure(IllegalStateException("Sin pasajes relevantes. Indexa más libros o reformula."))
        }
        val prompt = PromptBuilder.build(question, hits)
        return llmEngine.generate(prompt).map { response ->
            RagResult(answer = response.trim(), usedHits = hits)
        }
    }
}

data class RagResult(
    val answer: String,
    val usedHits: List<SearchHit>
)
