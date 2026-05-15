package com.medsearch.rag.data.pdf

import android.content.Context
import android.graphics.Bitmap
import android.graphics.Color
import android.graphics.pdf.PdfRenderer
import android.net.Uri
import android.os.ParcelFileDescriptor
import com.google.mlkit.vision.common.InputImage
import com.google.mlkit.vision.text.TextRecognition
import com.google.mlkit.vision.text.latin.TextRecognizerOptions
import com.tom_roush.pdfbox.io.MemoryUsageSetting
import com.tom_roush.pdfbox.pdmodel.PDDocument
import com.tom_roush.pdfbox.text.PDFTextStripper
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.delay
import kotlinx.coroutines.tasks.await
import kotlinx.coroutines.withContext
import kotlinx.coroutines.yield
import java.io.File
import java.io.FileOutputStream
import kotlin.math.min

interface PdfTextExtractor {
    suspend fun iteratePages(
        context: Context,
        uri: Uri,
        allowOcr: Boolean,
        onPage: suspend (page: Int, total: Int, text: String, isOcr: Boolean) -> Unit
    )
}

class PdfTextExtractorImpl : PdfTextExtractor {

    companion object {
        private const val SCANNED_THRESHOLD = 80
        private const val MAX_MEMORY_BYTES = 16L * 1024L * 1024L
        private const val OCR_MAX_DIMENSION = 1024f
        private const val GC_HINT_EVERY_PAGES = 50
    }

    private val memorySetting: MemoryUsageSetting =
        MemoryUsageSetting.setupMixed(MAX_MEMORY_BYTES)

    override suspend fun iteratePages(
        context: Context,
        uri: Uri,
        allowOcr: Boolean,
        onPage: suspend (page: Int, total: Int, text: String, isOcr: Boolean) -> Unit
    ) = withContext(Dispatchers.IO) {

        val tempPdf = copyUriToTempFile(context, uri)

        try {
            PDDocument.load(tempPdf, memorySetting).use { doc ->
                val totalPages = doc.numberOfPages
                if (totalPages == 0) return@use

                val isScanned = if (allowOcr) detectScanned(doc, totalPages) else false

                if (isScanned) {
                    performOcrFromFile(tempPdf, totalPages, onPage)
                } else {
                    extractNativeText(doc, totalPages, onPage)
                }
            }
        } finally {
            tempPdf.delete()
            System.gc()
        }
    }

    private suspend fun copyUriToTempFile(context: Context, uri: Uri): File =
        withContext(Dispatchers.IO) {
            val tempFile = File.createTempFile("medsearch_", ".pdf", context.cacheDir)
            context.contentResolver.openInputStream(uri)?.use { input ->
                FileOutputStream(tempFile).use { output ->
                    val buffer = ByteArray(64 * 1024)
                    var bytesRead: Int
                    while (input.read(buffer).also { bytesRead = it } != -1) {
                        output.write(buffer, 0, bytesRead)
                        yield()
                    }
                    output.flush()
                }
            } ?: throw IllegalStateException("No se pudo abrir el archivo (InputStream null)")
            tempFile
        }

    private fun detectScanned(doc: PDDocument, totalPages: Int): Boolean {
        val stripper = PDFTextStripper()
        stripper.startPage = 1
        stripper.endPage = min(2, totalPages)
        val sampleText = runCatching { stripper.getText(doc) }.getOrDefault("").orEmpty()
        val nonWhitespaceLen = sampleText.replace("\\s+".toRegex(), "").length
        return nonWhitespaceLen < SCANNED_THRESHOLD
    }

    private suspend fun extractNativeText(
        doc: PDDocument,
        totalPages: Int,
        onPage: suspend (page: Int, total: Int, text: String, isOcr: Boolean) -> Unit
    ) {
        val stripper = PDFTextStripper().apply { sortByPosition = true }

        for (p in 1..totalPages) {
            yield()

            stripper.startPage = p
            stripper.endPage = p
            val text = runCatching { stripper.getText(doc) }
                .getOrDefault("")
                .trim()

            onPage(p, totalPages, cleanText(text), false)

            if (p % GC_HINT_EVERY_PAGES == 0) {
                System.gc()
                delay(20)
            }
        }
    }

    private suspend fun performOcrFromFile(
        pdfFile: File,
        totalPages: Int,
        onPage: suspend (page: Int, total: Int, text: String, isOcr: Boolean) -> Unit
    ) {
        val pfd = ParcelFileDescriptor.open(pdfFile, ParcelFileDescriptor.MODE_READ_ONLY)
        val recognizer = TextRecognition.getClient(TextRecognizerOptions.DEFAULT_OPTIONS)

        try {
            pfd.use { fd ->
                PdfRenderer(fd).use { renderer ->
                    for (p in 1..totalPages) {
                        yield()
                        processOcrPage(renderer, recognizer, p, totalPages, onPage)
                    }
                }
            }
        } finally {
            recognizer.close()
        }
    }

    private suspend fun processOcrPage(
        renderer: PdfRenderer,
        recognizer: com.google.mlkit.vision.text.TextRecognizer,
        p: Int,
        totalPages: Int,
        onPage: suspend (page: Int, total: Int, text: String, isOcr: Boolean) -> Unit
    ) {
        val page = runCatching { renderer.openPage(p - 1) }.getOrNull() ?: return

        try {
            val scale = min(
                1.0f,
                min(OCR_MAX_DIMENSION / page.width, OCR_MAX_DIMENSION / page.height)
            )
            val width = (page.width * scale).toInt().coerceAtLeast(1)
            val height = (page.height * scale).toInt().coerceAtLeast(1)

            val bmp = Bitmap.createBitmap(width, height, Bitmap.Config.RGB_565)
            bmp.eraseColor(Color.WHITE)
            page.render(bmp, null, null, PdfRenderer.Page.RENDER_MODE_FOR_DISPLAY)
            page.close()

            val image = InputImage.fromBitmap(bmp, 0)
            val result = recognizer.process(image).await()

            bmp.recycle()
            onPage(p, totalPages, cleanText(result.text), true)
            delay(120)
        } catch (e: OutOfMemoryError) {
            page.close()
            System.gc()
            onPage(p, totalPages, "[Página ${p}: error de memoria, saltada]", true)
            delay(300)
        } catch (t: Throwable) {
            page.close()
            onPage(p, totalPages, "[Página ${p}: ${t.localizedMessage}]", true)
            delay(100)
        }
    }

    private fun cleanText(raw: String): String {
        if (raw.isBlank()) return ""
        return raw.replace("\u0000", "")
            .replace(Regex("-\\s*\\n\\s*"), "")
            .replace(Regex("[ \\t\\x0B\\f\\r]+"), " ")
            .replace(Regex("\\n{3,}"), "\n\n")
            .trim()
    }
}
