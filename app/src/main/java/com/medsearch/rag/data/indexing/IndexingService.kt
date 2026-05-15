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
import javax.inject.Inject
import javax.inject.Singleton

data class IndexProgress(
    val running: Boolean = false,
    val currentBook: String? = null,
    val currentPage: Int = 0,
    val totalPages: Int = 0,
    val booksDone: Int = 0,
    val booksTotal: Int = 0,
    val skippedBooks: Int = 0,
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

    companion object {
        const val MAX_FILE_SIZE_MB = 1024L
        const val MAX_FILE_SIZE_BYTES = MAX_FILE_SIZE_MB * 1024L * 1024L
        private const val TARGET_CHUNK_WORDS = 400
        private const val CHUNK_OVERLAP_WORDS = 80
        private const val MIN_CHUNK_WORDS = 20
        private const val DB_BATCH_FLUSH_SIZE = 150
    }

    suspend fun indexFolder(context: Context, folderUri: Uri, allowOcr: Boolean) {
        val folder = DocumentFile.fromTreeUri(context, folderUri)
            ?: run {
                _progress.value = IndexProgress(errorMessage = "No se pudo acceder a la carpeta")
                return
            }

        val pdfFiles = folder.listFiles().filter {
            it.isFile && (it.name?.endsWith(".pdf", ignoreCase = true) == true)
        }

        val currentNames = pdfFiles.mapNotNull { it.name }.toSet()
        val allIndexed = bookDao.observeAll().first()
        allIndexed.forEach { book ->
            if (book.displayName !in currentNames) {
                bookDao.deleteByUri(book.uri)
            }
        }

        if (pdfFiles.isEmpty()) {
            _progress.value = IndexProgress(
                errorMessage = "La carpeta está vacía",
                finishedAt = System.currentTimeMillis()
            )
            return
        }

        _progress.value = IndexProgress(running = true, booksTotal = pdfFiles.size)

        var lastError: String? = null
        var skippedCount = 0

        pdfFiles.forEachIndexed { idx, df ->
            val name = df.name ?: "documento_$idx.pdf"
            val uri = df.uri
            val sizeBytes = df.length()

            try {
                if (sizeBytes > MAX_FILE_SIZE_BYTES) {
                    val sizeMb = sizeBytes / (1024 * 1024)
                    skippedCount++
                    lastError = "Saltado '$name': ${sizeMb}MB excede el límite de ${MAX_FILE_SIZE_MB}MB"
                    _progress.value = _progress.value.copy(
                        booksDone = idx + 1,
                        skippedBooks = skippedCount,
                        errorMessage = lastError
                    )
                    return@forEachIndexed
                }

                val existing = bookDao.findByUri(uri.toString())
                if (existing != null &&
                    existing.sizeBytes == sizeBytes &&
                    existing.indexedPages > 0
                ) {
                    _progress.value = _progress.value.copy(booksDone = idx + 1)
                    return@forEachIndexed
                }

                _progress.value = _progress.value.copy(
                    currentBook = name,
                    currentPage = 0,
                    totalPages = 0,
                    errorMessage = null
                )

                indexSingleBook(context, df, uri, name, sizeBytes, allowOcr)

                _progress.value = _progress.value.copy(booksDone = idx + 1)

                System.gc()
                delay(300)

            } catch (oom: OutOfMemoryError) {
                lastError = "Memoria insuficiente para '$name'. Intenta partir el PDF."
                _progress.value = _progress.value.copy(
                    errorMessage = lastError,
                    booksDone = idx + 1
                )
                System.gc()
                delay(2000)
            } catch (t: Throwable) {
                lastError = "Error en '$name': ${t.localizedMessage ?: t.javaClass.simpleName}"
                _progress.value = _progress.value.copy(
                    errorMessage = lastError,
                    booksDone = idx + 1
                )
                delay(800)
            }
        }

        _progress.value = _progress.value.copy(
            running = false,
            errorMessage = lastError,
            finishedAt = System.currentTimeMillis()
        )
    }

    private suspend fun indexSingleBook(
        context: Context,
        df: DocumentFile,
        uri: Uri,
        name: String,
        sizeBytes: Long,
        allowOcr: Boolean
    ) {
        var bookId: Long = -1
        var finalTotalPages = 0
        var finalIsOcr = false
        val buffer = mutableListOf<PageChunkEntity>()
        var globalChunkIndex = 0

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
                        sizeBytes = sizeBytes,
                        lastIndexedAt = System.currentTimeMillis(),
                        isOcr = isOcr
                    )
                )
                _progress.value = _progress.value.copy(totalPages = total)
            }

            if (text.isNotBlank()) {
                val chunks = chunkBySentences(text, TARGET_CHUNK_WORDS, CHUNK_OVERLAP_WORDS)
                chunks.forEach { chunkText ->
                    buffer += PageChunkEntity(
                        bookId = bookId,
                        pageNumber = page,
                        chunkIndex = globalChunkIndex++,
                        text = chunkText
                    )
                }
            }

            if (buffer.size >= DB_BATCH_FLUSH_SIZE) {
                pageChunkDao.insertChunks(buffer.toList())
                buffer.clear()
            }

            _progress.value = _progress.value.copy(currentPage = page)
        }

        if (buffer.isNotEmpty()) {
            pageChunkDao.insertChunks(buffer.toList())
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
                    sizeBytes = sizeBytes,
                    lastIndexedAt = System.currentTimeMillis(),
                    isOcr = finalIsOcr
                )
            )
        }
    }

    private fun chunkBySentences(
        text: String,
        targetWords: Int,
        overlapWords: Int
    ): List<String> {
        val sentencePattern = Regex("(?<=[.!?])\\s+(?=[A-ZÁÉÍÓÚÑ¿¡])")
        val sentences = sentencePattern.split(text)
            .map { it.trim() }
            .filter { it.isNotEmpty() }

        if (sentences.isEmpty()) return emptyList()

        val totalWords = text.split(Regex("\\s+")).size
        if (totalWords <= targetWords) {
            return if (totalWords >= MIN_CHUNK_WORDS) listOf(text.trim()) else emptyList()
        }

        val chunks = mutableListOf<String>()
        var currentSentences = mutableListOf<String>()
        var currentWordCount = 0

        for (sentence in sentences) {
            val sentenceWords = sentence.split(Regex("\\s+")).size

            if (currentWordCount + sentenceWords > targetWords && currentSentences.isNotEmpty()) {
                val chunkText = currentSentences.joinToString(" ")
                if (currentWordCount >= MIN_CHUNK_WORDS) {
                    chunks.add(chunkText)
                }

                val overlap = mutableListOf<String>()
                var overlapCount = 0
                for (s in currentSentences.reversed()) {
                    val w = s.split(Regex("\\s+")).size
                    if (overlapCount + w > overlapWords && overlap.isNotEmpty()) break
                    overlap.add(0, s)
                    overlapCount += w
                }

                currentSentences = overlap
                currentWordCount = overlapCount
            }

            currentSentences.add(sentence)
            currentWordCount += sentenceWords
        }

        if (currentSentences.isNotEmpty() && currentWordCount >= MIN_CHUNK_WORDS) {
            chunks.add(currentSentences.joinToString(" "))
        }

        return chunks
    }
}
