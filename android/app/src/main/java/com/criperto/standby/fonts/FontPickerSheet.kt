package com.criperto.standby.fonts

import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.rounded.Check
import androidx.compose.material.icons.rounded.CloudDownload
import androidx.compose.material.icons.rounded.FolderOpen
import androidx.compose.material.icons.rounded.Refresh
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Button
import androidx.compose.material3.ElevatedCard
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.LinearProgressIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Tab
import androidx.compose.material3.TabRow
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
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
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import java.io.File

@Composable
fun FontPickerSheet(
    app: StandByApplication,
    targetObject: SceneObject,
    fontsourceRepository: FontsourceRepository,
    onDismiss: () -> Unit,
    onApplyFont: (
        customFontFamily: String,
        fontFileName: String?,
        fontSourceId: String?,
    ) -> Unit,
) {
    val context = LocalContext.current
    val scope = rememberCoroutineScope()

    var tab by remember { mutableIntStateOf(0) } // 0 = Sistema, 1 = Fontsource, 2 = Importadas
    var search by remember { mutableStateOf("") }
    var busyMessage by remember { mutableStateOf<String?>(null) }
    var errorMessage by remember { mutableStateOf<String?>(null) }

    var systemFonts by remember { mutableStateOf<List<SystemFontEntry>>(emptyList()) }
    var importedFiles by remember { mutableStateOf<List<File>>(emptyList()) }

    val catalog by fontsourceRepository.catalogFlow.collectAsState()
    val downloadedIds by fontsourceRepository.downloadedIdsFlow.collectAsState()

    LaunchedEffect(Unit) {
        systemFonts = withContext(Dispatchers.IO) { SystemFontsScanner.scanDeviceFonts() }
        importedFiles = withContext(Dispatchers.IO) { CustomFontImporter.listImportedFonts(context) }
        fontsourceRepository.refreshDownloadedSet()
    }

    val importFontLauncher = rememberLauncherForActivityResult(
        ActivityResultContracts.GetContent()
    ) { uri ->
        if (uri == null) return@rememberLauncherForActivityResult
        scope.launch {
            busyMessage = "Importando archivo TTF/OTF..."
            errorMessage = null
            CustomFontImporter.importFontFromUri(context, uri)
                .onSuccess { file ->
                    app.assetCache.invalidateFontAndSvgCache()
                    importedFiles = CustomFontImporter.listImportedFonts(context)
                    val fam = SystemFontsScanner.normalizeFamilyName(file.name)
                    busyMessage = null
                    onApplyFont(fam, file.name, null)
                }
                .onFailure { err ->
                    busyMessage = null
                    errorMessage = err.message ?: "Archivo de fuente inválido"
                }
        }
    }

    AlertDialog(
        onDismissRequest = onDismiss,
        title = {
            Row(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.SpaceBetween,
                verticalAlignment = Alignment.CenterVertically,
            ) {
                Text("Tipografía · ${targetObject.name}")
                if (tab == 1) {
                    IconButton(
                        onClick = {
                            scope.launch {
                                busyMessage = "Actualizando catálogo Fontsource..."
                                fontsourceRepository.fetchFullCatalog()
                                busyMessage = null
                            }
                        },
                    ) {
                        Icon(Icons.Rounded.Refresh, contentDescription = "Cargar catálogo completo")
                    }
                }
            }
        },
        text = {
            Column(
                modifier = Modifier
                    .fillMaxWidth()
                    .heightIn(max = 460.dp),
                verticalArrangement = Arrangement.spacedBy(8.dp),
            ) {
                TabRow(selectedTabIndex = tab) {
                    Tab(selected = tab == 0, onClick = { tab = 0 }, text = { Text("Sistema") })
                    Tab(selected = tab == 1, onClick = { tab = 1 }, text = { Text("Fontsource") })
                    Tab(selected = tab == 2, onClick = { tab = 2 }, text = { Text("Archivos") })
                }

                OutlinedTextField(
                    value = search,
                    onValueChange = { search = it },
                    label = { Text("Buscar fuente...") },
                    singleLine = true,
                    modifier = Modifier.fillMaxWidth(),
                )

                if (busyMessage != null) {
                    LinearProgressIndicator(modifier = Modifier.fillMaxWidth())
                    Text(
                        busyMessage!!,
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.primary,
                    )
                }
                errorMessage?.let { err ->
                    Text(
                        err,
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.error,
                    )
                }

                when (tab) {
                    0 -> {
                        val filtered = remember(systemFonts, search) {
                            if (search.isBlank()) systemFonts
                            else systemFonts.filter { it.familyName.contains(search, ignoreCase = true) }
                        }
                        LazyColumn(
                            modifier = Modifier.weight(1f),
                            verticalArrangement = Arrangement.spacedBy(6.dp),
                        ) {
                            items(filtered, key = { it.familyName + it.fileName }) { entry ->
                                ElevatedCard(
                                    modifier = Modifier
                                        .fillMaxWidth()
                                        .clickable {
                                            app.assetCache.invalidateFontAndSvgCache()
                                            onApplyFont(
                                                entry.familyName,
                                                entry.absolutePath.takeIf { it.isNotBlank() },
                                                null,
                                            )
                                        },
                                ) {
                                    Row(
                                        modifier = Modifier
                                            .fillMaxWidth()
                                            .padding(horizontal = 12.dp, vertical = 10.dp),
                                        horizontalArrangement = Arrangement.SpaceBetween,
                                        verticalAlignment = Alignment.CenterVertically,
                                    ) {
                                        Column(modifier = Modifier.weight(1f)) {
                                            Text(entry.familyName, style = MaterialTheme.typography.titleSmall)
                                            if (entry.fileName.isNotBlank()) {
                                                Text(
                                                    entry.fileName,
                                                    style = MaterialTheme.typography.labelSmall,
                                                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                                                )
                                            }
                                        }
                                        Text("21:45", style = MaterialTheme.typography.titleMedium)
                                    }
                                }
                            }
                        }
                    }

                    1 -> {
                        val filtered = remember(catalog, search) {
                            if (search.isBlank()) catalog
                            else catalog.filter {
                                it.family.contains(search, ignoreCase = true) ||
                                    it.category.contains(search, ignoreCase = true)
                            }
                        }
                        LazyColumn(
                            modifier = Modifier.weight(1f),
                            verticalArrangement = Arrangement.spacedBy(6.dp),
                        ) {
                            items(filtered, key = { it.id }) { item ->
                                val isDownloaded = item.id in downloadedIds
                                ElevatedCard(modifier = Modifier.fillMaxWidth()) {
                                    Row(
                                        modifier = Modifier
                                            .fillMaxWidth()
                                            .padding(horizontal = 12.dp, vertical = 8.dp),
                                        horizontalArrangement = Arrangement.SpaceBetween,
                                        verticalAlignment = Alignment.CenterVertically,
                                    ) {
                                        Column(modifier = Modifier.weight(1f)) {
                                            Text(item.family, style = MaterialTheme.typography.titleSmall)
                                            Text(
                                                "${item.category} · ${if (isDownloaded) "Descargada" else "Online API"}",
                                                style = MaterialTheme.typography.labelSmall,
                                                color = MaterialTheme.colorScheme.onSurfaceVariant,
                                            )
                                        }
                                        Button(
                                            onClick = {
                                                scope.launch {
                                                    busyMessage = "Descargando ${item.family}..."
                                                    errorMessage = null
                                                    fontsourceRepository.downloadFont(
                                                        fontId = item.id,
                                                        preferredWeight = targetObject.fontWeight,
                                                    ).onSuccess { file ->
                                                        app.assetCache.invalidateFontAndSvgCache()
                                                        busyMessage = null
                                                        onApplyFont(item.family, file.name, item.id)
                                                    }.onFailure { err ->
                                                        busyMessage = null
                                                        errorMessage = err.message
                                                    }
                                                }
                                            },
                                        ) {
                                            Icon(
                                                if (isDownloaded) Icons.Rounded.Check else Icons.Rounded.CloudDownload,
                                                contentDescription = null,
                                            )
                                            Spacer(Modifier.width(4.dp))
                                            Text(if (isDownloaded) "Usar" else "Bajar")
                                        }
                                    }
                                }
                            }
                        }
                    }

                    2 -> {
                        OutlinedButton(
                            onClick = { importFontLauncher.launch("*/*") },
                            modifier = Modifier.fillMaxWidth(),
                        ) {
                            Icon(Icons.Rounded.FolderOpen, contentDescription = null)
                            Spacer(Modifier.width(6.dp))
                            Text("Importar archivo .TTF / .OTF del teléfono")
                        }
                        LazyColumn(
                            modifier = Modifier.weight(1f),
                            verticalArrangement = Arrangement.spacedBy(6.dp),
                        ) {
                            items(importedFiles, key = { it.name }) { file ->
                                val fam = SystemFontsScanner.normalizeFamilyName(file.name)
                                ElevatedCard(
                                    modifier = Modifier
                                        .fillMaxWidth()
                                        .clickable {
                                            app.assetCache.invalidateFontAndSvgCache()
                                            onApplyFont(fam, file.name, null)
                                        },
                                ) {
                                    Row(
                                        modifier = Modifier
                                            .fillMaxWidth()
                                            .padding(12.dp),
                                        horizontalArrangement = Arrangement.SpaceBetween,
                                        verticalAlignment = Alignment.CenterVertically,
                                    ) {
                                        Column {
                                            Text(fam, style = MaterialTheme.typography.titleSmall)
                                            Text(
                                                file.name,
                                                style = MaterialTheme.typography.labelSmall,
                                                color = MaterialTheme.colorScheme.onSurfaceVariant,
                                            )
                                        }
                                        Text("Usar", color = MaterialTheme.colorScheme.primary)
                                    }
                                }
                            }
                        }
                    }
                }
            }
        },
        confirmButton = {
            TextButton(onClick = onDismiss) { Text("Cerrar") }
        },
    )
}
