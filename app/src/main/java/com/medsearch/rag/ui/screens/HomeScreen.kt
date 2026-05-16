package com.medsearch.rag.ui.screens

import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.KeyboardActions
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.outlined.*
import androidx.compose.material3.*
import androidx.compose.runtime.Composable
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.platform.LocalFocusManager
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.input.ImeAction
import androidx.compose.ui.unit.dp
import com.medsearch.rag.R
import com.medsearch.rag.data.local.dao.SearchHit
import com.medsearch.rag.ui.ExtractiveUiState
import com.medsearch.rag.ui.HomeUiState
import com.medsearch.rag.ui.RagUiState
import com.medsearch.rag.ui.SearchUiState
import com.medsearch.rag.ui.SearchViewModel
import com.medsearch.rag.ui.components.StatCard
import com.medsearch.rag.ui.components.rememberHighlightedSnippet

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun HomeScreen(
    viewModel: SearchViewModel,
    onOpenSettings: () -> Unit
) {
    val home by viewModel.home.collectAsState()
    val search by viewModel.search.collectAsState()
    val extractive by viewModel.extractive.collectAsState()
    val rag by viewModel.rag.collectAsState()

    val focus = LocalFocusManager.current
    var query by rememberSaveable { mutableStateOf("") }

    val folderPicker = rememberLauncherForActivityResult(
        contract = ActivityResultContracts.OpenDocumentTree()
    ) { uri ->
        try {
            uri?.let(viewModel::onFolderSelected)
        } catch (e: Exception) {
            android.util.Log.e("HomeScreen", "Error en el callback del selector de carpetas", e)
        }
    }

    Scaffold(
        topBar = {
            TopAppBar(
                title = {
                    Text(
                        stringResource(R.string.app_name),
                        style = MaterialTheme.typography.titleLarge.copy(fontWeight = FontWeight.SemiBold)
                    )
                },
                actions = {
                    IconButton(onClick = onOpenSettings) {
                        Icon(Icons.Outlined.Settings, contentDescription = stringResource(R.string.open_settings))
                    }
                },
                colors = TopAppBarDefaults.topAppBarColors(
                    containerColor = MaterialTheme.colorScheme.background
                )
            )
        },
        containerColor = MaterialTheme.colorScheme.background
    ) { padding ->
        LazyColumn(
            modifier = Modifier
                .fillMaxSize()
                .padding(padding)
                .padding(horizontal = 20.dp),
            verticalArrangement = Arrangement.spacedBy(16.dp),
            contentPadding = PaddingValues(vertical = 16.dp)
        ) {
            item {
                FolderCard(
                    home = home,
                    onPickFolder = { folderPicker.launch(null) }
                )
            }

            item {
                Row(horizontalArrangement = Arrangement.spacedBy(12.dp)) {
                    StatCard(
                        label = "Libros",
                        value = home.bookCount.toString(),
                        modifier = Modifier.weight(1f)
                    )
                    StatCard(
                        label = "Páginas",
                        value = home.pageCount.toString(),
                        accent = MaterialTheme.colorScheme.tertiary,
                        modifier = Modifier.weight(1f)
                    )
                    StatCard(
                        label = "LLM",
                        value = if (home.modelConfigured) "ON" else "OFF",
                        accent = if (home.modelConfigured) MaterialTheme.colorScheme.primary
                        else MaterialTheme.colorScheme.error,
                        modifier = Modifier.weight(1f)
                    )
                }
            }

            item {
                IndexingCard(home = home, onIndex = viewModel::startIndexing)
            }

            item {
                SearchField(
                    query = query,
                    onQueryChange = { query = it },
                    onSubmit = {
                        focus.clearFocus()
                        viewModel.runSearch(query)
                    },
                    enabled = home.bookCount > 0 || !home.indexing.running
                )
            }

            // 1. Síntesis extractive automática (instantánea, cero alucinaciones)
            if (search is SearchUiState.Results && extractive is ExtractiveUiState.Ready) {
                item {
                    ExtractiveCard(state = extractive as ExtractiveUiState.Ready)
                }
            }

            // 2. Acción RAG con LLM (bajo demanda) + resultado streaming
            if (search is SearchUiState.Results) {
                item {
                    RagActionRow(
                        modelConfigured = home.modelConfigured,
                        ragState = rag,
                        onSummarize = viewModel::summarizeCurrent
                    )
                }
                when (val r = rag) {
                    is RagUiState.Streaming -> item {
                        RagStreamingCard(
                            text = r.partialText,
                            hits = r.hits,
                            isStreaming = r.isStreaming,
                            modelName = home.modelName
                        )
                    }
                    is RagUiState.Ready -> item {
                        RagAnswerCard(result = r.result)
                    }
                    is RagUiState.Error -> item {
                        ErrorBanner(r.message)
                    }
                    else -> Unit
                }
            }

            // 3. Resultados FTS crudos
            when (val s = search) {
                is SearchUiState.Idle -> Unit
                is SearchUiState.Searching -> item { LoadingCard(stringResource(R.string.search_in_progress)) }
                is SearchUiState.Empty -> item {
                    Card(
                        shape = RoundedCornerShape(20.dp),
                        colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surface)
                    ) {
                        Text(
                            stringResource(R.string.no_results),
                            modifier = Modifier.padding(20.dp),
                            style = MaterialTheme.typography.bodyLarge,
                            color = MaterialTheme.colorScheme.onSurfaceVariant
                        )
                    }
                }
                is SearchUiState.Results -> {
                    item {
                        val books = s.hits.map { it.bookId }.distinct().size
                        Text(
                            stringResource(R.string.results_count, s.hits.size, books),
                            style = MaterialTheme.typography.titleMedium,
                            color = MaterialTheme.colorScheme.onSurfaceVariant
                        )
                    }
                    items(s.hits, key = { it.chunkId }) { hit -> HitCard(hit) }
                }
                is SearchUiState.Error -> item { ErrorBanner(s.message) }
            }
        }
    }
}

