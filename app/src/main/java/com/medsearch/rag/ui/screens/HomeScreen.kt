package com.medsearch.rag.ui.screens

import android.graphics.Bitmap
import androidx.activity.compose.BackHandler
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.Image
import androidx.compose.foundation.background
import androidx.compose.foundation.gestures.detectTapGestures
import androidx.compose.foundation.gestures.detectTransformGestures
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.KeyboardActions
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.outlined.Close
import androidx.compose.material.icons.outlined.FolderOpen
import androidx.compose.material.icons.outlined.PlayArrow
import androidx.compose.material.icons.outlined.Search
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.asImageBitmap
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalFocusManager
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.input.ImeAction
import androidx.compose.ui.unit.dp
import androidx.compose.ui.window.Dialog
import androidx.compose.ui.window.DialogProperties
import com.medsearch.rag.data.pdf.PdfPageRenderer
import com.medsearch.rag.ui.PageResult
import com.medsearch.rag.ui.SearchUiState
import com.medsearch.rag.ui.SearchViewModel
import kotlin.math.abs

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun HomeScreen(
    viewModel: SearchViewModel
) {
    val home by viewModel.home.collectAsState()
    val search by viewModel.search.collectAsState()
    val pdfRenderer = viewModel.pdfPageRenderer
    val focus = LocalFocusManager.current
    var query by rememberSaveable { mutableStateOf("") }

    // Página actualmente abierta en pantalla completa (null = ninguna)
    var fullScreenPage by remember { mutableStateOf<PageResult?>(null) }

    val folderPicker = rememberLauncherForActivityResult(
        contract = ActivityResultContracts.OpenDocumentTree()
    ) { uri ->
        try {
            uri?.let(viewModel::onFolderSelected)
        } catch (e: Exception) {
            android.util.Log.e("HomeScreen", "Error al seleccionar carpeta", e)
        }
    }

    Scaffold(
        topBar = {
            TopAppBar(
                title = {
                    Text(
                        "MedSearch",
                        style = MaterialTheme.typography.titleLarge.copy(fontWeight = FontWeight.SemiBold)
                    )
                },
                colors = TopAppBarDefaults.topAppBarColors(
                    containerColor = MaterialTheme.colorScheme.background
                )
            )
        },
        containerColor = MaterialTheme.colorScheme.background
    ) { padding ->
        Column(
            modifier = Modifier
                .fillMaxSize()
                .padding(padding)
        ) {
            Column(
                modifier = Modifier.padding(horizontal = 16.dp, vertical = 8.dp),
                verticalArrangement = Arrangement.spacedBy(8.dp)
            ) {
                Row(
                    horizontalArrangement = Arrangement.spacedBy(8.dp),
                    verticalAlignment = Alignment.CenterVertically
                ) {
                    OutlinedButton(
                        onClick = { folderPicker.launch(null) },
                        shape = RoundedCornerShape(12.dp),
                        modifier = Modifier.weight(1f)
                    ) {
                        Icon(Icons.Outlined.FolderOpen, contentDescription = null)
                        Spacer(Modifier.width(6.dp))
                        Text(home.folderName ?: "Carpeta", maxLines = 1)
                    }
                    OutlinedButton(
                        onClick = viewModel::startIndexing,
                        enabled = home.folderUri != null && !home.indexing.running,
                        shape = RoundedCornerShape(12.dp)
                    ) {
                        Icon(Icons.Outlined.PlayArrow, contentDescription = null)
                        Spacer(Modifier.width(6.dp))
                        Text(if (home.bookCount == 0) "Indexar" else "Reindexar")
                    }
                }

                if (home.indexing.running) {
                    val prog = home.indexing
                    Text(
                        "Indexando ${prog.currentBook ?: ""} — pág. ${prog.currentPage}/${prog.totalPages}",
                        style = MaterialTheme.typography.labelSmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant
                    )
                    LinearProgressIndicator(
                        progress = {
                            if (prog.totalPages == 0) 0f
                            else prog.currentPage.toFloat() / prog.totalPages.toFloat()
                        },
                        modifier = Modifier.fillMaxWidth()
                    )
                } else {
                    Text(
                        "${home.bookCount} libros · ${home.pageCount} páginas indexadas",
                        style = MaterialTheme.typography.labelSmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant
                    )
                }

                OutlinedTextField(
                    value = query,
                    onValueChange = { query = it },
                    leadingIcon = { Icon(Icons.Outlined.Search, contentDescription = null) },
                    placeholder = { Text("Buscar término clínico") },
                    keyboardOptions = KeyboardOptions(imeAction = ImeAction.Search),
                    keyboardActions = KeyboardActions(onSearch = {
                        focus.clearFocus()
                        viewModel.runSearch(query)
                    }),
                    singleLine = true,
                    modifier = Modifier.fillMaxWidth(),
                    shape = RoundedCornerShape(14.dp)
                )
            }

            HorizontalDivider()

            when (val s = search) {
                is SearchUiState.Idle -> CenterMessage("Busca un término para ver las páginas.")
                is SearchUiState.Searching -> CenterLoading()
                is SearchUiState.Empty -> CenterMessage("Sin resultados para \"${s.term}\".")
                is SearchUiState.Error -> CenterMessage(s.message)
                is SearchUiState.Results -> {
                    Text(
                        "${s.pages.size} páginas con \"${s.term}\" · toca una para ampliar",
                        style = MaterialTheme.typography.labelMedium,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                        modifier = Modifier.padding(horizontal = 16.dp, vertical = 8.dp)
                    )
                    LazyColumn(
                        modifier = Modifier.fillMaxSize(),
                        contentPadding = PaddingValues(bottom = 24.dp),
                        verticalArrangement = Arrangement.spacedBy(20.dp)
                    ) {
                        items(
                            items = s.pages,
                            key = { "${it.bookId}|${it.pageNumber}" }
                        ) { page ->
                            PdfPageItem(
                                page = page,
                                pdfRenderer = pdfRenderer,
                                onTap = { fullScreenPage = page }
                            )
                        }
                    }
                }
            }
        }
    }

    // Visor a pantalla completa con zoom
    fullScreenPage?.let { page ->
        FullScreenPageViewer(
            page = page,
            pdfRenderer = pdfRenderer,
            onClose = { fullScreenPage = null }
        )
    }
}

