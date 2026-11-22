package com.criperto.standby.editor.sheets

import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.horizontalScroll
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
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.AssistChip
import androidx.compose.material3.Button
import androidx.compose.material3.FilterChip
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Slider
import androidx.compose.material3.Switch
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.unit.dp
import com.criperto.standby.StandByApplication
import com.criperto.standby.core.ActionsConfig
import com.criperto.standby.core.GradientSpec
import com.criperto.standby.core.GradientStop
import com.criperto.standby.core.SceneObject
import com.criperto.standby.editor.EditorUiState
import com.criperto.standby.editor.EditorViewModel
import com.criperto.standby.editor.InspectorSection
import java.io.File
import kotlin.math.roundToInt

@OptIn(ExperimentalLayoutApi::class)
@Composable
fun InspectorSheet(
    app: StandByApplication,
    uiState: EditorUiState,
    viewModel: EditorViewModel,
    onOpenFontPicker: (SceneObject) -> Unit = {},
    onImportSvgForObject: (SceneObject) -> Unit = {},
    aiCutoutPanel: @Composable (SceneObject?) -> Unit = {},
    modifier: Modifier = Modifier,
) {
    val context = LocalContext.current
    val primary = uiState.primarySelectedObject
    val shaders by app.shaderRegistry.shadersFlow.collectAsState()
    var inspectorNotice by remember { mutableStateOf<String?>(null) }

    val wallpaperPicker = rememberLauncherForActivityResult(
        ActivityResultContracts.GetContent()
    ) { uri ->
        if (uri == null) return@rememberLauncherForActivityResult
        runCatching {
            val dir = File(context.filesDir, "wallpapers").apply { mkdirs() }
            val dst = File(dir, "wp_${System.currentTimeMillis()}.jpg")
            val input = context.contentResolver.openInputStream(uri)
                ?: error("No se pudo abrir la imagen seleccionada")
            input.use { inp ->
                dst.outputStream().use { out -> inp.copyTo(out) }
            }
            viewModel.updateBackgroundDebounced { it.copy(mode = "wallpaper", wallpaperUri = dst.absolutePath) }
        }.onSuccess {
            inspectorNotice = "Wallpaper importado y aplicado"
        }.onFailure { err ->
            inspectorNotice = "Error importando wallpaper: ${err.message}"
        }
    }

    val agslImporter = rememberLauncherForActivityResult(
        ActivityResultContracts.GetContent()
    ) { uri ->
        if (uri == null) return@rememberLauncherForActivityResult
        runCatching {
            val src = context.contentResolver.openInputStream(uri)?.bufferedReader()?.use { it.readText() }
                ?: error("No se pudo leer el archivo .agsl")
            val name = uri.lastPathSegment?.substringBeforeLast('.') ?: "custom_agsl"
            app.shaderRegistry.importCustomShader(name, src).getOrThrow().also { desc ->
                if (primary != null) {
                    viewModel.updateObjectImmediate(primary.id) { it.copy(shaderId = desc.id) }
                }
            }
        }.onSuccess { desc ->
            inspectorNotice = "Shader AGSL '${desc.displayName}' compilado y aplicado"
        }.onFailure { err ->
            inspectorNotice = "Error en shader AGSL: ${err.message}"
        }
    }

    Column(
        modifier = modifier
            .fillMaxSize()
            .padding(horizontal = 12.dp, vertical = 8.dp),
    ) {
        Row(
            modifier = Modifier
                .fillMaxWidth()
                .horizontalScroll(rememberScrollState()),
            horizontalArrangement = Arrangement.spacedBy(6.dp),
        ) {
            InspectorSection.entries.forEach { sec ->
                FilterChip(
                    selected = uiState.inspectorSection == sec,
                    onClick = { viewModel.setInspectorSection(sec) },
                    label = { Text(sec.label) },
                )
            }
        }

        Spacer(Modifier.height(6.dp))

        inspectorNotice?.let { msg ->
            Text(
                text = msg,
                style = MaterialTheme.typography.bodySmall,
                color = if (msg.startsWith("Error")) MaterialTheme.colorScheme.error else MaterialTheme.colorScheme.primary,
                modifier = Modifier.padding(horizontal = 4.dp, vertical = 2.dp),
            )
        }

        Column(
            modifier = Modifier
                .fillMaxSize()
                .verticalScroll(rememberScrollState()),
            verticalArrangement = Arrangement.spacedBy(10.dp),
        ) {
            if (primary == null && uiState.inspectorSection != InspectorSection.MASK_BG) {
                Text(
                    "Seleccioná un objeto en el lienzo o en la pestaña Capas para editar sus propiedades.",
                    style = MaterialTheme.typography.bodyMedium,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                    modifier = Modifier.padding(12.dp),
                )
            } else {
                when (uiState.inspectorSection) {
                    InspectorSection.TRANSFORM -> if (primary != null) {
                        TransformSection(primary, uiState, viewModel)
                    }
                    InspectorSection.STYLE -> if (primary != null) {
                        StyleSection(primary, app, viewModel)
                    }
                    InspectorSection.TEXT -> if (primary != null) {
                        TextSection(
                            obj = primary,
                            viewModel = viewModel,
                            onOpenFontPicker = { onOpenFontPicker(primary) },
                            onImportSvg = { onImportSvgForObject(primary) },
                        )
                    }
                    InspectorSection.SHADERS -> if (primary != null) {
                        ShadersSection(
                            obj = primary,
                            shaders = shaders.map { it.id to it.displayName },
                            viewModel = viewModel,
                            onImportAgsl = { agslImporter.launch("*/*") },
                        )
                    }
                    InspectorSection.MASK_BG -> {
                        MaskAndBackgroundSection(
                            obj = primary,
                            uiState = uiState,
                            viewModel = viewModel,
                            onPickWallpaper = { wallpaperPicker.launch("image/*") },
                            aiCutoutPanel = aiCutoutPanel,
                        )
                    }
                    InspectorSection.EXPRESSIONS -> if (primary != null) {
                        ExpressionsSection(primary, viewModel)
                    }
                }
            }
            Spacer(Modifier.height(20.dp))
        }
    }
}