@Composable
private fun FolderCard(home: HomeUiState, onPickFolder: () -> Unit) {
    Card(
        shape = RoundedCornerShape(24.dp),
        colors = CardDefaults.cardColors(
            containerColor = MaterialTheme.colorScheme.primaryContainer
        ),
        elevation = CardDefaults.cardElevation(defaultElevation = 0.dp)
    ) {
        Column(Modifier.padding(20.dp)) {
            Row(verticalAlignment = Alignment.CenterVertically) {
                Icon(
                    Icons.Outlined.FolderOpen,
                    contentDescription = null,
                    tint = MaterialTheme.colorScheme.onPrimaryContainer
                )
                Spacer(Modifier.width(10.dp))
                Text(
                    "Biblioteca",
                    style = MaterialTheme.typography.titleMedium,
                    fontWeight = FontWeight.SemiBold,
                    color = MaterialTheme.colorScheme.onPrimaryContainer
                )
            }
            Spacer(Modifier.height(8.dp))
            Text(
                text = home.folderName?.let { "📁  $it" }
                    ?: stringResource(R.string.no_folder_selected),
                style = MaterialTheme.typography.bodyMedium,
                color = MaterialTheme.colorScheme.onPrimaryContainer
            )
            Spacer(Modifier.height(16.dp))
            Button(
                onClick = onPickFolder,
                colors = ButtonDefaults.buttonColors(
                    containerColor = MaterialTheme.colorScheme.primary,
                    contentColor = MaterialTheme.colorScheme.onPrimary
                ),
                modifier = Modifier.fillMaxWidth(),
                shape = RoundedCornerShape(14.dp)
            ) {
                Icon(Icons.Outlined.FolderOpen, contentDescription = null)
                Spacer(Modifier.width(8.dp))
                Text(stringResource(R.string.select_folder))
            }
        }
    }
}

