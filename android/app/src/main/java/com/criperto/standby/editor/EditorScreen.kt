package com.criperto.standby.editor

import android.content.res.Configuration
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.animation.AnimatedVisibility
import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.rounded.Redo
import androidx.compose.material.icons.automirrored.rounded.Undo
import androidx.compose.material.icons.rounded.CenterFocusStrong
import androidx.compose.material.icons.rounded.GridOn
import androidx.compose.material.icons.rounded.PlayArrow
import androidx.compose.material.icons.rounded.Save
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Button
import androidx.compose.material3.FilledTonalButton
import androidx.compose.material3.FilterChip
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.material3.Tab
import androidx.compose.material3.TabRow
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.VerticalDivider
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalConfiguration
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.criperto.standby.StandByApplication
import com.criperto.standby.ai.AiCutoutPanel
import com.criperto.standby.core.SceneObject
import com.criperto.standby.editor.canvas.EditorCanvas
import com.criperto.standby.editor.sheets.AddObjectSheet
import com.criperto.standby.editor.sheets.InspectorSheet
import com.criperto.standby.editor.sheets.LayersSheet
import com.criperto.standby.editor.sheets.PresetManagerSheet
import com.criperto.standby.editor.sheets.StateSimulatorSheet
import com.criperto.standby.fonts.FontPickerSheet
import com.criperto.standby.fonts.SvgAssetManager
import com.criperto.standby.standby.StandByActivity
import kotlinx.coroutines.launch

