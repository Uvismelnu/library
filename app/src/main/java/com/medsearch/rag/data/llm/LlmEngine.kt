package com.medsearch.rag.data.llm

import android.content.Context
import com.google.mediapipe.tasks.genai.llminference.LlmInference
import com.google.mediapipe.tasks.genai.llminference.LlmInference.LlmInferenceOptions
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import java.io.File
import javax.inject.Inject
import javax.inject.Singleton

/**
 * Adaptador alrededor de MediaPipe LLM Inference (formato .task).
 *
 * El modelo recomendado para móviles modernos:
 *   - gemma-2b-it-cpu-int4.task   (~1.3 GB)
 *   - gemma-2b-it-gpu-int4.task   (más rápido si el dispositivo soporta GPU delegate)
 *
 * Se descarga manualmente desde Kaggle (HuggingFace también ofrece formatos
 * compatibles tras conversión) y se coloca en:
 *   /Android/data/com.medsearch.rag/files/llm/<modelo>.task
 *
 * Esto evita problemas de bundling (APK > 150 MB) y permite al usuario
 * elegir entre Gemma 2B, Phi-3 mini, Falcon-RW-1B, etc.
 */
@Singleton
class LlmEngine @Inject constructor() {

    @Volatile private var inference: LlmInference? = null
    @Volatile private var loadedPath: String? = null

    val isLoaded: Boolean get() = inference != null
    val loadedFileName: String? get() = loadedPath?.let { File(it).name }

    /** Lista los .task disponibles en el directorio externo de la app. */
    fun availableModels(context: Context): List<File> {
        val dir = modelDir(context)
        if (!dir.exists()) dir.mkdirs()
        return dir.listFiles { f -> f.isFile && f.extension == "task" }?.toList().orEmpty()
    }

    fun modelDir(context: Context): File =
        File(context.getExternalFilesDir(null), "llm")

    suspend fun load(context: Context, modelPath: String): Result<Unit> = withContext(Dispatchers.IO) {
        runCatching {
            unloadInternal()
            val options = LlmInferenceOptions.builder()
                .setModelPath(modelPath)
                .setMaxTokens(2048)
                .setMaxTopK(40)
                .build()
            inference = LlmInference.createFromOptions(context, options)
            loadedPath = modelPath
        }
    }

    suspend fun unload() = withContext(Dispatchers.IO) { unloadInternal() }

    private fun unloadInternal() {
        try { inference?.close() } catch (_: Throwable) {}
        inference = null
        loadedPath = null
    }

    /**
     * Genera respuesta para un prompt. Bloqueante; el caller usa Dispatchers.IO o un Worker.
     */
    suspend fun generate(prompt: String): Result<String> = withContext(Dispatchers.Default) {
        val engine = inference ?: return@withContext Result.failure(
            IllegalStateException("Modelo LLM no cargado. Configúralo en Ajustes.")
        )
        runCatching { engine.generateResponse(prompt) }
    }
}
