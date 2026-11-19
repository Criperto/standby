package com.criperto.standby.editor

import android.content.res.Configuration
import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.rememberScrollState
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.rounded.Redo
import androidx.compose.material.icons.automirrored.rounded.Undo
import androidx.compose.material.icons.rounded.CenterFocusStrong
import androidx.compose.material.icons.rounded.GridOn
import androidx.compose.material.icons.rounded.PlayArrow
import androidx.compose.material.icons.rounded.Save
import androidx.compose.material3.Button
import androidx.compose.material3.FilterChip
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.material3.Tab
import androidx.compose.material3.TabRow
import androidx.compose.material3.Text
import androidx.compose.material3.VerticalDivider
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalConfiguration
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import com.criperto.standby.StandByApplication
import com.criperto.standby.core.SceneObject
import com.criperto.standby.editor.canvas.EditorCanvas
import com.criperto.standby.editor.sheets.AddObjectSheet
import com.criperto.standby.editor.sheets.InspectorSheet
import com.criperto.standby.editor.sheets.LayersSheet
import com.criperto.standby.editor.sheets.PresetManagerSheet
import com.criperto.standby.editor.sheets.StateSimulatorSheet
import com.criperto.standby.standby.StandByActivity

@Composable
fun EditorScreen(
    app: StandByApplication,
    onOpenFontPicker: (SceneObject) -> Unit = {},
    onImportSvgForObject: (SceneObject) -> Unit = {},
    onMissingFontsDetected: (List<String>) -> Unit = {},
    aiCutoutPanel: @Composable (SceneObject?) -> Unit = {},
    modifier: Modifier = Modifier,
) {
    val context = LocalContext.current
    val viewModel = app.editorController
    val ui by viewModel.uiState.collectAsState()
    val isLandscape = LocalConfiguration.current.orientation == Configuration.ORIENTATION_LANDSCAPE

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
            onPreviewStandBy = {
                viewModel.saveCurrentPreset {
                    context.startActivity(StandByActivity.createIntent(context, manualLaunch = true))
                }
            },
        )
        HorizontalDivider()

        if (isLandscape) {
            Row(modifier = Modifier.weight(1f).fillMaxWidth()) {
                EditorCanvas(
                    app = app,
                    viewModel = viewModel,
                    uiState = ui,
                    modifier = Modifier.weight(0.55f).fillMaxHeight(),
                )
                VerticalDivider()
                Surface(
                    modifier = Modifier.weight(0.45f).fillMaxHeight(),
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
        } else {
            Column(modifier = Modifier.weight(1f).fillMaxWidth()) {
                EditorCanvas(
                    app = app,
                    viewModel = viewModel,
                    uiState = ui,
                    modifier = Modifier.weight(0.42f).fillMaxWidth(),
                )
                HorizontalDivider()
                Surface(
                    modifier = Modifier.weight(0.58f).fillMaxWidth(),
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

@Composable
private fun EditorTopToolbar(
    uiState: EditorUiState,
    viewModel: EditorViewModel,
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
            Text(
                text = uiState.preset.name + (if (uiState.dirty) " *" else ""),
                style = MaterialTheme.typography.titleSmall,
                maxLines = 1,
                overflow = TextOverflow.Ellipsis,
                modifier = Modifier.padding(end = 6.dp),
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