@Composable
private fun PdfPageItem(
    page: PageResult,
    pdfRenderer: PdfPageRenderer,
    onTap: () -> Unit
) {
    val context = LocalContext.current
    var bitmap by remember(page.bookId, page.pageNumber) { mutableStateOf<Bitmap?>(null) }
    var error by remember(page.bookId, page.pageNumber) { mutableStateOf<String?>(null) }

    LaunchedEffect(page.bookId, page.pageNumber) {
        when (val r = pdfRenderer.renderPage(context, page.bookUri, page.pageNumber)) {
            is PdfPageRenderer.RenderResult.Success -> bitmap = r.bitmap
            is PdfPageRenderer.RenderResult.Error -> error = r.message
        }
    }

    Column(modifier = Modifier.fillMaxWidth()) {
        Row(
            modifier = Modifier
                .fillMaxWidth()
                .padding(horizontal = 16.dp, vertical = 6.dp),
            verticalAlignment = Alignment.CenterVertically
        ) {
            Surface(
                color = MaterialTheme.colorScheme.primary,
                shape = RoundedCornerShape(50),
                modifier = Modifier.size(8.dp)
            ) {}
            Spacer(Modifier.width(8.dp))
            Text(
                page.bookTitle,
                style = MaterialTheme.typography.titleSmall,
                fontWeight = FontWeight.SemiBold,
                maxLines = 1,
                modifier = Modifier.weight(1f)
            )
            Text(
                "Página ${page.pageNumber}",
                style = MaterialTheme.typography.labelMedium,
                color = MaterialTheme.colorScheme.primary
            )
        }

        when {
            bitmap != null -> {
                Image(
                    bitmap = bitmap!!.asImageBitmap(),
                    contentDescription = "Página ${page.pageNumber} de ${page.bookTitle}",
                    modifier = Modifier
                        .fillMaxWidth()
                        .pointerInput(Unit) {
                            detectTapGestures(onTap = { onTap() })
                        },
                    contentScale = ContentScale.FillWidth
                )
            }
            error != null -> {
                Box(
                    modifier = Modifier
                        .fillMaxWidth()
                        .height(180.dp),
                    contentAlignment = Alignment.Center
                ) {
                    Text(
                        error!!,
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.error,
                        modifier = Modifier.padding(24.dp)
                    )
                }
            }
            else -> {
                Box(
                    modifier = Modifier
                        .fillMaxWidth()
                        .height(360.dp),
                    contentAlignment = Alignment.Center
                ) {
                    CircularProgressIndicator(
                        modifier = Modifier.size(28.dp),
                        strokeWidth = 2.dp
                    )
                }
            }
        }
    }
}

/**
 * Visor a pantalla completa con pinch-to-zoom, paneo y doble-tap.
 * Se abre como Dialog full-screen para cubrir todo y manejar back.
 */
