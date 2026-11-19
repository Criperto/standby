package com.criperto.standby.editor.sheets

import android.content.ClipData
import android.content.ClipboardManager
import android.content.Context
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ExperimentalLayoutApi
import androidx.compose.foundation.layout.FlowRow
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.rounded.Add
import androidx.compose.material.icons.rounded.ContentCopy
import androidx.compose.material.icons.rounded.ContentPaste
import androidx.compose.material.icons.rounded.Delete
import androidx.compose.material.icons.rounded.FileUpload
import androidx.compose.material.icons.rounded.FolderOpen
import androidx.compose.material.icons.rounded.Save
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Button
import androidx.compose.material3.ElevatedCard
import androidx.compose.material3.FilterChip
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.unit.dp
import com.criperto.standby.StandByApplication
import com.criperto.standby.core.AppJson
import com.criperto.standby.editor.EditorUiState
import com.criperto.standby.editor.EditorViewModel
import kotlinx.coroutines.launch

@OptIn(ExperimentalLayoutApi::class)
@Composable
fun PresetManagerSheet(
    app: StandByApplication,
    uiState: EditorUiState,
    viewModel: EditorViewModel,
    onMissingFontsDetected: (List<String>) -> Unit = {},
    modifier: Modifier = Modifier,
) {
    val context = LocalContext.current
    val scope = rememberCoroutineScope()
    val presetIds by app.presetRepository.presetIdsFlow.collectAsState()

    var statusText by remember { mutableStateOf<String?>(null) }
    var saveAsDialog by remember { mutableStateOf(false) }
    var pasteJsonDialog by remember { mutableStateOf(false) }

    val importFileLauncher = rememberLauncherForActivityResult(
        ActivityResultContracts.GetContent()
    ) { uri ->
        if (uri == null) return@rememberLauncherForActivityResult
        scope.launch {
            val bytes = runCatching {
                context.contentResolver.openInputStream(uri)?.use { it.readBytes() }
            }.getOrNull() ?: return@launch

            val isZip = bytes.size >= 4 && bytes[0] == 0x50.toByte() && bytes[1] == 0x4B.toByte()
            val res = if (isZip) {
                app.presetRepository.importStandbyZip(bytes)
            } else {
                app.presetRepository.importJsonOrCripertoShell(bytes.decodeToString())
            }
            res.onSuccess { (slug, report) ->
                viewModel.loadPresetIntoEditor(slug)
                statusText = "Importado '$slug' (${if (report.valid) "válido" else "con ajustes"})"
                if (report.missingFontsourceIds.isNotEmpty()) {
                    onMissingFontsDetected(report.missingFontsourceIds)
                }
            }.onFailure { err ->
                statusText = "Error al importar: ${err.message}"
            }
        }
    }

    val exportZipLauncher = rememberLauncherForActivityResult(
        ActivityResultContracts.CreateDocument("application/zip")
    ) { uri ->
        if (uri == null) return@rememberLauncherForActivityResult
        scope.launch {
            viewModel.saveCurrentPreset()
            app.presetRepository.exportStandbyZip(uiState.presetId)
                .onSuccess { zipBytes ->
                    runCatching {
                        val out = context.contentResolver.openOutputStream(uri)
                            ?: error("No se pudo abrir el archivo de destino")
                        out.use { it.write(zipBytes) }
                    }.onSuccess {
                        statusText = "Paquete .standby exportado (${zipBytes.size / 1024} KB)"
                    }.onFailure { ioErr ->
                        statusText = "Error escribiendo archivo: ${ioErr.message}"
                    }
                }
                .onFailure { err ->
                    statusText = "Error exportando .standby: ${err.message}"
                }
        }
    }

    Column(
        modifier = modifier
            .fillMaxSize()
            .padding(14.dp),
        verticalArrangement = Arrangement.spacedBy(10.dp),
    ) {
        FlowRow(
            horizontalArrangement = Arrangement.spacedBy(8.dp),
            verticalArrangement = Arrangement.spacedBy(8.dp),
        ) {
            Button(onClick = { viewModel.saveCurrentPreset() }) {
                Icon(Icons.Rounded.Save, contentDescription = null)
                Spacer(Modifier.width(4.dp))
                Text("Guardar")
            }
            OutlinedButton(onClick = { saveAsDialog = true }) {
                Icon(Icons.Rounded.Add, contentDescription = null)
                Spacer(Modifier.width(4.dp))
                Text("Guardar como...")
            }
            OutlinedButton(onClick = { importFileLauncher.launch("*/*") }) {
                Icon(Icons.Rounded.FolderOpen, contentDescription = null)
                Spacer(Modifier.width(4.dp))
                Text("Importar .json/.standby")
            }
            OutlinedButton(onClick = { pasteJsonDialog = true }) {
                Icon(Icons.Rounded.ContentPaste, contentDescription = null)
                Spacer(Modifier.width(4.dp))
                Text("Pegar JSON (IA PC)")
            }
            OutlinedButton(
                onClick = {
                    val json = AppJson.encodeToString(uiState.preset)
                    val cm = context.getSystemService(Context.CLIPBOARD_SERVICE) as? ClipboardManager
                    cm?.setPrimaryClip(ClipData.newPlainText("StandByPreset", json))
                    statusText = "JSON copiado al portapapeles"
                },
            ) {
                Icon(Icons.Rounded.ContentCopy, contentDescription = null)
                Spacer(Modifier.width(4.dp))
                Text("Copiar JSON")
            }
            OutlinedButton(
                onClick = { exportZipLauncher.launch("${uiState.presetId}.standby") },
            ) {
                Icon(Icons.Rounded.FileUpload, contentDescription = null)
                Spacer(Modifier.width(4.dp))
                Text("Exportar .standby")
            }
            OutlinedButton(
                onClick = {
                    scope.launch {
                        val count = app.presetRepository.restoreBuiltinPresets().size
                        viewModel.loadPresetIntoEditor(app.presetRepository.activePresetIdFlow.value)
                        statusText = "Presets de fábrica restaurados ($count disponibles)"
                    }
                },
            ) {
                Text("Restaurar fábrica")
            }
        }

        statusText?.let { msg ->
            Text(
                text = msg,
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.primary,
            )
        }

        LazyColumn(
            modifier = Modifier.fillMaxSize(),
            verticalArrangement = Arrangement.spacedBy(8.dp),
        ) {
            items(presetIds, key = { it }) { id ->
                val isCurrent = id == uiState.presetId
                ElevatedCard(modifier = Modifier.fillMaxWidth()) {
                    Row(
                        modifier = Modifier
                            .fillMaxWidth()
                            .padding(horizontal = 14.dp, vertical = 10.dp),
                        horizontalArrangement = Arrangement.SpaceBetween,
                        verticalAlignment = Alignment.CenterVertically,
                    ) {
                        FilterChip(
                            selected = isCurrent,
                            onClick = { viewModel.loadPresetIntoEditor(id) },
                            label = { Text(id) },
                        )
                        Row {
                            IconButton(
                                onClick = {
                                    scope.launch {
                                        val newSlug = app.presetRepository.duplicatePreset(id, "${id}_copia")
                                        viewModel.loadPresetIntoEditor(newSlug)
                                    }
                                },
                            ) {
                                Icon(Icons.Rounded.ContentCopy, contentDescription = "Duplicar")
                            }
                            if (id !in listOf("amoled_minimal", "standby_split")) {
                                IconButton(
                                    onClick = {
                                        scope.launch { app.presetRepository.deletePreset(id) }
                                    },
                                ) {
                                    Icon(
                                        Icons.Rounded.Delete,
                                        contentDescription = "Eliminar",
                                        tint = MaterialTheme.colorScheme.error,
                                    )
                                }
                            }
                        }
                    }
                }
            }
            item { Spacer(Modifier.height(16.dp)) }
        }
    }

    if (saveAsDialog) {
        var newName by remember { mutableStateOf("${uiState.preset.name} V2") }
        AlertDialog(
            onDismissRequest = { saveAsDialog = false },
            title = { Text("Guardar preset como...") },
            text = {
                OutlinedTextField(
                    value = newName,
                    onValueChange = { newName = it },
                    label = { Text("Nombre del nuevo preset") },
                    singleLine = true,
                    modifier = Modifier.fillMaxWidth(),
                )
            },
            confirmButton = {
                Button(
                    enabled = newName.isNotBlank(),
                    onClick = {
                        viewModel.saveCurrentPreset(asNewName = newName)
                        saveAsDialog = false
                    },
                ) {
                    Text("Guardar")
                }
            },
            dismissButton = {
                TextButton(onClick = { saveAsDialog = false }) { Text("Cancelar") }
            },
        )
    }

    if (pasteJsonDialog) {
        var rawBuf by remember { mutableStateOf("") }
        var customName by remember { mutableStateOf("") }
        AlertDialog(
            onDismissRequest = { pasteJsonDialog = false },
            title = { Text("Importar JSON (Preset o CripertoShell)") },
            text = {
                Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
                    OutlinedTextField(
                        value = customName,
                        onValueChange = { customName = it },
                        label = { Text("Nombre (opcional)") },
                        singleLine = true,
                        modifier = Modifier.fillMaxWidth(),
                    )
                    OutlinedTextField(
                        value = rawBuf,
                        onValueChange = { rawBuf = it },
                        label = { Text("Pegá aquí el JSON generado en PC") },
                        maxLines = 8,
                        modifier = Modifier.fillMaxWidth(),
                    )
                }
            },
            confirmButton = {
                Button(
                    enabled = rawBuf.isNotBlank(),
                    onClick = {
                        scope.launch {
                            app.presetRepository.importJsonOrCripertoShell(
                                rawJson = rawBuf,
                                desiredName = customName.takeIf { it.isNotBlank() },
                            ).onSuccess { (slug, report) ->
                                viewModel.loadPresetIntoEditor(slug)
                                statusText = "Importado '$slug'"
                                if (report.missingFontsourceIds.isNotEmpty()) {
                                    onMissingFontsDetected(report.missingFontsourceIds)
                                }
                            }.onFailure { err ->
                                statusText = "JSON inválido: ${err.message}"
                            }
                            pasteJsonDialog = false
                        }
                    },
                ) {
                    Text("Importar")
                }
            },
            dismissButton = {
                TextButton(onClick = { pasteJsonDialog = false }) { Text("Cancelar") }
            },
        )
    }
}
