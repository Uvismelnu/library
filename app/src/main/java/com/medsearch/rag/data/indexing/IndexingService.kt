package com.medsearch.rag.data.indexing

import android.content.Context
import android.net.Uri
import androidx.documentfile.provider.DocumentFile
import com.medsearch.rag.data.local.dao.BookDao
import com.medsearch.rag.data.local.dao.PageChunkDao
import com.medsearch.rag.data.local.entity.BookEntity
import com.medsearch.rag.data.local.entity.PageChunkEntity
import com.medsearch.rag.data.pdf.PdfTextExtractor
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.yield
import javax.inject.Inject
import javax.inject.Singleton

data class IndexProgress(
    val running: Boolean = false,
    val currentBook: String? = null,
    val currentPage: Int = 0,
    val totalPages: Int = 0,
    val booksDone: Int = 0,
    val booksTotal: Int = 0,
    val errorMessage: String? = null,
    val finishedAt: Long? = null
)

@Singleton
class IndexingService @Inject constructor(
    private val bookDao: BookDao,
    private val pageChunkDao: PageChunkDao,
    private val pdfExtractor: PdfTextExtractor
) {

    private val _progress = MutableStateFlow(IndexProgress())
    val progress: StateFlow<IndexProgress> = _progress.asStateFlow()

    private val targetChunkSize = 1000
    private val chunkOverlap = 150

    suspend fun indexFolder(context: Context, folderUri: Uri, allowOcr: Boolean) {
        val folder = DocumentFile.fromTreeUri(context, folderUri)
            ?: run {
                _progress.value = IndexProgress(errorMessage = "No se pudo acceder a la carpeta")
                return
            }

        val pdfFiles = folder.listFiles().filter {
            it.isFile && (it.name?.endsWith(".pdf", ignoreCase = true) == true)
        }

        // 1. Sincronización: Eliminar de DB lo que ya no está en disco
        val currentNames = pdfFiles.map { it.name }.toSet()
        val allIndexed = bookDao.observeAll().first()
        allIndexed.forEach { book ->
            if (book.displayName !in currentNames) {
                bookDao.deleteByUri(book.uri)
            }
        }

        if (pdfFiles.isEmpty()) {
            _progress.value = IndexProgress(errorMessage = "La carpeta está vacía", finishedAt = System.currentTimeMillis())
            return
        }

        _progress.value = IndexProgress(running = true, booksTotal = pdfFiles.size)

        var hasGlobalError = false
        var lastError: String? = null

        pdfFiles.forEachIndexed { idx, df ->
            val name = df.name ?: "documento_$idx.pdf"
            val uri = df.uri

            try {
                val existing = bookDao.findByUri(uri.toString())
                if (existing != null && existing.sizeBytes == df.length() && existing.indexedPages > 0) {
                    _progress.value = _progress.value.copy(booksDone = idx + 1)
                    return@forEachIndexed
                }

                _progress.value = _progress.value.copy(
                    currentBook = name,
                    currentPage = 0,
                    totalPages = 0,
                    errorMessage = null // Limpiamos error previo para mostrar avance
                )

                var bookId: Long = -1
                var finalTotalPages = 0
                var finalIsOcr = false
                val buffer = mutableListOf<PageChunkEntity>()

                pdfExtractor.iteratePages(context, uri, allowOcr) { page, total, text, isOcr ->
                    if (bookId == -1L) {
                        finalTotalPages = total
                        finalIsOcr = isOcr
                        bookId = bookDao.insert(
                            BookEntity(
                                uri = uri.toString(),
                                displayName = name,
                                totalPages = total,
                                indexedPages = 0,
                                sizeBytes = df.length(),
                                lastIndexedAt = System.currentTimeMillis(),
                                isOcr = isOcr
                            )
                        )
                        _progress.value = _progress.value.copy(totalPages = total)
                    }

                    if (text.isNotBlank()) {
                        chunkify(text).forEachIndexed { ci, chunk ->
                            buffer += PageChunkEntity(
                                bookId = bookId,
                                pageNumber = page,
                                chunkIndex = ci,
                                text = chunk
                            )
                        }
                    }

                    if (buffer.size >= 30) {
                        pageChunkDao.insertChunks(buffer)
                        buffer.clear()
                    }
                    _progress.value = _progress.value.copy(currentPage = page)
                }

                if (buffer.isNotEmpty()) {
                    pageChunkDao.insertChunks(buffer)
                    buffer.clear()
                }

                if (bookId != -1L) {
                    bookDao.update(
                        BookEntity(
                            id = bookId,
                            uri = uri.toString(),
                            displayName = name,
                            totalPages = finalTotalPages,
                            indexedPages = finalTotalPages,
                            sizeBytes = df.length(),
                            lastIndexedAt = System.currentTimeMillis(),
                            isOcr = finalIsOcr
                        )
                    )
                }
                _progress.value = _progress.value.copy(booksDone = idx + 1)
                System.gc()
                delay(200)

            } catch (t: Throwable) {
                hasGlobalError = true
                lastError = "Error en $name: ${t.localizedMessage}"
                _progress.value = _progress.value.copy(errorMessage = lastError, booksDone = idx + 1)
                delay(1000)
            }
        }

        _progress.value = _progress.value.copy(
            running = false,
            errorMessage = lastError, // Mantenemos el último error si hubo alguno
            finishedAt = System.currentTimeMillis()
        )
    }

    private fun chunkify(pageText: String): List<String> {
        if (pageText.length <= targetChunkSize) return listOf(pageText)
        val out = mutableListOf<String>()
        var start = 0
        while (start < pageText.length) {
            val end = (start + targetChunkSize).coerceAtMost(pageText.length)
            val cut = pageText.substring(start, end).let { txt ->
                val lastDot = txt.lastIndexOfAny(charArrayOf('.', '!', '?', '\n'))
                if (lastDot > targetChunkSize * 0.5) start + lastDot + 1 else end
            }
            out += pageText.substring(start, cut).trim()
            if (cut >= pageText.length) break
            start = (cut - chunkOverlap).coerceAtLeast(start + 1)
        }
        return out.filter { it.isNotBlank() }
    }
}