@OptIn(ExperimentalLayoutApi::class)
@Composable
private fun TransformSection(
    obj: SceneObject,
    uiState: EditorUiState,
    viewModel: EditorViewModel,
) {
    var nameBuf by remember(obj.id, obj.name) { mutableStateOf(obj.name) }

    OutlinedTextField(
        value = nameBuf,
        onValueChange = {
            nameBuf = it
            viewModel.updateObjectDebounced(obj.id) { o -> o.copy(name = it) }
        },
        label = { Text("Nombre (${obj.objType})") },
        singleLine = true,
        modifier = Modifier.fillMaxWidth(),
    )

    InspectorSlider(
        label = "Posición X: ${(obj.posXRatio * 100).roundToInt()}%",
        value = obj.posXRatio.toFloat(),
        range = 0f..1f,
        onChange = { v -> viewModel.updateObjectDebounced(obj.id) { it.copy(posXRatio = v.toDouble()) } },
    )
    InspectorSlider(
        label = "Posición Y: ${(obj.posYRatio * 100).roundToInt()}%",
        value = obj.posYRatio.toFloat(),
        range = 0f..1f,
        onChange = { v -> viewModel.updateObjectDebounced(obj.id) { it.copy(posYRatio = v.toDouble()) } },
    )
    InspectorSlider(
        label = "Ancho base: ${obj.baseWidth.roundToInt()} px",
        value = obj.baseWidth.toFloat(),
        range = 24f..2400f,
        onChange = { v ->
            viewModel.updateObjectDebounced(obj.id) {
                it.copy(
                    baseWidth = v.toDouble(),
                    groupWidth = if (it.objType == "group") v.toDouble() else it.groupWidth,
                )
            }
        },
    )
    InspectorSlider(
        label = "Alto base: ${obj.baseHeight.roundToInt()} px",
        value = obj.baseHeight.toFloat(),
        range = 24f..1080f,
        onChange = { v ->
            viewModel.updateObjectDebounced(obj.id) {
                it.copy(
                    baseHeight = v.toDouble(),
                    groupHeight = if (it.objType == "group") v.toDouble() else it.groupHeight,
                )
            }
        },
    )
    InspectorSlider(
        label = "Rotación: ${obj.rotation.roundToInt()}°",
        value = obj.rotation.toFloat(),
        range = 0f..360f,
        onChange = { v -> viewModel.updateObjectDebounced(obj.id) { it.copy(rotation = v.toDouble()) } },
    )
    InspectorSlider(
        label = "Escala uniforme: ${"%.2f".format(obj.uniformScale)}x",
        value = obj.uniformScale.toFloat(),
        range = 0.2f..3.0f,
        onChange = { v -> viewModel.updateObjectDebounced(obj.id) { it.copy(uniformScale = v.toDouble()) } },
    )
    InspectorSlider(
        label = "Opacidad: ${(obj.elementOpacity * 100).roundToInt()}%",
        value = obj.elementOpacity.toFloat(),
        range = 0.05f..1f,
        onChange = { v -> viewModel.updateObjectDebounced(obj.id) { it.copy(elementOpacity = v.toDouble()) } },
    )

    HorizontalDivider()
    Text(
        "Pivote con conservación visual (${"%.2f".format(obj.pivotX)}, ${"%.2f".format(obj.pivotY)})",
        style = MaterialTheme.typography.labelLarge,
        color = MaterialTheme.colorScheme.primary,
    )
    val pivotPresets = listOf(
        Triple(0.0, 0.0, "TL"),
        Triple(0.5, 0.0, "TC"),
        Triple(1.0, 0.0, "TR"),
        Triple(0.0, 0.5, "ML"),
        Triple(0.5, 0.5, "C"),
        Triple(1.0, 0.5, "MR"),
        Triple(0.0, 1.0, "BL"),
        Triple(0.5, 1.0, "BC"),
        Triple(1.0, 1.0, "BR"),
    )
    FlowRow(horizontalArrangement = Arrangement.spacedBy(6.dp)) {
        pivotPresets.forEach { (px, py, label) ->
            val active = kotlin.math.abs(obj.pivotX - px) < 0.05 && kotlin.math.abs(obj.pivotY - py) < 0.05
            FilterChip(
                selected = active,
                onClick = { viewModel.setPivotKeepVisualPosition(px, py) },
                label = { Text(label) },
            )
        }
    }

    HorizontalDivider()
    Text(
        "Autoalineación en pantalla",
        style = MaterialTheme.typography.labelLarge,
        color = MaterialTheme.colorScheme.primary,
    )
    FlowRow(horizontalArrangement = Arrangement.spacedBy(6.dp)) {
        listOf(
            "left" to "Izq",
            "hcenter" to "Centro H",
            "right" to "Der",
            "top" to "Arriba",
            "vcenter" to "Centro V",
            "bottom" to "Abajo",
            "center" to "Centro Total",
        ).forEach { (anchor, label) ->
            AssistChip(
                onClick = { viewModel.alignPrimaryToScreen(anchor) },
                label = { Text(label) },
            )
        }
    }

    if (uiState.selectedIds.size >= 2) {
        HorizontalDivider()
        Text(
            "Alinear / Distribuir selección (${uiState.selectedIds.size})",
            style = MaterialTheme.typography.labelLarge,
            color = MaterialTheme.colorScheme.primary,
        )
        FlowRow(horizontalArrangement = Arrangement.spacedBy(6.dp)) {
            listOf(
                "align_left" to "Alin. Izq",
                "align_center_x" to "Alin. Centro H",
                "align_right" to "Alin. Der",
                "align_top" to "Alin. Arriba",
                "align_center_y" to "Alin. Centro V",
                "align_bottom" to "Alin. Abajo",
                "distribute_h" to "Distribuir H",
                "distribute_v" to "Distribuir V",
            ).forEach { (mode, label) ->
                AssistChip(
                    onClick = { viewModel.alignMultipleSelected(mode) },
                    label = { Text(label) },
                )
            }
        }
    }

    if (obj.objType == "group") {
        HorizontalDivider()
        Text(
            "Auto-Layout del Grupo",
            style = MaterialTheme.typography.labelLarge,
            color = MaterialTheme.colorScheme.primary,
        )
        FlowRow(horizontalArrangement = Arrangement.spacedBy(6.dp)) {
            listOf(
                "none" to "Manual",
                "row" to "Fila H",
                "column" to "Columna V",
                "grid" to "Grilla 2×2",
                "radial" to "Radial",
            ).forEach { (mode, label) ->
                FilterChip(
                    selected = obj.autoLayout == mode,
                    onClick = {
                        viewModel.applyGroupAutoLayout(obj.id, mode, obj.autoLayoutGap, obj.autoLayoutAlign)
                    },
                    label = { Text(label) },
                )
            }
        }
        InspectorSlider(
            label = "Espaciado Auto-Layout: ${obj.autoLayoutGap.roundToInt()} px",
            value = obj.autoLayoutGap.toFloat(),
            range = 0f..120f,
            onChange = { gap ->
                viewModel.applyGroupAutoLayout(obj.id, obj.autoLayout, gap.toDouble(), obj.autoLayoutAlign)
            },
        )
    }
}

