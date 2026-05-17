package com.medsearch.rag.data.pdf

import android.content.Context
import android.graphics.Bitmap
import android.graphics.Color
import android.graphics.pdf.PdfRenderer
import android.net.Uri
import android.os.ParcelFileDescriptor
import android.util.LruCache
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import javax.inject.Inject
import javax.inject.Singleton

/**
 * Renderiza páginas individuales de un PDF a Bitmap en alta calidad.
 *
 * Diseño para evitar OOM (lección aprendida del LLM):
 *  - Renderiza UNA página a la vez, bajo demanda (no todas de golpe).
 *  - Cache LRU pequeño (4 páginas) para que el scroll hacia atrás sea fluido
 *    sin acumular decenas de bitmaps en memoria.
 *  - Abre y cierra el PdfRenderer por cada render (no mantiene el PDF abierto).
 *  - Ancho objetivo configurable; alta calidad por defecto (2048 px).
 */
@Singleton
class PdfPageRenderer @Inject constructor() {

    companion object {
        // Alta calidad: 2048 px de ancho. Texto y figuras nítidos.
        private const val TARGET_WIDTH_PX = 2048

        // Cache de 4 páginas renderizadas. Cada bitmap ARGB_8888 a 2048px
        // ~ 16-24 MB, así que 4 ≈ 80-96 MB máximo. Seguro en 6 GB.
        private const val CACHE_SIZE = 4
    }

    // key = "uri|pageNumber"
    private val cache = object : LruCache<String, Bitmap>(CACHE_SIZE) {
        override fun entryRemoved(
            evicted: Boolean,
            key: String,
            oldValue: Bitmap,
            newValue: Bitmap?
        ) {
            if (evicted && !oldValue.isRecycled) {
                oldValue.recycle()
            }
        }
    }

    sealed class RenderResult {
        data class Success(val bitmap: Bitmap) : RenderResult()
        data class Error(val message: String) : RenderResult()
    }

    /**
     * Renderiza la página [pageNumber] (1-indexed) del PDF en [pdfUri].
     * Devuelve un Bitmap de alta resolución o un error legible.
     */
    suspend fun renderPage(
        context: Context,
        pdfUri: String,
        pageNumber: Int
    ): RenderResult = withContext(Dispatchers.IO) {
        val cacheKey = "$pdfUri|$pageNumber"
        cache.get(cacheKey)?.let { cached ->
            if (!cached.isRecycled) return@withContext RenderResult.Success(cached)
        }

        var pfd: ParcelFileDescriptor? = null
        var renderer: PdfRenderer? = null
        try {
            pfd = context.contentResolver.openFileDescriptor(Uri.parse(pdfUri), "r")
                ?: return@withContext RenderResult.Error(
                    "No se pudo abrir el PDF. ¿La carpeta sigue seleccionada?"
                )

            renderer = PdfRenderer(pfd)

            if (pageNumber < 1 || pageNumber > renderer.pageCount) {
                return@withContext RenderResult.Error(
                    "Página $pageNumber fuera de rango (el PDF tiene ${renderer.pageCount})."
                )
            }

            val page = renderer.openPage(pageNumber - 1)
            try {
                // Escalar manteniendo proporción a TARGET_WIDTH_PX de ancho
                val scale = TARGET_WIDTH_PX.toFloat() / page.width.toFloat()
                val width = TARGET_WIDTH_PX
                val height = (page.height * scale).toInt().coerceAtLeast(1)

                val bitmap = Bitmap.createBitmap(width, height, Bitmap.Config.ARGB_8888)
                bitmap.eraseColor(Color.WHITE)
                page.render(bitmap, null, null, PdfRenderer.Page.RENDER_MODE_FOR_DISPLAY)

                cache.put(cacheKey, bitmap)
                RenderResult.Success(bitmap)
            } finally {
                page.close()
            }
        } catch (oom: OutOfMemoryError) {
            cache.evictAll()
            System.gc()
            RenderResult.Error("Memoria insuficiente para renderizar la página $pageNumber.")
        } catch (t: Throwable) {
            RenderResult.Error("Error renderizando página $pageNumber: ${t.localizedMessage}")
        } finally {
            try { renderer?.close() } catch (_: Throwable) {}
            try { pfd?.close() } catch (_: Throwable) {}
        }
    }

    fun clearCache() {
        cache.evictAll()
    }
}