@Composable
private fun IndexingCard(home: HomeUiState, onIndex: () -> Unit) {
    val prog = home.indexing
    Card(
        shape = RoundedCornerShape(20.dp),
        colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surface),
        elevation = CardDefaults.cardElevation(defaultElevation = 0.dp)
    ) {
        Column(Modifier.padding(20.dp)) {
            Row(verticalAlignment = Alignment.CenterVertically) {
                Icon(Icons.Outlined.AutoStories, contentDescription = null, tint = MaterialTheme.colorScheme.primary)
                Spacer(Modifier.width(10.dp))
                Text(
                    if (home.bookCount == 0) stringResource(R.string.index_library)
                    else stringResource(R.string.reindex_library),
                    style = MaterialTheme.typography.titleMedium,
                    fontWeight = FontWeight.SemiBold
                )
            }
            if (prog.running) {
                Spacer(Modifier.height(12.dp))
                prog.currentBook?.let {
                    Text(
                        stringResource(R.string.indexing_book, it),
                        style = MaterialTheme.typography.bodyMedium,
                        color = MaterialTheme.colorScheme.onSurfaceVariant
                    )
                }
                if (prog.totalPages > 0) {
                    Text(
                        stringResource(R.string.indexing_page, prog.currentPage, prog.totalPages),
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant
                    )
                }
                Spacer(Modifier.height(8.dp))
                LinearProgressIndicator(
                    progress = {
                        if (prog.totalPages == 0) 0f
                        else prog.currentPage.toFloat() / prog.totalPages.toFloat()
                    },
                    modifier = Modifier.fillMaxWidth()
                )
            } else {
                Spacer(Modifier.height(12.dp))
                Button(
                    onClick = onIndex,
                    enabled = home.folderUri != null && !prog.running,
                    modifier = Modifier.fillMaxWidth(),
                    shape = RoundedCornerShape(14.dp)
                ) {
                    Icon(Icons.Outlined.PlayArrow, contentDescription = null)
                    Spacer(Modifier.width(8.dp))
                    Text(
                        if (home.bookCount == 0) stringResource(R.string.index_library)
                        else stringResource(R.string.reindex_library)
                    )
                }
                prog.errorMessage?.let {
                    Spacer(Modifier.height(8.dp))
                    Text(it, color = MaterialTheme.colorScheme.error, style = MaterialTheme.typography.bodySmall)
                }
                prog.finishedAt?.let {
                    Spacer(Modifier.height(8.dp))
                    Text(
                        stringResource(R.string.indexing_done),
                        style = MaterialTheme.typography.bodyMedium,
                        color = MaterialTheme.colorScheme.primary
                    )
                }
            }
        }
    }
}

@Composable
private fun SearchField(
    query: String,
    onQueryChange: (String) -> Unit,
    onSubmit: () -> Unit,
    enabled: Boolean
) {
    OutlinedTextField(
        value = query,
        onValueChange = onQueryChange,
        leadingIcon = { Icon(Icons.Outlined.Search, contentDescription = null) },
        trailingIcon = {
            FilledIconButton(
                onClick = onSubmit,
                enabled = enabled && query.isNotBlank()
            ) {
                Icon(Icons.Outlined.ArrowForward, contentDescription = stringResource(R.string.search))
            }
        },
        placeholder = { Text(stringResource(R.string.search_hint)) },
        keyboardOptions = KeyboardOptions(imeAction = ImeAction.Search),
        keyboardActions = KeyboardActions(onSearch = { onSubmit() }),
        singleLine = true,
        enabled = enabled,
        modifier = Modifier.fillMaxWidth(),
        shape = RoundedCornerShape(16.dp)
    )
}

/**
 * Card de síntesis extractive: aparece automáticamente al buscar.
 * Texto literal de los libros, cero alucinaciones, instantáneo.
 * Distinción visual: chip 📖 "Texto del libro".
 */
@Composable
private fun ExtractiveCard(state: ExtractiveUiState.Ready) {
    Card(
        shape = RoundedCornerShape(20.dp),
        colors = CardDefaults.cardColors(
            containerColor = MaterialTheme.colorScheme.tertiaryContainer
        )
    ) {
        Column(Modifier.padding(20.dp)) {
            Row(verticalAlignment = Alignment.CenterVertically) {
                Icon(
                    Icons.Outlined.MenuBook,
                    contentDescription = null,
                    tint = MaterialTheme.colorScheme.onTertiaryContainer
                )
                Spacer(Modifier.width(8.dp))
                Text(
                    "Síntesis bibliográfica",
                    style = MaterialTheme.typography.titleMedium,
                    fontWeight = FontWeight.SemiBold,
                    color = MaterialTheme.colorScheme.onTertiaryContainer,
                    modifier = Modifier.weight(1f)
                )
                AssistChip(
                    onClick = { },
                    label = { Text("📖 Literal", style = MaterialTheme.typography.labelSmall) },
                    colors = AssistChipDefaults.assistChipColors(
                        containerColor = MaterialTheme.colorScheme.tertiary.copy(alpha = 0.2f)
                    )
                )
            }
            Spacer(Modifier.height(12.dp))
            Text(
                state.answer,
                style = MaterialTheme.typography.bodyMedium,
                color = MaterialTheme.colorScheme.onTertiaryContainer
            )
            Spacer(Modifier.height(12.dp))
            Text(
                "Cada oración es literal del libro citado. Sin reescritura por IA.",
                style = MaterialTheme.typography.labelSmall,
                color = MaterialTheme.colorScheme.onTertiaryContainer.copy(alpha = 0.75f)
            )
        }
    }
}