@Composable
fun EditorScreen(
    app: StandByApplication,
    modifier: Modifier = Modifier,
) {
    val context = LocalContext.current
    val scope = rememberCoroutineScope()
    val viewModel = app.editorController
    val ui by viewModel.uiState.collectAsState()
    val isLandscape = LocalConfiguration.current.orientation == Configuration.ORIENTATION_LANDSCAPE

    var isPanelCollapsed by rememberSaveable { mutableStateOf(false) }
    var fontPickerTarget by remember { mutableStateOf<SceneObject?>(null) }
    var svgPickerTargetId by remember { mutableStateOf<String?>(null) }
    var missingFontIds by remember { mutableStateOf<List<String>>(emptyList()) }

    val svgLauncher = rememberLauncherForActivityResult(
        ActivityResultContracts.GetContent()
    ) { uri ->
        val objId = svgPickerTargetId
        svgPickerTargetId = null
        if (uri == null || objId == null) return@rememberLauncherForActivityResult
        scope.launch {
            SvgAssetManager.importSvgFromUri(context, uri).onSuccess { file ->
                app.assetCache.invalidateFontAndSvgCache()
                viewModel.updateObjectImmediate(objId) { it.copy(svgSource = file.name) }
            }
        }
    }

    val onOpenFontPicker: (SceneObject) -> Unit = { obj -> fontPickerTarget = obj }
    val onImportSvgForObject: (SceneObject) -> Unit = { obj ->
        svgPickerTargetId = obj.id
        svgLauncher.launch("*/*")
    }
    val onMissingFontsDetected: (List<String>) -> Unit = { ids ->
        missingFontIds = ids
    }
    val aiCutoutPanel: @Composable (SceneObject?) -> Unit = { obj ->
        AiCutoutPanel(
            app = app,
            selectedObject = obj,
            downloader = app.modelDownloader,
            onCutoutGenerated = {},
        )
    }

    DisposableEffect(Unit) {
        app.signalProvider.acquireRenderConsumer()
        onDispose {
            viewModel.flushPendingHistory()
            app.signalProvider.releaseRenderConsumer()
        }
    }

    Column(modifier = modifier.fillMaxSize()) {
        EditorTopToolbar(
            uiState = ui,
            viewModel = viewModel,
            isPanelCollapsed = isPanelCollapsed,
            onToggleCollapse = { isPanelCollapsed = !isPanelCollapsed },
            onPreviewStandBy = {
                viewModel.saveCurrentPreset {
                    context.startActivity(StandByActivity.createIntent(context, manualLaunch = true))
                }
            },
        )
        HorizontalDivider()

        if (isLandscape) {
            Row(modifier = Modifier.weight(1f).fillMaxWidth()) {
                Box(
                    modifier = Modifier
                        .weight(if (isPanelCollapsed) 1f else 0.56f)
                        .fillMaxHeight(),
                ) {
                    EditorCanvas(
                        app = app,
                        viewModel = viewModel,
                        uiState = ui,
                        modifier = Modifier.fillMaxSize(),
                    )
                    FilledTonalButton(
                        onClick = { isPanelCollapsed = !isPanelCollapsed },
                        shape = RoundedCornerShape(topStart = 18.dp, bottomStart = 18.dp),
                        contentPadding = PaddingValues(horizontal = 12.dp, vertical = 10.dp),
                        modifier = Modifier
                            .align(Alignment.CenterEnd)
                            .padding(end = 2.dp),
                    ) {
                        Text(
                            text = if (isPanelCollapsed) "< Panel" else ">",
                            fontSize = 15.sp,
                            fontWeight = FontWeight.Bold,
                        )
                    }
                }

                AnimatedVisibility(visible = !isPanelCollapsed) {
                    Row(modifier = Modifier.fillMaxHeight().width(390.dp)) {
                        VerticalDivider()
                        Surface(
                            modifier = Modifier.fillMaxSize(),
                            color = MaterialTheme.colorScheme.surfaceContainerLow,
                        ) {
                            EditorBottomOrSideWorkspace(
                                app = app,
                                uiState = ui,
                                viewModel = viewModel,
                                onOpenFontPicker = onOpenFontPicker,
                                onImportSvgForObject = onImportSvgForObject,
                                onMissingFontsDetected = onMissingFontsDetected,
                                aiCutoutPanel = aiCutoutPanel,
                            )
                        }
                    }
                }
            }
        } else {
            Column(modifier = Modifier.weight(1f).fillMaxWidth()) {
                Box(
                    modifier = Modifier
                        .weight(if (isPanelCollapsed) 1f else 0.45f)
                        .fillMaxWidth(),
                ) {
                    EditorCanvas(
                        app = app,
                        viewModel = viewModel,
                        uiState = ui,
                        modifier = Modifier.fillMaxSize(),
                    )
                    FilledTonalButton(
                        onClick = { isPanelCollapsed = !isPanelCollapsed },
                        shape = RoundedCornerShape(18.dp),
                        contentPadding = PaddingValues(horizontal = 14.dp, vertical = 6.dp),
                        modifier = Modifier
                            .align(Alignment.BottomEnd)
                            .padding(10.dp),
                    ) {
                        Text(
                            text = if (isPanelCollapsed) "< Mostrar Opciones" else "< Ocultar Panel",
                            fontSize = 13.sp,
                            fontWeight = FontWeight.Bold,
                        )
                    }
                }

                if (!isPanelCollapsed) {
                    HorizontalDivider()
                    Surface(
                        modifier = Modifier
                            .weight(0.55f)
                            .fillMaxWidth(),
                        color = MaterialTheme.colorScheme.surfaceContainerLow,
                    ) {
                        EditorBottomOrSideWorkspace(
                            app = app,
                            uiState = ui,
                            viewModel = viewModel,
                            onOpenFontPicker = onOpenFontPicker,
                            onImportSvgForObject = onImportSvgForObject,
                            onMissingFontsDetected = onMissingFontsDetected,
                            aiCutoutPanel = aiCutoutPanel,
                        )
                    }
                }
            }
        }
    }

    fontPickerTarget?.let { targetObj ->
        FontPickerSheet(
            app = app,
            targetObject = targetObj,
            fontsourceRepository = app.fontsourceRepository,
            onDismiss = { fontPickerTarget = null },
            onApplyFont = { family, fileName, sourceId ->
                viewModel.updateObjectImmediate(targetObj.id) {
                    it.copy(
                        customFontFamily = family,
                        fontFileName = fileName,
                        fontSourceId = sourceId,
                        useSystemFont = fileName == null && sourceId == null,
                    )
                }
                fontPickerTarget = null
            },
        )
    }

    if (missingFontIds.isNotEmpty()) {
        AlertDialog(
            onDismissRequest = { missingFontIds = emptyList() },
            title = { Text("Fuentes Fontsource faltantes") },
            text = {
                Text(
                    "El preset importado referencia fuentes de Fontsource que no están descargadas aún: ${missingFontIds.joinToString(", ")}. ¿Querés descargarlas ahora automáticamente?"
                )
            },
            confirmButton = {
                Button(
                    onClick = {
                        val toDownload = missingFontIds
                        missingFontIds = emptyList()
                        scope.launch {
                            for (fid in toDownload) {
                                app.fontsourceRepository.downloadFont(fid)
                            }
                            app.assetCache.invalidateFontAndSvgCache()
                        }
                    },
                ) {
                    Text("Descargar todas")
                }
            },
            dismissButton = {
                TextButton(onClick = { missingFontIds = emptyList() }) {
                    Text("Omitir")
                }
            },
        )
    }
}

