package com.criperto.standby.ai

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.rounded.AutoFixHigh
import androidx.compose.material.icons.rounded.CloudDownload
import androidx.compose.material.icons.rounded.Delete
import androidx.compose.material3.Button
import androidx.compose.material3.ElevatedCard
import androidx.compose.material3.FilterChip
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.LinearProgressIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.Text
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
import com.criperto.standby.core.SceneObject
import kotlinx.coroutines.launch
import kotlin.math.roundToInt

@Composable
fun AiCutoutPanel(
    app: StandByApplication,
    selectedObject: SceneObject?,
    downloader: ModelDownloader,
    onCutoutGenerated: () -> Unit,
) {
    val context = LocalContext.current
    val scope = rememberCoroutineScope()
    val dlStatus by downloader.statusFlow.collectAsState()
    val editorUi by app.editorController.uiState.collectAsState()

    val currentModelId = selectedObject?.maskModelName ?: "birefnet-general-lite"
    val selectedSpec = remember(currentModelId) { AiModelCatalog.findById(currentModelId) }

    var runningStage by remember { mutableStateOf<String?>(null) }
    var feedbackText by remember { mutableStateOf<String?>(null) }

    Column(verticalArrangement = Arrangement.spacedBy(10.dp)) {
        Text(
            "Motor IA Local ONNX (Recorte y Presencia)",
            style = MaterialTheme.typography.titleSmall,
            color = MaterialTheme.colorScheme.primary,
        )
        Text(
            "Descarga a demanda y ejecuta el recorte una sola vez por wallpaper (queda cacheado en WebP + grilla Smart Depth 128×72).",
            style = MaterialTheme.typography.bodySmall,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
        )

        AiModelCatalog.models.forEach { spec ->
            val installed = spec.id in dlStatus.installedIds
            val isDownloadingThis = dlStatus.activeModelId == spec.id
            val isSelected = spec.isSegmentation && spec.id == selectedSpec.id

            ElevatedCard(modifier = Modifier.fillMaxWidth()) {
                Column(
                    modifier = Modifier.padding(12.dp),
                    verticalArrangement = Arrangement.spacedBy(6.dp),
                ) {
                    Row(
                        modifier = Modifier.fillMaxWidth(),
                        horizontalArrangement = Arrangement.SpaceBetween,
                        verticalAlignment = Alignment.CenterVertically,
                    ) {
                        Column(modifier = Modifier.weight(1f)) {
                            Text(spec.displayName, style = MaterialTheme.typography.bodyMedium)
                            Text(
                                "${spec.subtitle} · ~${spec.approxSizeMb} MB",
                                style = MaterialTheme.typography.labelSmall,
                                color = MaterialTheme.colorScheme.onSurfaceVariant,
                            )
                        }
                        Row(verticalAlignment = Alignment.CenterVertically) {
                            if (spec.isSegmentation) {
                                FilterChip(
                                    selected = isSelected,
                                    onClick = {
                                        if (selectedObject != null) {
                                            app.editorController.updateObjectImmediate(selectedObject.id) {
                                                it.copy(maskModelName = spec.id)
                                            }
                                        }
                                    },
                                    label = { Text(if (isSelected) "Activo" else "Elegir") },
                                )
                            }
                            Spacer(Modifier.width(6.dp))
                            if (installed) {
                                IconButton(onClick = { downloader.deleteModel(spec) }) {
                                    Icon(
                                        Icons.Rounded.Delete,
                                        contentDescription = "Borrar modelo",
                                        tint = MaterialTheme.colorScheme.error,
                                    )
                                }
                            } else if (isDownloadingThis) {
                                OutlinedButton(onClick = { downloader.cancelActiveDownload() }) {
                                    Text("Cancelar (${(dlStatus.progressFraction * 100).roundToInt()}%)")
                                }
                            } else {
                                OutlinedButton(
                                    enabled = dlStatus.activeModelId == null,
                                    onClick = { downloader.startDownload(spec) },
                                ) {
                                    Icon(Icons.Rounded.CloudDownload, contentDescription = null)
                                    Spacer(Modifier.width(4.dp))
                                    Text("Descargar")
                                }
                            }
                        }
                    }
                    if (isDownloadingThis) {
                        LinearProgressIndicator(
                            progress = { dlStatus.progressFraction },
                            modifier = Modifier.fillMaxWidth(),
                        )
                    }
                }
            }
        }

        dlStatus.errorMessage?.let { err ->
            Text(err, style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.error)
        }

        val wpPath = editorUi.preset.background.wallpaperUri
        val canRunCutout = !wpPath.isNullOrBlank() &&
            selectedSpec.id in dlStatus.installedIds &&
            runningStage == null

        Button(
            enabled = canRunCutout,
            onClick = {
                val path = wpPath ?: return@Button
                scope.launch {
                    runningStage = "Iniciando recorte IA..."
                    feedbackText = null
                    val invert = selectedObject?.invertMask ?: false
                    val choke = (selectedObject?.maskChoke ?: 1.5).toFloat()
                    val feather = (selectedObject?.maskFeather ?: 1.5).toFloat()
                    OnnxSegmentationEngine.generateCutoutAndCache(
                        context = context,
                        wallpaperPath = path,
                        modelSpec = selectedSpec,
                        invert = invert,
                        choke = choke,
                        smoothRadius = feather,
                        onStageUpdate = { runningStage = it },
                    ).onSuccess {
                        runningStage = null
                        feedbackText = "Recorte IA generado y cacheado con éxito (${selectedSpec.displayName})"
                        // Force editor object list pulse so EditorCanvas reloads cached mask
                        if (selectedObject != null) {
                            app.editorController.updateObjectImmediate(selectedObject.id) {
                                it.copy(
                                    isMask = true,
                                    maskModelName = selectedSpec.id,
                                    depthCutoutMode = if (it.depthCutoutMode == "none") "limit_50" else it.depthCutoutMode,
                                )
                            }
                        }
                        onCutoutGenerated()
                    }.onFailure { err ->
                        runningStage = null
                        feedbackText = "Error: ${err.message}"
                    }
                }
            },
            modifier = Modifier.fillMaxWidth(),
        ) {
            Icon(Icons.Rounded.AutoFixHigh, contentDescription = null)
            Spacer(Modifier.width(6.dp))
            Text(
                when {
                    wpPath.isNullOrBlank() -> "Primero elegí una imagen de Wallpaper arriba"
                    selectedSpec.id !in dlStatus.installedIds -> "Descargá '${selectedSpec.displayName}' para recortar"
                    else -> "Generar recorte IA del Wallpaper ahora"
                }
            )
        }

        if (runningStage != null) {
            LinearProgressIndicator(modifier = Modifier.fillMaxWidth())
            Text(
                runningStage!!,
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.primary,
            )
        }
        feedbackText?.let { msg ->
            Text(
                msg,
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.primary,
            )
        }
    }
}