@OptIn(ExperimentalLayoutApi::class)
@Composable
private fun StyleSection(
    obj: SceneObject,
    app: StandByApplication,
    viewModel: EditorViewModel,
) {
    Text(
        "Color Material You / Personalizado",
        style = MaterialTheme.typography.labelLarge,
        color = MaterialTheme.colorScheme.primary,
    )
    val paletteChoices = listOf(
        "primary" to "Primary",
        "onPrimary" to "OnPrimary",
        "primaryContainer" to "PrimaryCont",
        "secondary" to "Secondary",
        "tertiary" to "Tertiary",
        "surface" to "Surface",
        "surfaceContainer" to "SurfaceCont",
        "onSurface" to "OnSurface",
        "white" to "Blanco",
        "black" to "Negro",
    )
    FlowRow(horizontalArrangement = Arrangement.spacedBy(6.dp)) {
        paletteChoices.forEach { (token, label) ->
            FilterChip(
                selected = !obj.isCustomColor && obj.textColorChoice == token,
                onClick = {
                    viewModel.updateObjectImmediate(obj.id) {
                        it.copy(isCustomColor = false, textColorChoice = token)
                    }
                },
                label = { Text(label) },
            )
        }
    }

    var hexBuf by remember(obj.id, obj.customHex) { mutableStateOf(obj.customHex) }
    Row(
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(8.dp),
    ) {
        OutlinedTextField(
            value = hexBuf,
            onValueChange = { hexBuf = it },
            label = { Text("Color Hex (#RRGGBB)") },
            singleLine = true,
            modifier = Modifier.weight(1f),
        )
        Button(
            onClick = {
                viewModel.updateObjectImmediate(obj.id) {
                    it.copy(isCustomColor = true, customHex = hexBuf.trim())
                }
            },
        ) {
            Text("Aplicar Hex")
        }
    }

    HorizontalDivider()
    val grad = obj.gradient ?: GradientSpec()
    Row(
        modifier = Modifier.fillMaxWidth(),
        horizontalArrangement = Arrangement.SpaceBetween,
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Text("Gradiente 4 Modos (GPU)", style = MaterialTheme.typography.titleSmall)
        Switch(
            checked = grad.enabled,
            onCheckedChange = { en ->
                viewModel.updateObjectImmediate(obj.id) {
                    it.copy(gradient = grad.copy(enabled = en))
                }
            },
        )
    }
    if (grad.enabled) {
        FlowRow(horizontalArrangement = Arrangement.spacedBy(6.dp)) {
            listOf(
                "linear" to "Lineal",
                "radial" to "Radial",
                "conic" to "Cónico",
                "mesh" to "Malla 4-pt",
            ).forEach { (gType, label) ->
                FilterChip(
                    selected = grad.gradType == gType,
                    onClick = {
                        viewModel.updateObjectImmediate(obj.id) {
                            it.copy(gradient = grad.copy(gradType = gType))
                        }
                    },
                    label = { Text(label) },
                )
            }
        }
        Row(horizontalArrangement = Arrangement.spacedBy(6.dp)) {
            listOf("primary" to "tertiary", "secondary" to "primary", "tertiary" to "surface")
                .forEach { (c0, c1) ->
                    AssistChip(
                        onClick = {
                            viewModel.updateObjectImmediate(obj.id) {
                                it.copy(
                                    gradient = grad.copy(
                                        stops = listOf(
                                            GradientStop(pos = 0.0, colorChoice = c0),
                                            GradientStop(pos = 1.0, colorChoice = c1),
                                        )
                                    )
                                )
                            }
                        },
                        label = { Text("$c0 → $c1") },
                    )
                }
        }
    }

    if (obj.objType == "shape") {
        HorizontalDivider()
        Text(
            "Geometría de Figura Paramétrica",
            style = MaterialTheme.typography.labelLarge,
            color = MaterialTheme.colorScheme.primary,
        )
        FlowRow(horizontalArrangement = Arrangement.spacedBy(6.dp)) {
            listOf(
                "rectangle" to "Rectángulo",
                "capsule" to "Cápsula",
                "circle" to "Círculo/Arco",
                "polygon" to "Polígono",
                "star" to "Estrella",
            ).forEach { (st, label) ->
                FilterChip(
                    selected = obj.shapeType == st,
                    onClick = { viewModel.updateObjectImmediate(obj.id) { it.copy(shapeType = st) } },
                    label = { Text(label) },
                )
            }
        }
        InspectorSlider(
            label = "Radio esquinas: ${obj.radiusUniform.roundToInt()} px",
            value = obj.radiusUniform.toFloat(),
            range = 0f..160f,
            onChange = { r -> viewModel.updateObjectDebounced(obj.id) { it.copy(radiusUniform = r.toDouble()) } },
        )
        if (obj.shapeType == "polygon") {
            InspectorSlider(
                label = "Lados polígono: ${obj.polygonSides}",
                value = obj.polygonSides.toFloat(),
                range = 3f..12f,
                onChange = { s ->
                    viewModel.updateObjectDebounced(obj.id) { it.copy(polygonSides = s.roundToInt()) }
                },
            )
        }
        if (obj.shapeType == "star") {
            InspectorSlider(
                label = "Puntas estrella: ${obj.starPoints}",
                value = obj.starPoints.toFloat(),
                range = 3f..12f,
                onChange = { s ->
                    viewModel.updateObjectDebounced(obj.id) { it.copy(starPoints = s.roundToInt()) }
                },
            )
            InspectorSlider(
                label = "Radio interno estrella: ${"%.2f".format(obj.starRatio)}",
                value = obj.starRatio.toFloat(),
                range = 0.15f..0.85f,
                onChange = { r ->
                    viewModel.updateObjectDebounced(obj.id) { it.copy(starRatio = r.toDouble()) }
                },
            )
        }
        if (obj.shapeType == "circle") {
            InspectorSlider(
                label = "Barrido arco: ${obj.circleSweep.roundToInt()}°",
                value = obj.circleSweep.toFloat(),
                range = 10f..360f,
                onChange = { sw ->
                    viewModel.updateObjectDebounced(obj.id) { it.copy(circleSweep = sw.toDouble()) }
                },
            )
            InspectorSlider(
                label = "Grosor anillo (0=lleno): ${"%.2f".format(obj.circleRatio)}",
                value = obj.circleRatio.toFloat(),
                range = 0f..0.9f,
                onChange = { cr ->
                    viewModel.updateObjectDebounced(obj.id) { it.copy(circleRatio = cr.toDouble()) }
                },
            )
        }
    }

    if (obj.objType == "wiz_button" || obj.objType == "wiz_device" || obj.objType == "smart_device") {
        val smartUi by app.smartHomeController.uiState.collectAsState()
        HorizontalDivider()
        Text(
            "Personalización Total Botón Smart Home",
            style = MaterialTheme.typography.labelLarge,
            color = MaterialTheme.colorScheme.primary,
        )
        Text("Diseño visual", style = MaterialTheme.typography.labelMedium)
        FlowRow(horizontalArrangement = Arrangement.spacedBy(6.dp)) {
            listOf(
                "pill_slider" to "Barra Google Home",
                "tile" to "Tarjeta HomeKit",
                "compact" to "Compacto",
                "minimal" to "Minimalista",
            ).forEach { (st, label) ->
                FilterChip(
                    selected = obj.wizStyle == st,
                    onClick = { viewModel.updateObjectImmediate(obj.id) { it.copy(wizStyle = st) } },
                    label = { Text(label) },
                )
            }
        }
        InspectorSlider(
            label = "Radio esquinas: ${obj.radiusUniform.roundToInt()} px",
            value = obj.radiusUniform.toFloat(),
            range = 0f..120f,
            onChange = { r -> viewModel.updateObjectDebounced(obj.id) { it.copy(radiusUniform = r.toDouble()) } },
        )

        Text("Dispositivo y Acción", style = MaterialTheme.typography.labelMedium)
        FlowRow(horizontalArrangement = Arrangement.spacedBy(6.dp)) {
            val deviceChoices = buildList {
                add("primary" to "Principal")
                add("all" to "Todos")
                smartUi.devices.forEach { d -> add(d.id to d.name) }
            }
            deviceChoices.forEach { (devId, label) ->
                FilterChip(
                    selected = (obj.wizDeviceId ?: "primary") == devId,
                    onClick = { viewModel.updateObjectImmediate(obj.id) { it.copy(wizDeviceId = devId) } },
                    label = { Text(label) },
                )
            }
        }
        FlowRow(horizontalArrangement = Arrangement.spacedBy(6.dp)) {
            listOf(
                "toggle" to "Alternar ON/OFF",
                "on" to "Encender",
                "off" to "Apagar",
                "scene" to "Escena Luz Diurna",
            ).forEach { (act, label) ->
                FilterChip(
                    selected = (obj.wizAction ?: "toggle") == act,
                    onClick = {
                        viewModel.updateObjectImmediate(obj.id) {
                            it.copy(
                                wizAction = act,
                                wizSceneId = if (act == "scene" && it.wizSceneId == null) 12 else it.wizSceneId,
                            )
                        }
                    },
                    label = { Text(label) },
                )
            }
        }

        Text("Color Fondo Tarjeta (Material You / Hex)", style = MaterialTheme.typography.labelMedium)
        FlowRow(horizontalArrangement = Arrangement.spacedBy(6.dp)) {
            paletteChoices.forEach { (token, label) ->
                FilterChip(
                    selected = !obj.wizIsCustomBgColor && obj.wizBgColorChoice == token,
                    onClick = {
                        viewModel.updateObjectImmediate(obj.id) {
                            it.copy(wizIsCustomBgColor = false, wizBgColorChoice = token)
                        }
                    },
                    label = { Text(label) },
                )
            }
        }
        var bgHexBuf by remember(obj.id, obj.wizCustomBgHex) { mutableStateOf(obj.wizCustomBgHex) }
        Row(
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.spacedBy(8.dp),
        ) {
            OutlinedTextField(
                value = bgHexBuf,
                onValueChange = { bgHexBuf = it },
                label = { Text("Hex Fondo (#RRGGBB)") },
                singleLine = true,
                modifier = Modifier.weight(1f),
            )
            Button(
                onClick = {
                    viewModel.updateObjectImmediate(obj.id) {
                        it.copy(wizIsCustomBgColor = true, wizCustomBgHex = bgHexBuf.trim())
                    }
                },
            ) { Text("Aplicar") }
        }
        InspectorSlider(
            label = "Opacidad Fondo: ${(obj.wizBgAlpha * 100).roundToInt()}%",
            value = obj.wizBgAlpha.toFloat(),
            range = 0f..1f,
            onChange = { a -> viewModel.updateObjectDebounced(obj.id) { it.copy(wizBgAlpha = a.toDouble()) } },
        )

        Text("Color Barra Activa (Material You / Hex)", style = MaterialTheme.typography.labelMedium)
        FlowRow(horizontalArrangement = Arrangement.spacedBy(6.dp)) {
            paletteChoices.forEach { (token, label) ->
                FilterChip(
                    selected = !obj.wizIsCustomBarColor && obj.wizBarColorChoice == token,
                    onClick = {
                        viewModel.updateObjectImmediate(obj.id) {
                            it.copy(wizIsCustomBarColor = false, wizBarColorChoice = token)
                        }
                    },
                    label = { Text(label) },
                )
            }
        }
        var barHexBuf by remember(obj.id, obj.wizCustomBarHex) { mutableStateOf(obj.wizCustomBarHex) }
        Row(
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.spacedBy(8.dp),
        ) {
            OutlinedTextField(
                value = barHexBuf,
                onValueChange = { barHexBuf = it },
                label = { Text("Hex Barra (#RRGGBB)") },
                singleLine = true,
                modifier = Modifier.weight(1f),
            )
            Button(
                onClick = {
                    viewModel.updateObjectImmediate(obj.id) {
                        it.copy(wizIsCustomBarColor = true, wizCustomBarHex = barHexBuf.trim())
                    }
                },
            ) { Text("Aplicar") }
        }
        InspectorSlider(
            label = "Opacidad Barra Activa: ${(obj.wizBarAlpha * 100).roundToInt()}%",
            value = obj.wizBarAlpha.toFloat(),
            range = 0f..1f,
            onChange = { a -> viewModel.updateObjectDebounced(obj.id) { it.copy(wizBarAlpha = a.toDouble()) } },
        )
        Row(
            modifier = Modifier.fillMaxWidth(),
            horizontalArrangement = Arrangement.SpaceBetween,
            verticalAlignment = Alignment.CenterVertically,
        ) {
            Text("Teñir barra con color vivo de la lámpara")
            Switch(
                checked = obj.wizUseLampColor,
                onCheckedChange = { en ->
                    viewModel.updateObjectImmediate(obj.id) { it.copy(wizUseLampColor = en) }
                },
            )
        }

        Text("Color Porcentaje / Acento (Material You / Hex)", style = MaterialTheme.typography.labelMedium)
        FlowRow(horizontalArrangement = Arrangement.spacedBy(6.dp)) {
            paletteChoices.forEach { (token, label) ->
                FilterChip(
                    selected = !obj.wizIsCustomAccentColor && obj.wizAccentColorChoice == token,
                    onClick = {
                        viewModel.updateObjectImmediate(obj.id) {
                            it.copy(wizIsCustomAccentColor = false, wizAccentColorChoice = token)
                        }
                    },
                    label = { Text(label) },
                )
            }
        }
        var accHexBuf by remember(obj.id, obj.wizCustomAccentHex) { mutableStateOf(obj.wizCustomAccentHex) }
        Row(
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.spacedBy(8.dp),
        ) {
            OutlinedTextField(
                value = accHexBuf,
                onValueChange = { accHexBuf = it },
                label = { Text("Hex Acento (#RRGGBB)") },
                singleLine = true,
                modifier = Modifier.weight(1f),
            )
            Button(
                onClick = {
                    viewModel.updateObjectImmediate(obj.id) {
                        it.copy(wizIsCustomAccentColor = true, wizCustomAccentHex = accHexBuf.trim())
                    }
                },
            ) { Text("Aplicar") }
        }
    }

    HorizontalDivider()
    Row(
        modifier = Modifier.fillMaxWidth(),
        horizontalArrangement = Arrangement.SpaceBetween,
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Text("Borde / Trazo")
        Switch(
            checked = obj.strokeEnabled,
            onCheckedChange = { en -> viewModel.updateObjectImmediate(obj.id) { it.copy(strokeEnabled = en) } },
        )
    }
    if (obj.strokeEnabled) {
        InspectorSlider(
            label = "Grosor borde: ${"%.1f".format(obj.strokeWidth)} px",
            value = obj.strokeWidth.toFloat(),
            range = 1f..32f,
            onChange = { w -> viewModel.updateObjectDebounced(obj.id) { it.copy(strokeWidth = w.toDouble()) } },
        )
    }

    Row(
        modifier = Modifier.fillMaxWidth(),
        horizontalArrangement = Arrangement.SpaceBetween,
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Text("Sombra paralela")
        Switch(
            checked = obj.shadowEnabled,
            onCheckedChange = { en -> viewModel.updateObjectImmediate(obj.id) { it.copy(shadowEnabled = en) } },
        )
    }
    if (obj.shadowEnabled) {
        InspectorSlider(
            label = "Difuminado sombra: ${(obj.shadowBlur * 100).roundToInt()}%",
            value = obj.shadowBlur.toFloat(),
            range = 0f..1f,
            onChange = { b -> viewModel.updateObjectDebounced(obj.id) { it.copy(shadowBlur = b.toDouble()) } },
        )
        InspectorSlider(
            label = "Offset Y sombra: ${obj.shadowOffsetY.roundToInt()} px",
            value = obj.shadowOffsetY.toFloat(),
            range = -40f..40f,
            onChange = { y -> viewModel.updateObjectDebounced(obj.id) { it.copy(shadowOffsetY = y.toDouble()) } },
        )
    }

    HorizontalDivider()
    Text(
        "Perspectiva 3D",
        style = MaterialTheme.typography.labelLarge,
        color = MaterialTheme.colorScheme.primary,
    )
    InspectorSlider(
        label = "Inclinación X (Pitch): ${obj.perspectiveX.roundToInt()}°",
        value = obj.perspectiveX.toFloat(),
        range = -60f..60f,
        onChange = { v -> viewModel.updateObjectDebounced(obj.id) { it.copy(perspectiveX = v.toDouble()) } },
    )
    InspectorSlider(
        label = "Inclinación Y (Yaw): ${obj.perspectiveY.roundToInt()}°",
        value = obj.perspectiveY.toFloat(),
        range = -60f..60f,
        onChange = { v -> viewModel.updateObjectDebounced(obj.id) { it.copy(perspectiveY = v.toDouble()) } },
    )
}