@Composable
private fun EditorTopToolbar(
    uiState: EditorUiState,
    viewModel: EditorViewModel,
    isPanelCollapsed: Boolean,
    onToggleCollapse: () -> Unit,
    onPreviewStandBy: () -> Unit,
) {
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .horizontalScroll(rememberScrollState())
            .padding(horizontal = 10.dp, vertical = 6.dp),
        horizontalArrangement = Arrangement.SpaceBetween,
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Row(
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.spacedBy(4.dp),
        ) {
            FilterChip(
                selected = isPanelCollapsed,
                onClick = onToggleCollapse,
                label = {
                    Text(
                        text = if (isPanelCollapsed) "> Panel" else "< Lienzo",
                        fontWeight = FontWeight.Bold,
                    )
                },
            )
            Text(
                text = uiState.preset.name + (if (uiState.dirty) " *" else ""),
                style = MaterialTheme.typography.titleSmall,
                maxLines = 1,
                overflow = TextOverflow.Ellipsis,
                modifier = Modifier.padding(horizontal = 4.dp),
            )
            IconButton(
                enabled = uiState.undoCount > 0,
                onClick = { viewModel.undo() },
            ) {
                Icon(Icons.AutoMirrored.Rounded.Undo, contentDescription = "Deshacer")
            }
            IconButton(
                enabled = uiState.redoCount > 0,
                onClick = { viewModel.redo() },
            ) {
                Icon(Icons.AutoMirrored.Rounded.Redo, contentDescription = "Rehacer")
            }
            FilterChip(
                selected = uiState.snapEnabled,
                onClick = { viewModel.toggleSnap() },
                label = { Text("Imán") },
            )
            IconButton(onClick = { viewModel.toggleCompositionGuides() }) {
                Icon(
                    Icons.Rounded.GridOn,
                    contentDescription = "Guías",
                    tint = if (uiState.showCompositionGuides) {
                        MaterialTheme.colorScheme.primary
                    } else {
                        MaterialTheme.colorScheme.onSurfaceVariant
                    },
                )
            }
            if (uiState.zoom != 1f || uiState.panX != 0f || uiState.panY != 0f) {
                IconButton(onClick = { viewModel.resetViewport() }) {
                    Icon(Icons.Rounded.CenterFocusStrong, contentDescription = "Resetear zoom")
                }
            }
        }

        Row(
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.spacedBy(6.dp),
        ) {
            IconButton(onClick = onPreviewStandBy) {
                Icon(
                    Icons.Rounded.PlayArrow,
                    contentDescription = "Probar en StandBy",
                    tint = MaterialTheme.colorScheme.primary,
                )
            }
            Button(onClick = { viewModel.saveCurrentPreset() }) {
                Icon(Icons.Rounded.Save, contentDescription = null)
                Spacer(Modifier.width(4.dp))
                Text("Guardar")
            }
        }
    }
}

@Composable
private fun EditorBottomOrSideWorkspace(
    app: StandByApplication,
    uiState: EditorUiState,
    viewModel: EditorViewModel,
    onOpenFontPicker: (SceneObject) -> Unit,
    onImportSvgForObject: (SceneObject) -> Unit,
    onMissingFontsDetected: (List<String>) -> Unit,
    aiCutoutPanel: @Composable (SceneObject?) -> Unit,
) {
    Column(modifier = Modifier.fillMaxSize()) {
        TabRow(selectedTabIndex = uiState.activeSheet.ordinal) {
            EditorSheetTab.entries.forEach { tab ->
                Tab(
                    selected = uiState.activeSheet == tab,
                    onClick = { viewModel.setActiveSheet(tab) },
                    text = { Text(tab.label, maxLines = 1, overflow = TextOverflow.Ellipsis) },
                )
            }
        }

        Box(modifier = Modifier.weight(1f).fillMaxWidth()) {
            when (uiState.activeSheet) {
                EditorSheetTab.INSPECTOR -> InspectorSheet(
                    app = app,
                    uiState = uiState,
                    viewModel = viewModel,
                    onOpenFontPicker = onOpenFontPicker,
                    onImportSvgForObject = onImportSvgForObject,
                    aiCutoutPanel = aiCutoutPanel,
                )
                EditorSheetTab.LAYERS -> LayersSheet(
                    uiState = uiState,
                    viewModel = viewModel,
                )
                EditorSheetTab.ADD -> AddObjectSheet(
                    onAddObject = { typeId -> viewModel.addObject(typeId) },
                )
                EditorSheetTab.SIMULATOR -> StateSimulatorSheet(
                    uiState = uiState,
                    viewModel = viewModel,
                )
                EditorSheetTab.PRESETS -> PresetManagerSheet(
                    app = app,
                    uiState = uiState,
                    viewModel = viewModel,
                    onMissingFontsDetected = onMissingFontsDetected,
                )
            }
        }
    }
}
