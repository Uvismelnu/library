package com.medsearch.rag.ui

import android.app.Application
import android.content.Context
import android.content.Intent
import android.net.Uri
import androidx.lifecycle.AndroidViewModel
import androidx.lifecycle.viewModelScope
import androidx.work.Data
import androidx.work.ExistingWorkPolicy
import androidx.work.OneTimeWorkRequestBuilder
import androidx.work.WorkManager
import com.medsearch.rag.data.indexing.IndexProgress
import com.medsearch.rag.data.indexing.IndexingService
import com.medsearch.rag.data.llm.LlmEngine
import com.medsearch.rag.data.local.dao.SearchHit
import com.medsearch.rag.data.repository.PreferencesRepository
import com.medsearch.rag.data.repository.RagResult
import com.medsearch.rag.data.repository.SearchRepository
import com.medsearch.rag.data.repository.SummaryResult
import com.medsearch.rag.worker.IndexingWorker
import dagger.hilt.android.lifecycle.HiltViewModel
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch
import javax.inject.Inject

data class HomeUiState(
    val folderUri: String? = null,
    val folderName: String? = null,
    val bookCount: Int = 0,
    val pageCount: Int = 0,
    val indexing: IndexProgress = IndexProgress(),
    val ocrEnabled: Boolean = false,
    val maxChunksForRag: Int = 6,
    val modelLoaded: Boolean = false,
    val modelName: String? = null,
    val disclaimerAck: Boolean = false
)

sealed interface SearchUiState {
    data object Idle : SearchUiState
    data object Searching : SearchUiState
    data class Results(val term: String, val hits: List<SearchHit>) : SearchUiState
    data class Empty(val term: String) : SearchUiState
    data class Error(val message: String) : SearchUiState
}

/** Estado del resumen extractive (instantáneo, sin LLM, automático al buscar). */
sealed interface ExtractiveUiState {
    data object Idle : ExtractiveUiState
    data class Ready(val answer: String, val hits: List<SearchHit>) : ExtractiveUiState
}

/** Estado del resumen con LLM (opcional, bajo demanda, con streaming de tokens). */
sealed interface RagUiState {
    data object Idle : RagUiState
    data object Generating : RagUiState
    /** Texto parcial llegando token por token. isStreaming=false cuando terminó. */
    data class Streaming(
        val partialText: String,
        val hits: List<SearchHit>,
        val isStreaming: Boolean
    ) : RagUiState
    data class Ready(val result: RagResult) : RagUiState
    data class Error(val message: String) : RagUiState
}