@OptIn(ExperimentalLayoutApi::class)
@Composable
private fun TextSection(
    obj: SceneObject,
    viewModel: EditorViewModel,
    onOpenFontPicker: () -> Unit,
    onImportSvg: () -> Unit,
) {
    Row(
        modifier = Modifier.fillMaxWidth(),
        horizontalArrangement = Arrangement.SpaceBetween,
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Column(modifier = Modifier.weight(1f)) {
            Text("Tipografía: ${obj.customFontFamily}", style = MaterialTheme.typography.titleSmall)
            Text(
                text = when {
                    !obj.fontFileName.isNullOrBlank() -> "Archivo: ${obj.fontFileName}"
                    !obj.fontSourceId.isNullOrBlank() -> "Fontsource: ${obj.fontSourceId}"
                    else -> "Fuente del sistema"
                },
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
        }
        Button(onClick = onOpenFontPicker) {
            Text("Elegir fuente...")
        }
    }

    InspectorSlider(
        label = "Tamaño de fuente: ${obj.baseFontSize.roundToInt()} sp",
        value = obj.baseFontSize.toFloat(),
        range = 14f..420f,
        onChange = { fs -> viewModel.updateObjectDebounced(obj.id) { it.copy(baseFontSize = fs.toDouble()) } },
    )
    InspectorSlider(
        label = "Peso (Weight): ${obj.fontWeight}",
        value = obj.fontWeight.toFloat(),
        range = 100f..900f,
        onChange = { fw ->
            val stepped = ((fw / 100f).roundToInt() * 100).coerceIn(100, 900)
            viewModel.updateObjectDebounced(obj.id) { it.copy(fontWeight = stepped) }
        },
    )
    InspectorSlider(
        label = "Espaciado letras: ${"%.1f".format(obj.letterSpacing)} px",
        value = obj.letterSpacing.toFloat(),
        range = -10f..40f,
        onChange = { ls -> viewModel.updateObjectDebounced(obj.id) { it.copy(letterSpacing = ls.toDouble()) } },
    )

    Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
        listOf("left" to "Izquierda", "center" to "Centro", "right" to "Derecha").forEach { (al, label) ->
            FilterChip(
                selected = obj.textAlign == al,
                onClick = { viewModel.updateObjectImmediate(obj.id) { it.copy(textAlign = al) } },
                label = { Text(label) },
            )
        }
        FilterChip(
            selected = obj.fontItalic,
            onClick = { viewModel.updateObjectImmediate(obj.id) { it.copy(fontItalic = !obj.fontItalic) } },
            label = { Text("Cursiva") },
        )
    }

    if (obj.objType == "custom_text") {
        var textBuf by remember(obj.id, obj.customContent) { mutableStateOf(obj.customContent) }
        OutlinedTextField(
            value = textBuf,
            onValueChange = {
                textBuf = it
                viewModel.updateObjectDebounced(obj.id) { o -> o.copy(customContent = it) }
            },
            label = { Text("Plantilla (\$HOUR:\$MINUTE, \$BATTERY%, \$TITLE, \${expr})") },
            modifier = Modifier.fillMaxWidth(),
        )
        FlowRow(horizontalArrangement = Arrangement.spacedBy(6.dp)) {
            listOf(
                "\$HOUR:\$MINUTE",
                "\$BATTERY%",
                "\$TITLE",
                "\$ARTIST",
                "\$WEATHER_TEMP",
                "\$NOTIF_COUNT",
                "\$LAST_NOTIF",
                "\${regexReplace(\$TITLE, \"(?i)\\\\s*\\\\(official.*\\\\)\", \"\")}",
            ).forEach { token ->
                AssistChip(
                    onClick = {
                        val next = if (textBuf.isBlank()) token else "$textBuf $token"
                        textBuf = next
                        viewModel.updateObjectImmediate(obj.id) { it.copy(customContent = next) }
                    },
                    label = { Text(token) },
                )
            }
        }
    }

    if (obj.objType == "icon_svg") {
        HorizontalDivider()
        Text("Icono Material You o archivo .SVG", style = MaterialTheme.typography.labelLarge)
        FlowRow(horizontalArrangement = Arrangement.spacedBy(6.dp)) {
            listOf(
                "bolt", "bedtime", "lightbulb", "music", "sunny", "cloud", "battery",
                "tv", "ac_unit", "thermostat", "fan", "power", "wifi", "home", "alarm", "timer", "notifications"
            ).forEach { ic ->
                FilterChip(
                    selected = obj.svgSource.isNullOrBlank() && obj.iconName == ic,
                    onClick = {
                        viewModel.updateObjectImmediate(obj.id) {
                            it.copy(iconName = ic, svgSource = null)
                        }
                    },
                    label = { Text(ic) },
                )
            }
        }
        OutlinedButton(onClick = onImportSvg) {
            Text(
                if (obj.svgSource.isNullOrBlank()) {
                    "Importar archivo .SVG personalizado..."
                } else {
                    "SVG: ${obj.svgSource} (Cambiar...)"
                }
            )
        }
    }

    if (obj.objType == "wiz_button" || obj.objType == "wiz_device" || obj.objType == "smart_device") {
        HorizontalDivider()
        Text(
            "Textos e Iconos del Botón Smart Home",
            style = MaterialTheme.typography.labelLarge,
            color = MaterialTheme.colorScheme.primary,
        )
        var titleBuf by remember(obj.id, obj.wizTitleTemplate) { mutableStateOf(obj.wizTitleTemplate) }
        var subBuf by remember(obj.id, obj.wizSubtitleTemplate) { mutableStateOf(obj.wizSubtitleTemplate) }

        OutlinedTextField(
            value = titleBuf,
            onValueChange = {
                titleBuf = it
                viewModel.updateObjectDebounced(obj.id) { o -> o.copy(wizTitleTemplate = it) }
            },
            label = { Text("Título (\$WIZ_NAME, \$WIZ_ROOM, \$WIZ_MODE, \$WIZ_ON, \$WIZ_DIM)") },
            singleLine = true,
            modifier = Modifier.fillMaxWidth(),
        )
        OutlinedTextField(
            value = subBuf,
            onValueChange = {
                subBuf = it
                viewModel.updateObjectDebounced(obj.id) { o -> o.copy(wizSubtitleTemplate = it) }
            },
            label = { Text("Subtítulo (\$WIZ_ROOM, \$WIZ_MODE, vacío para ocultar)") },
            singleLine = true,
            modifier = Modifier.fillMaxWidth(),
        )
        Row(
            modifier = Modifier.fillMaxWidth(),
            horizontalArrangement = Arrangement.SpaceBetween,
            verticalAlignment = Alignment.CenterVertically,
        ) {
            Text("Mostrar % / °C / OFF a la derecha")
            Switch(
                checked = obj.wizShowPercent,
                onCheckedChange = { en ->
                    viewModel.updateObjectImmediate(obj.id) { it.copy(wizShowPercent = en) }
                },
            )
        }
        Row(
            modifier = Modifier.fillMaxWidth(),
            horizontalArrangement = Arrangement.SpaceBetween,
            verticalAlignment = Alignment.CenterVertically,
        ) {
            Text("Mostrar ícono del dispositivo")
            Switch(
                checked = obj.wizShowIcon,
                onCheckedChange = { en ->
                    viewModel.updateObjectImmediate(obj.id) { it.copy(wizShowIcon = en) }
                },
            )
        }
        if (obj.wizShowIcon) {
            FlowRow(horizontalArrangement = Arrangement.spacedBy(6.dp)) {
                listOf("lightbulb", "tv", "ac_unit", "thermostat", "fan", "power", "bedtime", "home").forEach { ic ->
                    FilterChip(
                        selected = obj.iconName == ic,
                        onClick = {
                            viewModel.updateObjectImmediate(obj.id) { it.copy(iconName = ic) }
                        },
                        label = { Text(ic) },
                    )
                }
            }
        }
    }
}

