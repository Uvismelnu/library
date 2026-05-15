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

    private val scannedThreshold = 80
    // Configuramos para usar disco si supera los 16MB de RAM
    private val memorySetting = MemoryUsageSetting.setupTempFileOnly(16 * 1024 * 1024)

    override suspend fun iteratePages(
        context: Context,
        uri: Uri,
        allowOcr: Boolean,
        onPage: suspend (page: Int, total: Int, text: String, isOcr: Boolean) -> Unit
    ) = withContext(Dispatchers.IO) {
        
        val pfd = context.contentResolver.openFileDescriptor(uri, "r") 
            ?: throw Exception("No se pudo abrir el archivo (PFD null)")

        pfd.use { fd ->
            var isScanned = false
            var totalPages = 0

            // PASO 1: Analizar estructura
            context.contentResolver.openInputStream(uri)?.use { input ->
                PDDocument.load(input, memorySetting).use { doc ->
                    totalPages = doc.numberOfPages
                    val stripper = PDFTextStripper()
                    stripper.startPage = 1
                    stripper.endPage = min(2, totalPages)
                    val sampleText = stripper.getText(doc).orEmpty()
                    isScanned = allowOcr && (sampleText.replace("\\s+".toRegex(), "").length < scannedThreshold)
                }
            }

            if (totalPages == 0) return@withContext

            if (isScanned) {
                performOcr(context, fd, totalPages, onPage)
            } else {
                // PASO 2: Extraer texto nativo re-abriendo el documento para procesar
                context.contentResolver.openInputStream(uri)?.use { input ->
                    PDDocument.load(input, memorySetting).use { doc ->
                        val stripper = PDFTextStripper().apply { sortByPosition = true }
                        for (p in 1..totalPages) {
                            yield()
                            stripper.startPage = p
                            stripper.endPage = p
                            val text = runCatching { stripper.getText(doc) }.getOrDefault("").trim()
                            onPage(p, totalPages, cleanText(text), false)
                            if (p % 100 == 0) System.gc()
                        }
                    }
                }
            }
        }
        System.gc()
    }

    private suspend fun performOcr(
        context: Context,
        pfd: ParcelFileDescriptor,
        totalPages: Int,
        onPage: suspend (page: Int, total: Int, text: String, isOcr: Boolean) -> Unit
    ) {
        val recognizer = TextRecognition.getClient(TextRecognizerOptions.DEFAULT_OPTIONS)
        try {
            PdfRenderer(pfd).use { renderer ->
                for (p in 1..totalPages) {
                    yield()
                    val page = runCatching { renderer.openPage(p - 1) }.getOrNull() ?: continue
                    
                    val maxDim = 1536f
                    val scale = min(1.0f, min(maxDim / page.width, maxDim / page.height))
                    
                    try {
                        val bmp = Bitmap.createBitmap(
                            (page.width * scale).toInt(),
                            (page.height * scale).toInt(),
                            Bitmap.Config.RGB_565
                        )
                        bmp.eraseColor(Color.WHITE)
                        page.render(bmp, null, null, PdfRenderer.Page.RENDER_MODE_FOR_DISPLAY)
                        page.close()

                        val image = InputImage.fromBitmap(bmp, 0)
                        val result = recognizer.process(image).await()
                        
                        bmp.recycle()
                        onPage(p, totalPages, cleanText(result.text), true)
                        delay(50)
                    } catch (e: OutOfMemoryError) {
                        System.gc()
                        onPage(p, totalPages, "[Error de memoria en página]", true)
                        delay(200)
                    }
                }
            }
        } finally {
            recognizer.close()
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