@Composable
private fun RagActionRow(
    modelConfigured: Boolean,
    ragState: RagUiState,
    onSummarize: () -> Unit
) {
    val isWorking = ragState is RagUiState.Generating ||
            (ragState is RagUiState.Streaming && ragState.isStreaming)

    Card(
        shape = RoundedCornerShape(20.dp),
        colors = CardDefaults.cardColors(
            containerColor = MaterialTheme.colorScheme.secondaryContainer
        )
    ) {
        Column(Modifier.padding(16.dp)) {
            Row(verticalAlignment = Alignment.CenterVertically) {
                Icon(
                    Icons.Outlined.AutoAwesome,
                    contentDescription = null,
                    tint = MaterialTheme.colorScheme.onSecondaryContainer
                )
                Spacer(Modifier.width(8.dp))
                Text(
                    "Resumen reescrito con IA",
                    style = MaterialTheme.typography.titleSmall,
                    fontWeight = FontWeight.SemiBold,
                    color = MaterialTheme.colorScheme.onSecondaryContainer
                )
            }
            Spacer(Modifier.height(8.dp))
            Text(
                if (modelConfigured) "Genera un resumen integrado y reescrito de los pasajes. Tarda 30-90s. Puede contener imprecisiones — verifica con el texto literal de arriba."
                else "Configura un modelo LLM .task en Ajustes para activar el resumen reescrito. El resumen literal de arriba ya está disponible sin modelo.",
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSecondaryContainer
            )
            Spacer(Modifier.height(12.dp))
            Button(
                onClick = onSummarize,
                enabled = modelConfigured && !isWorking,
                modifier = Modifier.fillMaxWidth(),
                shape = RoundedCornerShape(14.dp)
            ) {
                if (isWorking) {
                    CircularProgressIndicator(
                        modifier = Modifier.size(18.dp),
                        strokeWidth = 2.dp,
                        color = MaterialTheme.colorScheme.onPrimary
                    )
                    Spacer(Modifier.width(8.dp))
                    Text(stringResource(R.string.generating_summary))
                } else {
                    Icon(Icons.Outlined.AutoAwesome, contentDescription = null)
                    Spacer(Modifier.width(8.dp))
                    Text(stringResource(R.string.summarize_with_ai))
                }
            }
        }
    }
}

/**
 * Card del resumen LLM con streaming: el texto aparece token por token.
 * Distinción visual: chip 🤖 "IA — verificar".
 */
@Composable
private fun RagStreamingCard(
    text: String,
    hits: List<SearchHit>,
    isStreaming: Boolean,
    modelName: String?
) {
    Card(
        shape = RoundedCornerShape(20.dp),
        colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.primaryContainer)
    ) {
        Column(Modifier.padding(20.dp)) {
            Row(verticalAlignment = Alignment.CenterVertically) {
                Icon(
                    Icons.Outlined.AutoAwesome,
                    contentDescription = null,
                    tint = MaterialTheme.colorScheme.onPrimaryContainer
                )
                Spacer(Modifier.width(8.dp))
                Text(
                    stringResource(R.string.ai_summary),
                    style = MaterialTheme.typography.titleMedium,
                    fontWeight = FontWeight.SemiBold,
                    color = MaterialTheme.colorScheme.onPrimaryContainer,
                    modifier = Modifier.weight(1f)
                )
                if (isStreaming) {
                    CircularProgressIndicator(
                        modifier = Modifier.size(16.dp),
                        strokeWidth = 2.dp,
                        color = MaterialTheme.colorScheme.onPrimaryContainer
                    )
                } else {
                    AssistChip(
                        onClick = { },
                        label = { Text("🤖 IA", style = MaterialTheme.typography.labelSmall) },
                        colors = AssistChipDefaults.assistChipColors(
                            containerColor = MaterialTheme.colorScheme.primary.copy(alpha = 0.2f)
                        )
                    )
                }
            }
            Spacer(Modifier.height(12.dp))
            Text(
                text.ifBlank { "Generando…" },
                style = MaterialTheme.typography.bodyMedium,
                color = MaterialTheme.colorScheme.onPrimaryContainer
            )
            if (!isStreaming && hits.isNotEmpty()) {
                Spacer(Modifier.height(16.dp))
                HorizontalDivider(color = MaterialTheme.colorScheme.onPrimaryContainer.copy(alpha = 0.2f))
                Spacer(Modifier.height(12.dp))
                Text(
                    "Fuentes utilizadas:",
                    style = MaterialTheme.typography.labelMedium,
                    color = MaterialTheme.colorScheme.onPrimaryContainer
                )
                hits.forEach { hit ->
                    Text(
                        "• ${hit.bookTitle} — p. ${hit.pageNumber}",
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onPrimaryContainer
                    )
                }
                Spacer(Modifier.height(12.dp))
                Text(
                    stringResource(R.string.summary_disclaimer),
                    style = MaterialTheme.typography.labelSmall,
                    color = MaterialTheme.colorScheme.onPrimaryContainer.copy(alpha = 0.75f)
                )
            }
        }
    }
}

