package com.medsearch.rag.data.llm

import android.content.Context
import com.google.mediapipe.tasks.genai.llminference.LlmInference
import com.google.mediapipe.tasks.genai.llminference.LlmInference.LlmInferenceOptions
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.channels.awaitClose
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.callbackFlow
import kotlinx.coroutines.flow.flowOn
import kotlinx.coroutines.withContext
import java.io.File
import java.util.concurrent.atomic.AtomicReference
import javax.inject.Inject
import javax.inject.Singleton

@Singleton
class LlmEngine @Inject constructor() {

    companion object {
        private const val MAX_TOKENS_OUTPUT = 2048
        private const val MAX_TOP_K = 40
        private const val MODEL_SUBDIR = "llm"
    }

    @Volatile private var inference: LlmInference? = null
    @Volatile private var loadedPath: String? = null

    // Listener activo para streaming. Se setea ANTES de cada llamada async.
    // AtomicReference para thread-safety: múltiples coroutines no deben pisarse.
    private val activeStreamListener = AtomicReference<((String, Boolean) -> Unit)?>(null)

    val isLoaded: Boolean get() = inference != null
    val loadedFileName: String? get() = loadedPath?.let { File(it).name }
    val loadedModelSizeMb: Long? get() = loadedPath?.let {
        runCatching { File(it).length() / (1024 * 1024) }.getOrNull()
    }

    fun availableModels(context: Context): List<File> {
        val dir = modelDir(context)
        if (!dir.exists()) dir.mkdirs()
        return dir.listFiles { f -> f.isFile && f.extension == "task" }?.toList().orEmpty()
    }

    fun modelDir(context: Context): File =
        File(context.getExternalFilesDir(null), MODEL_SUBDIR)

    fun expectedModelLocation(context: Context): String {
        val dir = modelDir(context)
        if (!dir.exists()) dir.mkdirs()
        return dir.absolutePath
    }

    suspend fun load(context: Context, modelPath: String): Result<Unit> = withContext(Dispatchers.IO) {
        runCatching {
            val file = File(modelPath)
            require(file.exists()) { "Archivo del modelo no encontrado: $modelPath" }
            require(file.length() > 100 * 1024 * 1024) {
                "Archivo del modelo demasiado pequeño (${file.length() / 1024 / 1024} MB). " +
                "Asegúrate de haber descargado el .task completo (>100 MB)."
            }

            unloadInternal()

            // El listener se registra UNA SOLA VEZ en las opciones.
            // Cada llamada async va a invocar este callback.
            // El callback re-direcciona al listener activo en ese momento.
            val options = LlmInferenceOptions.builder()
                .setModelPath(modelPath)
                .setMaxTokens(MAX_TOKENS_OUTPUT)
                .setMaxTopK(MAX_TOP_K)
                .setResultListener { partialResult, done ->
                    // Forward al listener activo (si lo hay)
                    activeStreamListener.get()?.invoke(partialResult, done)
                }
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
        activeStreamListener.set(null)
    }

    /**
     * Generación bloqueante. Espera la respuesta completa.
     */
    suspend fun generate(prompt: String): Result<String> = withContext(Dispatchers.Default) {
        val engine = inference ?: return@withContext Result.failure(
            IllegalStateException("Modelo LLM no cargado. Configúralo en Ajustes.")
        )
        runCatching { engine.generateResponse(prompt) }
    }

    /**
     * Generación streaming via Flow. Emite cada incremento como String acumulado.
     *
     * El listener se setea en activeStreamListener antes de llamar generateResponseAsync,
     * y el listener registrado en LlmInferenceOptions hace el forwarding.
     */
    fun generateStream(prompt: String): Flow<String> = callbackFlow {
        val engine = inference
        if (engine == null) {
            close(IllegalStateException("Modelo LLM no cargado. Configúralo en Ajustes."))
            return@callbackFlow
        }

        val accumulator = StringBuilder()

        // Registrar nuestro listener antes de llamar
        activeStreamListener.set { partial, done ->
            accumulator.append(partial)
            trySend(accumulator.toString())
            if (done) {
                close()
            }
        }

        try {
            // En MediaPipe 0.10.18, generateResponseAsync solo toma el prompt.
            // El callback se invoca via el setResultListener de las options.
            engine.generateResponseAsync(prompt)
        } catch (t: Throwable) {
            activeStreamListener.set(null)
            close(t)
        }

        awaitClose {
            // Limpiar listener cuando el flow se cierra
            activeStreamListener.set(null)
        }
    }.flowOn(Dispatchers.Default)
}