@OptIn(ExperimentalLayoutApi::class)
@Composable
private fun ShadersSection(
    obj: SceneObject,
    shaders: List<Pair<String, String>>,
    viewModel: EditorViewModel,
    onImportAgsl: () -> Unit,
) {
    Row(
        modifier = Modifier.fillMaxWidth(),
        horizontalArrangement = Arrangement.SpaceBetween,
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Column(modifier = Modifier.weight(1f)) {
            Text("Liquid Glass (Refracción AGSL)", style = MaterialTheme.typography.titleSmall)
            Text(
                "Port nativo del shader liquid_glass de tu lockscreen (refracción + dispersión + bisel)",
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
        }
        val isLiquid = obj.liquidGlassEnabled || obj.shaderId == "liquid_glass"
        Switch(
            checked = isLiquid,
            onCheckedChange = { en ->
                viewModel.updateObjectImmediate(obj.id) {
                    it.copy(
                        liquidGlassEnabled = en,
                        shaderId = if (en) "liquid_glass" else null,
                    )
                }
            },
        )
    }

    if (obj.liquidGlassEnabled || obj.shaderId == "liquid_glass") {
        InspectorSlider(
            label = "Bisel (Bezel): ${obj.liquidBezel.roundToInt()} px",
            value = obj.liquidBezel.toFloat(),
            range = 1f..64f,
            onChange = { v -> viewModel.updateObjectDebounced(obj.id) { it.copy(liquidBezel = v.toDouble()) } },
        )
        InspectorSlider(
            label = "Espesor / Refracción: ${obj.liquidThickness.roundToInt()} px",
            value = obj.liquidThickness.toFloat(),
            range = 0f..80f,
            onChange = { v -> viewModel.updateObjectDebounced(obj.id) { it.copy(liquidThickness = v.toDouble()) } },
        )
        InspectorSlider(
            label = "Índice de Refracción (IOR): ${"%.2f".format(obj.liquidIor)}",
            value = obj.liquidIor.toFloat(),
            range = 1.0f..2.4f,
            onChange = { v -> viewModel.updateObjectDebounced(obj.id) { it.copy(liquidIor = v.toDouble()) } },
        )
        InspectorSlider(
            label = "Dispersión cromática: ${"%.3f".format(obj.liquidDispersion)}",
            value = obj.liquidDispersion.toFloat(),
            range = 0f..0.25f,
            onChange = { v -> viewModel.updateObjectDebounced(obj.id) { it.copy(liquidDispersion = v.toDouble()) } },
        )
        InspectorSlider(
            label = "Escarchado (Frost): ${"%.2f".format(obj.liquidFrost)}",
            value = obj.liquidFrost.toFloat(),
            range = 0f..4f,
            onChange = { v -> viewModel.updateObjectDebounced(obj.id) { it.copy(liquidFrost = v.toDouble()) } },
        )
        InspectorSlider(
            label = "Brillo especular borde (Rim): ${"%.2f".format(obj.liquidRim)}",
            value = obj.liquidRim.toFloat(),
            range = 0f..1.5f,
            onChange = { v -> viewModel.updateObjectDebounced(obj.id) { it.copy(liquidRim = v.toDouble()) } },
        )
        InspectorSlider(
            label = "Ángulo de luz: ${obj.liquidLightAngle.roundToInt()}°",
            value = obj.liquidLightAngle.toFloat(),
            range = 0f..360f,
            onChange = { v -> viewModel.updateObjectDebounced(obj.id) { it.copy(liquidLightAngle = v.toDouble()) } },
        )
        InspectorSlider(
            label = "Cantidad de tinte: ${(obj.liquidTintAmount * 100).roundToInt()}%",
            value = obj.liquidTintAmount.toFloat(),
            range = 0f..1f,
            onChange = { v -> viewModel.updateObjectDebounced(obj.id) { it.copy(liquidTintAmount = v.toDouble()) } },
        )
    }

    HorizontalDivider()
    Row(
        modifier = Modifier.fillMaxWidth(),
        horizontalArrangement = Arrangement.SpaceBetween,
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Text("Desenfoque Frosted Glass")
        Switch(
            checked = obj.blurEnabled,
            onCheckedChange = { en -> viewModel.updateObjectImmediate(obj.id) { it.copy(blurEnabled = en) } },
        )
    }
    if (obj.blurEnabled) {
        InspectorSlider(
            label = "Intensidad Blur: ${(obj.blurAmount * 100).roundToInt()}%",
            value = obj.blurAmount.toFloat(),
            range = 0.05f..1f,
            onChange = { v -> viewModel.updateObjectDebounced(obj.id) { it.copy(blurAmount = v.toDouble()) } },
        )
    }

    HorizontalDivider()
    Text("Registro de Shaders Modulares (.agsl)", style = MaterialTheme.typography.labelLarge)
    FlowRow(horizontalArrangement = Arrangement.spacedBy(6.dp)) {
        FilterChip(
            selected = obj.shaderId == null && !obj.liquidGlassEnabled,
            onClick = {
                viewModel.updateObjectImmediate(obj.id) {
                    it.copy(shaderId = null, liquidGlassEnabled = false)
                }
            },
            label = { Text("Ninguno") },
        )
        shaders.forEach { (id, name) ->
            FilterChip(
                selected = obj.shaderId == id || (id == "liquid_glass" && obj.liquidGlassEnabled),
                onClick = {
                    viewModel.updateObjectImmediate(obj.id) {
                        it.copy(
                            shaderId = id,
                            liquidGlassEnabled = id == "liquid_glass",
                        )
                    }
                },
                label = { Text(name) },
            )
        }
    }
    OutlinedButton(onClick = onImportAgsl) {
        Text("Importar shader .agsl personalizado...")
    }
}

@OptIn(ExperimentalLayoutApi::class)
@Composable
private fun MaskAndBackgroundSection(
    obj: SceneObject?,
    uiState: EditorUiState,
    viewModel: EditorViewModel,
    onPickWallpaper: () -> Unit,
    aiCutoutPanel: @Composable (SceneObject?) -> Unit,
) {
    val bg = uiState.preset.background

    Text(
        "Modo de Fondo del Reloj StandBy",
        style = MaterialTheme.typography.labelLarge,
        color = MaterialTheme.colorScheme.primary,
    )
    Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
        FilterChip(
            selected = bg.mode == "black",
            onClick = { viewModel.updateBackgroundDebounced { it.copy(mode = "black") } },
            label = { Text("Negro AMOLED (#000000)") },
        )
        FilterChip(
            selected = bg.mode == "wallpaper",
            onClick = { viewModel.updateBackgroundDebounced { it.copy(mode = "wallpaper") } },
            label = { Text("Wallpaper + Profundidad IA") },
        )
    }

    if (bg.mode == "wallpaper") {
        OutlinedButton(onClick = onPickWallpaper, modifier = Modifier.fillMaxWidth()) {
            Text(
                if (bg.wallpaperUri.isNullOrBlank()) {
                    "Seleccionar imagen de Wallpaper..."
                } else {
                    "Cambiar Wallpaper (${File(bg.wallpaperUri).name})"
                }
            )
        }
        InspectorSlider(
            label = "Brillo del Wallpaper: ${(bg.brightness * 100).roundToInt()}%",
            value = bg.brightness.toFloat(),
            range = 0.05f..1.0f,
            onChange = { v -> viewModel.updateBackgroundDebounced { it.copy(brightness = v.toDouble()) } },
        )
        InspectorSlider(
            label = "Desenfoque de fondo: ${bg.blurRadius.roundToInt()} px",
            value = bg.blurRadius.toFloat(),
            range = 0f..45f,
            onChange = { v -> viewModel.updateBackgroundDebounced { it.copy(blurRadius = v.toDouble()) } },
        )
        InspectorSlider(
            label = "Atenuación adicional nocturna: ${(bg.nightDimFactor * 100).roundToInt()}%",
            value = bg.nightDimFactor.toFloat(),
            range = 0.1f..1.0f,
            onChange = { v -> viewModel.updateBackgroundDebounced { it.copy(nightDimFactor = v.toDouble()) } },
        )
    }

    if (obj != null) {
        HorizontalDivider()
        Text(
            "Máscara de Profundidad y Recorte (${obj.name})",
            style = MaterialTheme.typography.labelLarge,
            color = MaterialTheme.colorScheme.primary,
        )
        Row(
            modifier = Modifier.fillMaxWidth(),
            horizontalArrangement = Arrangement.SpaceBetween,
            verticalAlignment = Alignment.CenterVertically,
        ) {
            Text("Actuar como capa de Máscara IA")
            Switch(
                checked = obj.isMask,
                onCheckedChange = { m -> viewModel.updateObjectImmediate(obj.id) { it.copy(isMask = m) } },
            )
        }
        FlowRow(horizontalArrangement = Arrangement.spacedBy(6.dp)) {
            listOf(
                "none" to "Sin oclusión (Delante)",
                "limit_50" to "Smart Depth (Por carácter)",
                "always_behind" to "Siempre detrás del sujeto",
            ).forEach { (mode, label) ->
                FilterChip(
                    selected = obj.depthCutoutMode == mode,
                    onClick = {
                        viewModel.updateObjectImmediate(obj.id) { it.copy(depthCutoutMode = mode) }
                    },
                    label = { Text(label) },
                )
            }
        }
        if (obj.depthCutoutMode == "limit_50") {
            InspectorSlider(
                label = "Umbral Smart Depth: ${obj.depthCutoutThreshold.roundToInt()}% oclusión",
                value = obj.depthCutoutThreshold.toFloat(),
                range = 10f..90f,
                onChange = { t ->
                    viewModel.updateObjectDebounced(obj.id) { it.copy(depthCutoutThreshold = t.toDouble()) }
                },
            )
        }
        InspectorSlider(
            label = "Choke / Erosión borde: ${"%.1f".format(obj.maskChoke)} px",
            value = obj.maskChoke.toFloat(),
            range = -5f..5f,
            onChange = { c -> viewModel.updateObjectDebounced(obj.id) { it.copy(maskChoke = c.toDouble()) } },
        )
        InspectorSlider(
            label = "Difuminado borde (Feather AGSL): ${"%.1f".format(obj.maskFeather)} px",
            value = obj.maskFeather.toFloat(),
            range = 0f..15f,
            onChange = { f -> viewModel.updateObjectDebounced(obj.id) { it.copy(maskFeather = f.toDouble()) } },
        )
        Row(
            modifier = Modifier.fillMaxWidth(),
            horizontalArrangement = Arrangement.SpaceBetween,
            verticalAlignment = Alignment.CenterVertically,
        ) {
            Text("Invertir máscara")
            Switch(
                checked = obj.invertMask,
                onCheckedChange = { inv ->
                    viewModel.updateObjectImmediate(obj.id) { it.copy(invertMask = inv) }
                },
            )
        }
    }

    HorizontalDivider()
    aiCutoutPanel(obj)
}