@Composable
private fun FullScreenPageViewer(
    page: PageResult,
    pdfRenderer: PdfPageRenderer,
    onClose: () -> Unit
) {
    val context = LocalContext.current
    var bitmap by remember(page.bookId, page.pageNumber) { mutableStateOf<Bitmap?>(null) }
    var error by remember(page.bookId, page.pageNumber) { mutableStateOf<String?>(null) }

    LaunchedEffect(page.bookId, page.pageNumber) {
        when (val r = pdfRenderer.renderPage(context, page.bookUri, page.pageNumber)) {
            is PdfPageRenderer.RenderResult.Success -> bitmap = r.bitmap
            is PdfPageRenderer.RenderResult.Error -> error = r.message
        }
    }

    Dialog(
        onDismissRequest = onClose,
        properties = DialogProperties(
            usePlatformDefaultWidth = false,
            dismissOnBackPress = true,
            dismissOnClickOutside = false
        )
    ) {
        BackHandler(enabled = true) { onClose() }

        // Estado de zoom/paneo
        var scale by remember { mutableStateOf(1f) }
        var offsetX by remember { mutableStateOf(0f) }
        var offsetY by remember { mutableStateOf(0f) }

        Box(
            modifier = Modifier
                .fillMaxSize()
                .background(Color.Black)
        ) {
            when {
                bitmap != null -> {
                    Image(
                        bitmap = bitmap!!.asImageBitmap(),
                        contentDescription = "Página ${page.pageNumber} de ${page.bookTitle}",
                        contentScale = ContentScale.Fit,
                        modifier = Modifier
                            .fillMaxSize()
                            .pointerInput(Unit) {
                                detectTapGestures(
                                    onDoubleTap = {
                                        // Doble tap alterna entre 1x y 2.5x
                                        if (scale > 1f) {
                                            scale = 1f
                                            offsetX = 0f
                                            offsetY = 0f
                                        } else {
                                            scale = 2.5f
                                        }
                                    }
                                )
                            }
                            .pointerInput(Unit) {
                                detectTransformGestures { _, pan, zoom, _ ->
                                    scale = (scale * zoom).coerceIn(1f, 6f)
                                    if (scale > 1f) {
                                        offsetX += pan.x
                                        offsetY += pan.y
                                    } else {
                                        offsetX = 0f
                                        offsetY = 0f
                                    }
                                }
                            }
                            .graphicsLayerCompat(scale, offsetX, offsetY)
                    )
                }
                error != null -> {
                    Text(
                        error!!,
                        color = Color.White,
                        modifier = Modifier
                            .align(Alignment.Center)
                            .padding(32.dp)
                    )
                }
                else -> {
                    CircularProgressIndicator(
                        color = Color.White,
                        modifier = Modifier.align(Alignment.Center)
                    )
                }
            }

            // Cabecera: libro + página + cerrar
            Row(
                modifier = Modifier
                    .fillMaxWidth()
                    .background(Color.Black.copy(alpha = 0.45f))
                    .padding(horizontal = 12.dp, vertical = 10.dp),
                verticalAlignment = Alignment.CenterVertically
            ) {
                Column(modifier = Modifier.weight(1f)) {
                    Text(
                        page.bookTitle,
                        color = Color.White,
                        style = MaterialTheme.typography.titleSmall,
                        fontWeight = FontWeight.SemiBold,
                        maxLines = 1
                    )
                    Text(
                        "Página ${page.pageNumber} · pellizca o doble-toca para zoom",
                        color = Color.White.copy(alpha = 0.7f),
                        style = MaterialTheme.typography.labelSmall
                    )
                }
                IconButton(onClick = onClose) {
                    Icon(
                        Icons.Outlined.Close,
                        contentDescription = "Cerrar",
                        tint = Color.White
                    )
                }
            }
        }
    }
}

/**
 * Helper para aplicar transformación de escala/traslación.
 * (Separado para mantener el modifier chain legible.)
 */
private fun Modifier.graphicsLayerCompat(
    scale: Float,
    offsetX: Float,
    offsetY: Float
): Modifier = this.then(
    Modifier.graphicsLayer(
        scaleX = scale,
        scaleY = scale,
        translationX = offsetX,
        translationY = offsetY
    )
)

@Composable
private fun CenterMessage(message: String) {
    Box(
        modifier = Modifier.fillMaxSize(),
        contentAlignment = Alignment.Center
    ) {
        Text(
            message,
            style = MaterialTheme.typography.bodyLarge,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
            modifier = Modifier.padding(32.dp)
        )
    }
}

@Composable
private fun CenterLoading() {
    Box(
        modifier = Modifier.fillMaxSize(),
        contentAlignment = Alignment.Center
    ) {
        CircularProgressIndicator()
    }
}