@HiltViewModel
class SearchViewModel @Inject constructor(
    app: Application,
    private val preferences: PreferencesRepository,
    private val searchRepository: SearchRepository,
    private val indexingService: IndexingService,
    private val llmEngine: LlmEngine
) : AndroidViewModel(app) {

    private val context: Context get() = getApplication()

    private val _home = MutableStateFlow(HomeUiState())
    val home: StateFlow<HomeUiState> = _home.asStateFlow()

    private val _search = MutableStateFlow<SearchUiState>(SearchUiState.Idle)
    val search: StateFlow<SearchUiState> = _search.asStateFlow()

    private val _extractive = MutableStateFlow<ExtractiveUiState>(ExtractiveUiState.Idle)
    val extractive: StateFlow<ExtractiveUiState> = _extractive.asStateFlow()

    private val _rag = MutableStateFlow<RagUiState>(RagUiState.Idle)
    val rag: StateFlow<RagUiState> = _rag.asStateFlow()

    init {
        viewModelScope.launch {
            preferences.folderUri.collect { uri ->
                _home.update { it.copy(folderUri = uri, folderName = uri?.let(::folderNameFromUri)) }
            }
        }
        viewModelScope.launch {
            preferences.ocrEnabled.collect { e -> _home.update { it.copy(ocrEnabled = e) } }
        }
        viewModelScope.launch {
            preferences.maxChunksForRag.collect { m -> _home.update { it.copy(maxChunksForRag = m) } }
        }
        viewModelScope.launch {
            preferences.disclaimerAcknowledged.collect { ack ->
                _home.update { it.copy(disclaimerAck = ack) }
            }
        }
        viewModelScope.launch {
            preferences.modelPath.collect { _ ->
                _home.update {
                    it.copy(modelLoaded = llmEngine.isLoaded, modelName = llmEngine.loadedFileName)
                }
            }
        }
        viewModelScope.launch {
            searchRepository.observeBookCount().collect { c ->
                _home.update { it.copy(bookCount = c) }
            }
        }
        viewModelScope.launch {
            searchRepository.observePageCount().collect { p ->
                _home.update { it.copy(pageCount = p) }
            }
        }
        viewModelScope.launch {
            indexingService.progress.collect { prog ->
                _home.update { it.copy(indexing = prog) }
            }
        }
    }

    fun onFolderSelected(uri: Uri) {
        viewModelScope.launch {
            try {
                val flags = Intent.FLAG_GRANT_READ_URI_PERMISSION or Intent.FLAG_GRANT_WRITE_URI_PERMISSION
                context.contentResolver.takePersistableUriPermission(uri, flags)
            } catch (e: Exception) {
                try {
                    context.contentResolver.takePersistableUriPermission(uri, Intent.FLAG_GRANT_READ_URI_PERMISSION)
                } catch (e2: Exception) {
                    android.util.Log.e("SearchViewModel", "Error persistiendo permiso", e2)
                }
            }
            preferences.setFolderUri(uri.toString())
        }
    }

    fun startIndexing() {
        viewModelScope.launch {
            // Liberamos el modelo LLM para dar toda la RAM al indexador
            llmEngine.unload()
            _home.update { it.copy(modelLoaded = false, modelName = null) }

            val uri = _home.value.folderUri ?: return@launch
            val req = OneTimeWorkRequestBuilder<IndexingWorker>()
                .setInputData(
                    Data.Builder()
                        .putString(IndexingWorker.KEY_FOLDER_URI, uri)
                        .putBoolean(IndexingWorker.KEY_ALLOW_OCR, _home.value.ocrEnabled)
                        .build()
                )
                .build()

            WorkManager.getInstance(context)
                .enqueueUniqueWork(IndexingWorker.UNIQUE_NAME, ExistingWorkPolicy.REPLACE, req)
        }
    }

    /**
     * Búsqueda + resumen extractive automático (instantáneo, sin LLM).
     * NO carga el LLM aquí: buscar debe ser rápido. El LLM se invoca
     * solo cuando el usuario pulsa "Resumir con IA".
     */
    fun runSearch(term: String) {
        if (term.isBlank()) return
        _search.value = SearchUiState.Searching
        _extractive.value = ExtractiveUiState.Idle
        _rag.value = RagUiState.Idle

        viewModelScope.launch {
            val hits = searchRepository.search(term, limit = 80)
            if (hits.isEmpty()) {
                _search.value = SearchUiState.Empty(term)
                return@launch
            }
            _search.value = SearchUiState.Results(term, hits)

            // Resumen extractive automático: instantáneo, cero alucinaciones
            when (val result = searchRepository.smartSummarize(term, topK = _home.value.maxChunksForRag)) {
                is SummaryResult.Extractive ->
                    _extractive.value = ExtractiveUiState.Ready(result.answer, result.usedHits)
                is SummaryResult.LlmGenerated ->
                    // Si por alguna razón el LLM ya estaba cargado, mostrarlo igual como extractive-like
                    _extractive.value = ExtractiveUiState.Ready(result.answer, result.usedHits)
                is SummaryResult.NoResults ->
                    _extractive.value = ExtractiveUiState.Idle
            }
        }
    }

    private suspend fun ensureModelLoaded() {
        if (!llmEngine.isLoaded) {
            val path = preferences.modelPath.first()
            if (path != null) {
                llmEngine.load(context, path)
                _home.update {
                    it.copy(modelLoaded = llmEngine.isLoaded, modelName = llmEngine.loadedFileName)
                }
            }
        }
    }

    fun clearSearch() {
        _search.value = SearchUiState.Idle
        _extractive.value = ExtractiveUiState.Idle
        _rag.value = RagUiState.Idle
    }

    /**
     * Resumen con LLM bajo demanda, con streaming de tokens en vivo.
     * Solo se invoca al pulsar el botón "Resumir con IA".
     */
    fun summarizeCurrent() {
        val s = _search.value
        if (s !is SearchUiState.Results) return

        _rag.value = RagUiState.Generating
        viewModelScope.launch {
            ensureModelLoaded()
            if (!llmEngine.isLoaded) {
                _rag.value = RagUiState.Error(
                    "Modelo no cargado. Configura un .task en Ajustes para el resumen con IA."
                )
                return@launch
            }

            try {
                searchRepository.smartSummarizeStream(s.term, topK = _home.value.maxChunksForRag)
                    .collect { result ->
                        _rag.value = when (result) {
                            is SummaryResult.LlmGenerated -> RagUiState.Streaming(
                                partialText = result.answer,
                                hits = result.usedHits,
                                isStreaming = result.isStreaming
                            )
                            is SummaryResult.Extractive -> RagUiState.Error(
                                result.fallbackReason
                                    ?: "El LLM no pudo generar. Revisa el resumen extractive arriba."
                            )
                            is SummaryResult.NoResults -> RagUiState.Error(result.message)
                        }
                    }
                // El flow terminó. Si el último estado fue Streaming, marcarlo como completo.
                val current = _rag.value
                if (current is RagUiState.Streaming) {
                    _rag.value = current.copy(isStreaming = false)
                }
            } catch (t: Throwable) {
                _rag.value = RagUiState.Error(
                    "Error generando resumen: ${t.localizedMessage ?: "desconocido"}"
                )
            }
        }
    }

    fun setOcrEnabled(enabled: Boolean) = viewModelScope.launch { preferences.setOcrEnabled(enabled) }
    fun setMaxChunks(n: Int) = viewModelScope.launch { preferences.setMaxChunksForRag(n) }

    fun setModelPath(path: String?) = viewModelScope.launch {
        preferences.setModelPath(path)
        if (path != null) {
            llmEngine.load(context, path)
        } else {
            llmEngine.unload()
        }
        _home.update { it.copy(modelLoaded = llmEngine.isLoaded, modelName = llmEngine.loadedFileName) }
    }

    fun availableModels() = llmEngine.availableModels(context)
    fun acknowledgeDisclaimer() = viewModelScope.launch { preferences.setDisclaimerAcknowledged(true) }
    fun clearIndex() = viewModelScope.launch { searchRepository.clearAll() }

    private fun folderNameFromUri(uriString: String): String? = runCatching {
        val uri = Uri.parse(uriString)
        uri.lastPathSegment?.substringAfterLast(':') ?: uri.path
    }.getOrNull()
}
