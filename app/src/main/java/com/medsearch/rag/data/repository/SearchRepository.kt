package com.medsearch.rag.data.repository

import com.medsearch.rag.data.llm.ExtractiveSummarizer
import com.medsearch.rag.data.llm.LlmEngine
import com.medsearch.rag.data.llm.PromptBuilder
import com.medsearch.rag.data.local.FtsQueryBuilder
import com.medsearch.rag.data.local.dao.BookDao
import com.medsearch.rag.data.local.dao.PageChunkDao
import com.medsearch.rag.data.local.dao.SearchHit
import com.medsearch.rag.data.local.entity.BookEntity
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.flow
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

    suspend fun smartSummarize(question: String, topK: Int = 6): SummaryResult {
        val hits = search(question, limit = topK)
        if (hits.isEmpty()) {
            return SummaryResult.NoResults(
                message = "Sin pasajes relevantes. Indexa más libros o reformula la consulta."
            )
        }
        return if (llmEngine.isLoaded) {
            generateWithLlm(question, hits)
        } else {
            generateExtractive(question, hits)
        }
    }

    fun smartSummarizeStream(question: String, topK: Int = 6): Flow<SummaryResult> = flow {
        val hits = search(question, limit = topK)
        if (hits.isEmpty()) {
            emit(SummaryResult.NoResults(
                message = "Sin pasajes relevantes. Indexa más libros o reformula la consulta."
            ))
            return@flow
        }

        if (llmEngine.isLoaded) {
            val prompt = PromptBuilder.build(question, hits)
            try {
                llmEngine.generateStream(prompt).collect { partialText ->
                    emit(SummaryResult.LlmGenerated(
                        answer = partialText,
                        usedHits = hits,
                        isStreaming = true
                    ))
                }
            } catch (t: Throwable) {
                val extractive = ExtractiveSummarizer.summarize(question, hits)
                emit(SummaryResult.Extractive(
                    answer = extractive.summary,
                    usedHits = extractive.citedHits,
                    fallbackReason = "Error en LLM: ${t.localizedMessage}. " +
                            "Se muestra resumen extractive como respaldo."
                ))
            }
        } else {
            val extractive = ExtractiveSummarizer.summarize(question, hits)
            emit(SummaryResult.Extractive(
                answer = extractive.summary,
                usedHits = extractive.citedHits,
                fallbackReason = null
            ))
        }
    }

    private suspend fun generateWithLlm(
        question: String,
        hits: List<SearchHit>
    ): SummaryResult {
        val prompt = PromptBuilder.build(question, hits)
        return llmEngine.generate(prompt).fold(
            onSuccess = { response ->
                SummaryResult.LlmGenerated(
                    answer = response.trim(),
                    usedHits = hits,
                    isStreaming = false
                )
            },
            onFailure = { error ->
                val extractive = ExtractiveSummarizer.summarize(question, hits)
                SummaryResult.Extractive(
                    answer = extractive.summary,
                    usedHits = extractive.citedHits,
                    fallbackReason = "LLM falló: ${error.localizedMessage}. " +
                            "Se muestra resumen extractive."
                )
            }
        )
    }

    private fun generateExtractive(
        question: String,
        hits: List<SearchHit>
    ): SummaryResult {
        val extractive = ExtractiveSummarizer.summarize(question, hits)
        return if (extractive.hadResults) {
            SummaryResult.Extractive(
                answer = extractive.summary,
                usedHits = extractive.citedHits,
                fallbackReason = null
            )
        } else {
            SummaryResult.NoResults(message = extractive.summary)
        }
    }

    @Deprecated(
        "Usa smartSummarize() para fallback automático cuando el LLM no está cargado",
        ReplaceWith("smartSummarize(question, topK)")
    )
    suspend fun ragSummarize(question: String, topK: Int = 6): Result<RagResult> {
        val hits = search(question, limit = topK)
        if (hits.isEmpty()) {
            return Result.failure(
                IllegalStateException("Sin pasajes relevantes. Indexa más libros o reformula.")
            )
        }
        val prompt = PromptBuilder.build(question, hits)
        return llmEngine.generate(prompt).map { response ->
            RagResult(answer = response.trim(), usedHits = hits)
        }
    }
}

sealed class SummaryResult {
    data class LlmGenerated(
        val answer: String,
        val usedHits: List<SearchHit>,
        val isStreaming: Boolean
    ) : SummaryResult()

    data class Extractive(
        val answer: String,
        val usedHits: List<SearchHit>,
        val fallbackReason: String?
    ) : SummaryResult()

    data class NoResults(val message: String) : SummaryResult()
}

data class RagResult(
    val answer: String,
    val usedHits: List<SearchHit>
)