@OptIn(ExperimentalLayoutApi::class)
@Composable
private fun ExpressionsSection(
    obj: SceneObject,
    viewModel: EditorViewModel,
) {
    val exprProps = listOf(
        "opacity" to "Opacidad (0..1)",
        "offsetX" to "Offset X (px)",
        "offsetY" to "Offset Y (px)",
        "rotation" to "Rotación (deg)",
        "scale" to "Escala (mult)",
        "color" to "Color (#hex / @token / rgb)",
    )
    var selectedProp by remember { mutableStateOf("opacity") }
    val currentExpr = obj.expressions[selectedProp].orEmpty()
    var exprBuf by remember(obj.id, selectedProp, currentExpr) { mutableStateOf(currentExpr) }
    var visibleCondBuf by remember(obj.id, obj.visibleCondition) { mutableStateOf(obj.visibleCondition) }
    var onTapBuf by remember(obj.id, obj.actions?.onTap) { mutableStateOf(obj.actions?.onTap.orEmpty()) }

    LaunchedEffect(obj.id, selectedProp, currentExpr) {
        if (exprBuf != currentExpr) exprBuf = currentExpr
    }

    val syntaxError = remember(exprBuf) { viewModel.validateExpression(exprBuf) }

    Text(
        "Propiedad dinámica",
        style = MaterialTheme.typography.labelLarge,
        color = MaterialTheme.colorScheme.primary,
    )
    FlowRow(horizontalArrangement = Arrangement.spacedBy(6.dp)) {
        exprProps.forEach { (key, label) ->
            val hasExpr = !obj.expressions[key].isNullOrBlank()
            FilterChip(
                selected = selectedProp == key,
                onClick = { selectedProp = key },
                label = { Text(if (hasExpr) "$label ●" else label) },
            )
        }
    }

    OutlinedTextField(
        value = exprBuf,
        onValueChange = { next ->
            exprBuf = next
            viewModel.updateObjectDebounced(obj.id) { o ->
                val map = o.expressions.toMutableMap()
                if (next.isBlank()) map.remove(selectedProp) else map[selectedProp] = next
                o.copy(expressions = map)
            }
        },
        label = { Text("Expresión para '$selectedProp'") },
        isError = syntaxError != null,
        supportingText = {
            if (syntaxError != null) {
                Text("Error: $syntaxError", color = MaterialTheme.colorScheme.error)
            } else {
                Text("Sintaxis válida · Soporta matemáticas, easings, regex y tokens Material You")
            }
        },
        modifier = Modifier.fillMaxWidth(),
    )

    Text("Snippets rápidos y Regex", style = MaterialTheme.typography.labelMedium)
    FlowRow(horizontalArrangement = Arrangement.spacedBy(6.dp)) {
        listOf(
            "0.7 + 0.3 * sin(t * 2)" to "Pulso suave",
            "isCharging ? 1.0 : 0.45" to "Si carga",
            "regexTest(mediaTitle, \"(?i)live|remix\") ? 1.0 : 0.7" to "regexTest()",
            "len(regexExtract(mediaTitle, \"^([^-]+)\", 1)) > 0 ? 1 : 0.8" to "regexExtract()",
            "mix(@primary, @tertiary, 0.5 + 0.5 * sin(t))" to "Mix Material You",
            "easeInOutCubic(mediaProgress)" to "Easing Media",
        ).forEach { (snippet, label) ->
            AssistChip(
                onClick = {
                    exprBuf = snippet
                    viewModel.updateObjectImmediate(obj.id) { o ->
                        o.copy(expressions = o.expressions + (selectedProp to snippet))
                    }
                },
                label = { Text(label) },
            )
        }
    }

    HorizontalDivider()
    OutlinedTextField(
        value = visibleCondBuf,
        onValueChange = { next ->
            visibleCondBuf = next
            viewModel.updateObjectDebounced(obj.id) { it.copy(visibleCondition = next) }
        },
        label = { Text("Condición de visibilidad (ej. hasMedia && isPlaying)") },
        singleLine = true,
        modifier = Modifier.fillMaxWidth(),
    )

    HorizontalDivider()
    OutlinedTextField(
        value = onTapBuf,
        onValueChange = { next ->
            onTapBuf = next
            viewModel.updateObjectDebounced(obj.id) { o ->
                val curAct = o.actions ?: ActionsConfig()
                o.copy(actions = curAct.copy(onTap = next))
            }
        },
        label = { Text("Acción al tocar (onTap)") },
        singleLine = true,
        modifier = Modifier.fillMaxWidth(),
    )
    FlowRow(horizontalArrangement = Arrangement.spacedBy(6.dp)) {
        listOf(
            "wiz(toggle,primary)" to "Toggle WiZ",
            "wiz(scene,primary,3)" to "WiZ Atardecer",
            "wiz(scene,primary,30)" to "WiZ Ciberpunk",
            "media(play_pause)" to "Play/Pausa",
            "media(next)" to "Siguiente canción",
            "flip(@|expanded)" to "Flip estado",
        ).forEach { (act, label) ->
            AssistChip(
                onClick = {
                    onTapBuf = act
                    viewModel.updateObjectImmediate(obj.id) { o ->
                        val curAct = o.actions ?: ActionsConfig()
                        o.copy(actions = curAct.copy(onTap = act))
                    }
                },
                label = { Text(label) },
            )
        }
    }
}

@Composable
private fun InspectorSlider(
    label: String,
    value: Float,
    range: ClosedFloatingPointRange<Float>,
    onChange: (Float) -> Unit,
) {
    Column(modifier = Modifier.fillMaxWidth()) {
        Text(
            label,
            style = MaterialTheme.typography.labelMedium,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
        )
        Slider(
            value = value.coerceIn(range.start, range.endInclusive),
            onValueChange = onChange,
            valueRange = range,
        )
    }
}
