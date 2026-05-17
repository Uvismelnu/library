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
import com.medsearch.rag.data.repository.PreferencesRepository
import com.medsearch.rag.data.repository.SearchRepository
import com.medsearch.rag.data.pdf.PdfPageRenderer
import com.medsearch.rag.worker.IndexingWorker
import dagger.hilt.android.lifecycle.HiltViewModel
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
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
    val disclaimerAck: Boolean = false
)

/** Una página de resultado: el PDF, su título y el número de página a renderizar. */
data class PageResult(
    val bookId: Long,
    val bookTitle: String,
    val bookUri: String,
    val pageNumber: Int
)

sealed interface SearchUiState {
    data object Idle : SearchUiState
    data object Searching : SearchUiState
    data class Results(val term: String, val pages: List<PageResult>) : SearchUiState
    data class Empty(val term: String) : SearchUiState
    data class Error(val message: String) : SearchUiState
}

@HiltViewModel
class SearchViewModel @Inject constructor(
    app: Application,
    private val preferences: PreferencesRepository,
    private val searchRepository: SearchRepository,
    private val indexingService: IndexingService,
    val pdfPageRenderer: PdfPageRenderer
) : AndroidViewModel(app) {

    private val context: Context get() = getApplication()

    private val _home = MutableStateFlow(HomeUiState())
    val home: StateFlow<HomeUiState> = _home.asStateFlow()

    private val _search = MutableStateFlow<SearchUiState>(SearchUiState.Idle)
    val search: StateFlow<SearchUiState> = _search.asStateFlow()

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
            preferences.disclaimerAcknowledged.collect { ack ->
                _home.update { it.copy(disclaimerAck = ack) }
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
     * Busca el término y produce la lista de páginas (libro + nº de página)
     * donde aparece, deduplicada y ordenada por libro y página.
     */
    fun runSearch(term: String) {
        if (term.isBlank()) return
        _search.value = SearchUiState.Searching

        viewModelScope.launch {
            val hits = searchRepository.search(term, limit = 100)
            if (hits.isEmpty()) {
                _search.value = SearchUiState.Empty(term)
                return@launch
            }

            // Resolver el uri de cada libro y deduplicar por (libro, página)
            val uriCache = HashMap<Long, String?>()
            val seen = HashSet<String>()
            val pages = mutableListOf<PageResult>()

            for (hit in hits) {
                val uri = uriCache.getOrPut(hit.bookId) {
                    searchRepository.bookUriById(hit.bookId)
                } ?: continue

                val key = "${hit.bookId}|${hit.pageNumber}"
                if (key in seen) continue
                seen.add(key)

                pages.add(
                    PageResult(
                        bookId = hit.bookId,
                        bookTitle = hit.bookTitle,
                        bookUri = uri,
                        pageNumber = hit.pageNumber
                    )
                )
            }

            // Orden: por título de libro, luego por número de página
            pages.sortWith(compareBy({ it.bookTitle }, { it.pageNumber }))

            _search.value = if (pages.isEmpty()) SearchUiState.Empty(term)
            else SearchUiState.Results(term, pages)
        }
    }

    fun clearSearch() {
        _search.value = SearchUiState.Idle
    }

    fun setOcrEnabled(enabled: Boolean) = viewModelScope.launch { preferences.setOcrEnabled(enabled) }
    fun acknowledgeDisclaimer() = viewModelScope.launch { preferences.setDisclaimerAcknowledged(true) }
    fun clearIndex() = viewModelScope.launch { searchRepository.clearAll() }

    private fun folderNameFromUri(uriString: String): String? = runCatching {
        val uri = Uri.parse(uriString)
        uri.lastPathSegment?.substringAfterLast(':') ?: uri.path
    }.getOrNull()
}