@Composable
private fun RagAnswerCard(result: com.medsearch.rag.data.repository.RagResult) {
    Card(
        shape = RoundedCornerShape(20.dp),
        colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.primaryContainer)
    ) {
        Column(Modifier.padding(20.dp)) {
            Row(verticalAlignment = Alignment.CenterVertically) {
                Icon(
                    Icons.Outlined.AutoAwesome,
                    contentDescription = null,
                    tint = MaterialTheme.colorScheme.onPrimaryContainer
                )
                Spacer(Modifier.width(8.dp))
                Text(
                    stringResource(R.string.ai_summary),
                    style = MaterialTheme.typography.titleMedium,
                    fontWeight = FontWeight.SemiBold,
                    color = MaterialTheme.colorScheme.onPrimaryContainer
                )
            }
            Spacer(Modifier.height(12.dp))
            Text(
                result.answer,
                style = MaterialTheme.typography.bodyMedium,
                color = MaterialTheme.colorScheme.onPrimaryContainer
            )
            Spacer(Modifier.height(16.dp))
            HorizontalDivider(color = MaterialTheme.colorScheme.onPrimaryContainer.copy(alpha = 0.2f))
            Spacer(Modifier.height(12.dp))
            Text(
                "Fuentes utilizadas:",
                style = MaterialTheme.typography.labelMedium,
                color = MaterialTheme.colorScheme.onPrimaryContainer
            )
            result.usedHits.forEach { hit ->
                Text(
                    "• ${hit.bookTitle} — p. ${hit.pageNumber}",
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onPrimaryContainer
                )
            }
            Spacer(Modifier.height(12.dp))
            Text(
                stringResource(R.string.summary_disclaimer),
                style = MaterialTheme.typography.labelSmall,
                color = MaterialTheme.colorScheme.onPrimaryContainer.copy(alpha = 0.75f)
            )
        }
    }
}

@Composable
private fun HitCard(hit: SearchHit) {
    val annotated = rememberHighlightedSnippet(hit.snippet)
    Card(
        shape = RoundedCornerShape(18.dp),
        colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surface),
        elevation = CardDefaults.cardElevation(defaultElevation = 0.dp),
        modifier = Modifier.fillMaxWidth()
    ) {
        Column(Modifier.padding(16.dp)) {
            Row(verticalAlignment = Alignment.CenterVertically) {
                Box(
                    Modifier
                        .size(8.dp)
                        .clip(RoundedCornerShape(50))
                        .background(MaterialTheme.colorScheme.primary)
                )
                Spacer(Modifier.width(8.dp))
                Text(
                    hit.bookTitle,
                    style = MaterialTheme.typography.titleSmall,
                    fontWeight = FontWeight.SemiBold,
                    modifier = Modifier.weight(1f),
                    maxLines = 1
                )
                Text(
                    stringResource(R.string.page_number, hit.pageNumber),
                    style = MaterialTheme.typography.labelMedium,
                    color = MaterialTheme.colorScheme.primary
                )
            }
            Spacer(Modifier.height(10.dp))
            Text(
                annotated,
                style = MaterialTheme.typography.bodyMedium,
                color = MaterialTheme.colorScheme.onSurface
            )
        }
    }
}

@Composable
private fun LoadingCard(message: String) {
    Card(
        shape = RoundedCornerShape(20.dp),
        colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surface)
    ) {
        Row(
            Modifier.padding(20.dp),
            verticalAlignment = Alignment.CenterVertically
        ) {
            CircularProgressIndicator(
                modifier = Modifier.size(20.dp),
                strokeWidth = 2.dp
            )
            Spacer(Modifier.width(12.dp))
            Text(message, style = MaterialTheme.typography.bodyMedium)
        }
    }
}

@Composable
private fun ErrorBanner(message: String) {
    Card(
        shape = RoundedCornerShape(16.dp),
        colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.errorContainer)
    ) {
        Row(
            Modifier.padding(16.dp),
            verticalAlignment = Alignment.CenterVertically
        ) {
            Icon(
                Icons.Outlined.ErrorOutline,
                contentDescription = null,
                tint = MaterialTheme.colorScheme.onErrorContainer
            )
            Spacer(Modifier.width(8.dp))
            Text(
                message,
                style = MaterialTheme.typography.bodyMedium,
                color = MaterialTheme.colorScheme.onErrorContainer
            )
        }
    }
}
