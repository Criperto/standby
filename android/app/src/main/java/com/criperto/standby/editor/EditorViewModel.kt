package com.criperto.standby.editor

import com.criperto.standby.StandByApplication
import com.criperto.standby.core.ActionsConfig
import com.criperto.standby.core.AppJson
import com.criperto.standby.core.BackgroundConfig
import com.criperto.standby.core.DeviceSignals
import com.criperto.standby.core.GradientSpec
import com.criperto.standby.core.SceneObject
import com.criperto.standby.core.SnapGuideDto
import com.criperto.standby.core.SnapResultDto
import com.criperto.standby.core.SpacingLabelDto
import com.criperto.standby.core.StandByPreset
import com.criperto.standby.core.ValidationReport
import com.criperto.standby.core.WorldTransformDto
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.launch
import uniffi.standby_core.alignObjectScreenJson
import uniffi.standby_core.alignSelectedObjectsJson
import uniffi.standby_core.computeSnapJson
import uniffi.standby_core.getObjectWorldTransformJson
import uniffi.standby_core.recalculateGroupLayoutJson
import uniffi.standby_core.setPivotKeepVisualJson
import uniffi.standby_core.validateExpressionStr
import java.util.UUID
import kotlin.math.cos
import kotlin.math.max
import kotlin.math.min
import kotlin.math.roundToInt
import kotlin.math.sin

enum class EditorSheetTab(val label: String) {
    INSPECTOR("Inspector"),
    LAYERS("Capas"),
    ADD("Agregar"),
    SIMULATOR("Simulador"),
    PRESETS("Presets"),
}

enum class InspectorSection(val label: String) {
    TRANSFORM("Transformar"),
    STYLE("Estilo"),
    TEXT("Texto"),
    SHADERS("Shaders"),
    MASK_BG("Fondo y IA"),
    EXPRESSIONS("Expresiones [fx]"),
}

/**
 * Bounded 35-step Undo/Redo history manager with drag transaction coalescing.
 * Pure Kotlin so it can be unit-tested on the host JVM without native JNI.
 */
class HistoryManager<T>(private val maxSteps: Int = 35) {
    private val undoStack = ArrayDeque<T>()
    private val redoStack = ArrayDeque<T>()
    private var transactionStart: T? = null

    val undoCount: Int get() = undoStack.size
    val redoCount: Int get() = redoStack.size
    val inTransaction: Boolean get() = transactionStart != null

    fun recordBeforeChange(currentSnapshot: T) {
        if (transactionStart != null) return
        if (undoStack.lastOrNull() == currentSnapshot) return
        undoStack.addLast(currentSnapshot)
        while (undoStack.size > maxSteps) {
            undoStack.removeFirst()
        }
        redoStack.clear()
    }

    fun beginTransaction(currentSnapshot: T) {
        if (transactionStart == null) {
            transactionStart = currentSnapshot
        }
    }

    fun commitTransaction(finalSnapshot: T) {
        val start = transactionStart ?: return
        transactionStart = null
        if (start != finalSnapshot) {
            recordBeforeChange(start)
        }
    }

    fun undo(currentSnapshot: T): T? {
        transactionStart = null
        if (undoStack.isEmpty()) return null
        val prev = undoStack.removeLast()
        redoStack.addLast(currentSnapshot)
        while (redoStack.size > maxSteps) {
            redoStack.removeFirst()
        }
        return prev
    }

    fun redo(currentSnapshot: T): T? {
        transactionStart = null
        if (redoStack.isEmpty()) return null
        val next = redoStack.removeLast()
        undoStack.addLast(currentSnapshot)
        while (undoStack.size > maxSteps) {
            undoStack.removeFirst()
        }
        return next
    }

    fun clear() {
        undoStack.clear()
        redoStack.clear()
        transactionStart = null
    }
}

object SceneObjectOps {
    fun createDefaultObject(
        objType: String,
        existingObjects: List<SceneObject>,
    ): SceneObject {
        val nextZ = (existingObjects.maxOfOrNull { it.zIndex } ?: 0) + 1
        val shortUid = UUID.randomUUID().toString().take(6)
        val id = "${objType}_$shortUid"
        val label = defaultDisplayName(objType, existingObjects.count { it.objType == objType } + 1)

        return when (objType) {
            "clock" -> SceneObject(
                id = id,
                objType = objType,
                name = label,
                zIndex = nextZ,
                baseWidth = 640.0,
                baseHeight = 220.0,
                baseFontSize = 168.0,
                fontWeight = 800,
                textColorChoice = "primary",
            )
            "clock_hours", "clock_minutes", "clock_seconds" -> SceneObject(
                id = id,
                objType = objType,
                name = label,
                zIndex = nextZ,
                baseWidth = 280.0,
                baseHeight = 200.0,
                baseFontSize = 156.0,
                fontWeight = 800,
                textColorChoice = if (objType == "clock_minutes") "tertiary" else "primary",
            )
            "clock_h1", "clock_h2", "clock_m1", "clock_m2", "clock_s1", "clock_s2", "clock_separator" -> SceneObject(
                id = id,
                objType = objType,
                name = label,
                zIndex = nextZ,
                baseWidth = 140.0,
                baseHeight = 190.0,
                baseFontSize = 152.0,
                fontWeight = 800,
                textColorChoice = "primary",
            )
            "clock_ampm" -> SceneObject(
                id = id,
                objType = objType,
                name = label,
                zIndex = nextZ,
                baseWidth = 140.0,
                baseHeight = 80.0,
                baseFontSize = 42.0,
                fontWeight = 700,
                textColorChoice = "secondary",
            )
            "date", "datetime" -> SceneObject(
                id = id,
                objType = objType,
                name = label,
                zIndex = nextZ,
                posYRatio = 0.72,
                baseWidth = 520.0,
                baseHeight = 76.0,
                baseFontSize = 36.0,
                fontWeight = 600,
                textColorChoice = "secondary",
            )
            "custom_text" -> SceneObject(
                id = id,
                objType = objType,
                name = label,
                zIndex = nextZ,
                baseWidth = 460.0,
                baseHeight = 90.0,
                baseFontSize = 40.0,
                customContent = "StandBy · \$BATTERY%",
                textColorChoice = "onSurface",
            )
            "shape" -> SceneObject(
                id = id,
                objType = objType,
                name = label,
                zIndex = nextZ,
                baseWidth = 340.0,
                baseHeight = 180.0,
                shapeType = "rectangle",
                radiusUniform = 32.0,
                textColorChoice = "surfaceContainer",
                fillAlpha = 0.85,
            )
            "icon_svg" -> SceneObject(
                id = id,
                objType = objType,
                name = label,
                zIndex = nextZ,
                baseWidth = 96.0,
                baseHeight = 96.0,
                iconName = "bolt",
                textColorChoice = "primary",
            )
            "battery", "battery_icon", "battery_percent", "battery_bar" -> SceneObject(
                id = id,
                objType = objType,
                name = label,
                zIndex = nextZ,
                posYRatio = 0.84,
                baseWidth = if (objType == "battery_bar") 280.0 else 200.0,
                baseHeight = 64.0,
                baseFontSize = 30.0,
                textColorChoice = "tertiary",
            )
            "media", "media_cover", "media_title", "media_artist", "media_play_pause", "media_prev", "media_next", "media_progress" -> SceneObject(
                id = id,
                objType = objType,
                name = label,
                zIndex = nextZ,
                baseWidth = if (objType == "media") 420.0 else 180.0,
                baseHeight = if (objType == "media") 140.0 else 72.0,
                baseFontSize = 26.0,
                textColorChoice = "onSurface",
            )
            "weather", "weather_icon", "weather_temp" -> SceneObject(
                id = id,
                objType = objType,
                name = label,
                zIndex = nextZ,
                baseWidth = 220.0,
                baseHeight = 84.0,
                baseFontSize = 36.0,
                textColorChoice = "primary",
            )
            "wiz_device" -> SceneObject(
                id = id,
                objType = objType,
                name = label,
                zIndex = nextZ,
                baseWidth = 250.0,
                baseHeight = 92.0,
                baseFontSize = 26.0,
                textColorChoice = "secondaryContainer",
                wizDeviceId = "primary",
                wizAction = "toggle",
                actions = ActionsConfig(onTap = "wiz(toggle,primary)"),
            )
            "group" -> SceneObject(
                id = id,
                objType = "group",
                name = label,
                zIndex = nextZ,
                baseWidth = 400.0,
                baseHeight = 240.0,
                groupWidth = 400.0,
                groupHeight = 240.0,
            )
            else -> SceneObject(
                id = id,
                objType = objType,
                name = label,
                zIndex = nextZ,
            )
        }
    }

    fun duplicateSelected(
        objects: List<SceneObject>,
        selectedIds: Set<String>,
    ): Pair<List<SceneObject>, Set<String>> {
        if (selectedIds.isEmpty()) return objects to emptySet()
        var nextZ = (objects.maxOfOrNull { it.zIndex } ?: 0) + 1
        val added = mutableListOf<SceneObject>()
        val newIds = mutableSetOf<String>()

        for (obj in objects.filter { it.id in selectedIds }) {
            val copyId = "${obj.objType}_${UUID.randomUUID().toString().take(6)}"
            val shiftedX = (obj.posXRatio + 0.03).coerceIn(0.05, 0.95)
            val shiftedY = (obj.posYRatio + 0.03).coerceIn(0.05, 0.95)
            added += obj.copy(
                id = copyId,
                name = "${obj.name.ifBlank { obj.id }} (copia)",
                zIndex = nextZ++,
                posXRatio = shiftedX,
                posYRatio = shiftedY,
            )
            newIds += copyId
        }
        return (objects + added) to newIds
    }

    fun deleteSelected(
        objects: List<SceneObject>,
        selectedIds: Set<String>,
    ): List<SceneObject> {
        if (selectedIds.isEmpty()) return objects
        return objects
            .filterNot { it.id in selectedIds }
            .map { obj ->
                val cleanedGroup = if (obj.groupId in selectedIds) null else obj.groupId
                val cleanedChildren = obj.childrenIds.filterNot { it in selectedIds }
                val cleanedMaskTarget = if (obj.maskTargetId in selectedIds) null else obj.maskTargetId
                val cleanedMaskTargets = obj.maskTargetIds.filterNot { it in selectedIds }
                obj.copy(
                    groupId = cleanedGroup,
                    childrenIds = cleanedChildren,
                    maskTargetId = cleanedMaskTarget,
                    maskTargetIds = cleanedMaskTargets,
                )
            }
    }

    fun moveZOrder(
        objects: List<SceneObject>,
        targetId: String,
        direction: Int, // +1 up, -1 down
    ): List<SceneObject> {
        val sorted = objects.sortedBy { it.zIndex }.toMutableList()
        val idx = sorted.indexOfFirst { it.id == targetId }
        if (idx < 0) return objects
        val swapIdx = (idx + direction).coerceIn(0, sorted.lastIndex)
        if (swapIdx == idx) return objects
        val a = sorted[idx]
        val b = sorted[swapIdx]
        sorted[idx] = b.copy(zIndex = a.zIndex)
        sorted[swapIdx] = a.copy(zIndex = b.zIndex)
        return sorted.mapIndexed { i, item -> item.copy(zIndex = i + 1) }
    }

    fun groupSelection(
        objects: List<SceneObject>,
        selectedIds: Set<String>,
        screenW: Double = 1920.0,
        screenH: Double = 1080.0,
    ): Pair<List<SceneObject>, String?> {
        val members = objects.filter { it.id in selectedIds && it.objType != "group" && it.groupId == null }
        if (members.size < 2) return objects to null

        var minX = Double.POSITIVE_INFINITY
        var minY = Double.POSITIVE_INFINITY
        var maxX = Double.NEGATIVE_INFINITY
        var maxY = Double.NEGATIVE_INFINITY
        for (m in members) {
            val cx = m.posXRatio * screenW
            val cy = m.posYRatio * screenH
            val hw = (m.baseWidth * m.scaleX * m.uniformScale) / 2.0
            val hh = (m.baseHeight * m.scaleY * m.uniformScale) / 2.0
            minX = min(minX, cx - hw)
            minY = min(minY, cy - hh)
            maxX = max(maxX, cx + hw)
            maxY = max(maxY, cy + hh)
        }
        val gCenterX = (minX + maxX) / 2.0
        val gCenterY = (minY + maxY) / 2.0
        val gW = (maxX - minX).coerceAtLeast(100.0)
        val gH = (maxY - minY).coerceAtLeast(80.0)
        val groupId = "group_${UUID.randomUUID().toString().take(6)}"
        val maxZ = (members.maxOfOrNull { it.zIndex } ?: 1)

        val groupObj = SceneObject(
            id = groupId,
            objType = "group",
            name = "Grupo (${members.size})",
            zIndex = maxZ,
            posXRatio = (gCenterX / screenW).coerceIn(0.0, 1.0),
            posYRatio = (gCenterY / screenH).coerceIn(0.0, 1.0),
            baseWidth = gW,
            baseHeight = gH,
            groupWidth = gW,
            groupHeight = gH,
            childrenIds = members.map { it.id },
        )

        val memberIds = members.map { it.id }.toSet()
        val updated = objects.map { obj ->
            if (obj.id in memberIds) {
                val cx = obj.posXRatio * screenW
                val cy = obj.posYRatio * screenH
                obj.copy(
                    groupId = groupId,
                    groupOffsetX = cx - gCenterX,
                    groupOffsetY = cy - gCenterY,
                )
            } else {
                obj
            }
        } + groupObj

        return updated to groupId
    }

    fun ungroupSelection(
        objects: List<SceneObject>,
        groupId: String,
        screenW: Double = 1920.0,
        screenH: Double = 1080.0,
    ): List<SceneObject> {
        val group = objects.firstOrNull { it.id == groupId && it.objType == "group" } ?: return objects
        val gx = group.posXRatio * screenW
        val gy = group.posYRatio * screenH
        return objects
            .filterNot { it.id == groupId }
            .map { obj ->
                if (obj.groupId == groupId) {
                    val wx = ((gx + obj.groupOffsetX) / screenW).coerceIn(0.0, 1.0)
                    val wy = ((gy + obj.groupOffsetY) / screenH).coerceIn(0.0, 1.0)
                    obj.copy(
                        groupId = null,
                        posXRatio = wx,
                        posYRatio = wy,
                        groupOffsetX = 0.0,
                        groupOffsetY = 0.0,
                    )
                } else {
                    obj
                }
            }
    }

    fun computePureVisualBox(
        obj: SceneObject,
        objects: List<SceneObject>,
        screenW: Double,
        screenH: Double,
    ): WorldTransformDto {
        val w = (obj.baseWidth * obj.scaleX * obj.uniformScale).coerceAtLeast(16.0)
        val h = (obj.baseHeight * obj.scaleY * obj.uniformScale).coerceAtLeast(16.0)
        val parent = obj.groupId?.let { gid -> objects.firstOrNull { it.id == gid } }
        val anchorX: Double
        val anchorY: Double
        val rotDeg: Double
        if (parent != null) {
            val px = parent.posXRatio * screenW
            val py = parent.posYRatio * screenH
            val rad = Math.toRadians(parent.rotation)
            val rx = obj.groupOffsetX * cos(rad) - obj.groupOffsetY * sin(rad)
            val ry = obj.groupOffsetX * sin(rad) + obj.groupOffsetY * cos(rad)
            anchorX = px + rx
            anchorY = py + ry
            rotDeg = parent.rotation + obj.rotation
        } else {
            anchorX = obj.posXRatio * screenW
            anchorY = obj.posYRatio * screenH
            rotDeg = obj.rotation
        }
        val dx = (0.5 - obj.pivotX) * w
        val dy = (0.5 - obj.pivotY) * h
        val rad = Math.toRadians(rotDeg)
        val vcx = anchorX + dx * cos(rad) - dy * sin(rad)
        val vcy = anchorY + dx * sin(rad) + dy * cos(rad)
        return WorldTransformDto(
            anchorX = anchorX,
            anchorY = anchorY,
            visualCenterX = vcx,
            visualCenterY = vcy,
            rotationDeg = rotDeg,
            scaleX = obj.scaleX * obj.uniformScale,
            scaleY = obj.scaleY * obj.uniformScale,
            width = w,
            height = h,
        )
    }

    private fun defaultDisplayName(objType: String, index: Int): String = when (objType) {
        "clock" -> "Reloj completo $index"
        "clock_hours" -> "Horas $index"
        "clock_minutes" -> "Minutos $index"
        "clock_seconds" -> "Segundos $index"
        "clock_separator" -> "Separador : $index"
        "clock_ampm" -> "AM/PM $index"
        "clock_h1" -> "Dígito H1 $index"
        "clock_h2" -> "Dígito H2 $index"
        "clock_m1" -> "Dígito M1 $index"
        "clock_m2" -> "Dígito M2 $index"
        "clock_s1" -> "Dígito S1 $index"
        "clock_s2" -> "Dígito S2 $index"
        "date" -> "Fecha $index"
        "datetime" -> "Fecha y Hora $index"
        "custom_text" -> "Texto $index"
        "shape" -> "Figura $index"
        "icon_svg" -> "Icono SVG $index"
        "battery" -> "Batería $index"
        "battery_icon" -> "Icono Batería $index"
        "battery_percent" -> "% Batería $index"
        "battery_bar" -> "Barra Batería $index"
        "media" -> "Reproductor Media $index"
        "media_cover" -> "Carátula $index"
        "media_title" -> "Título Canción $index"
        "media_artist" -> "Artista $index"
        "media_play_pause" -> "Play/Pausa $index"
        "media_prev" -> "Anterior $index"
        "media_next" -> "Siguiente $index"
        "media_progress" -> "Progreso Media $index"
        "weather" -> "Clima $index"
        "weather_icon" -> "Icono Clima $index"
        "weather_temp" -> "Temperatura $index"
        "wiz_device" -> "Control WiZ $index"
        "group" -> "Grupo $index"
        else -> "$objType $index"
    }
}

data class EditorUiState(
    val presetId: String = "standby_split",
    val preset: StandByPreset = StandByPreset(),
    val selectedIds: Set<String> = emptySet(),
    val multiSelectMode: Boolean = false,
    val zoom: Float = 1f,
    val panX: Float = 0f,
    val panY: Float = 0f,
    val snapEnabled: Boolean = true,
    val showCompositionGuides: Boolean = true,
    val showMaskLinks: Boolean = true,
    val activeGuides: List<SnapGuideDto> = emptyList(),
    val activeSpacings: List<SpacingLabelDto> = emptyList(),
    val activeSheet: EditorSheetTab = EditorSheetTab.INSPECTOR,
    val inspectorSection: InspectorSection = InspectorSection.TRANSFORM,
    val simulatorEnabled: Boolean = false,
    val simulatorSignals: DeviceSignals = DeviceSignals(
        batteryLevel = 80,
        isCharging = true,
        chargeSource = "ac",
        hour = 21,
        minute = 45,
        second = 30.0,
        weatherTemp = "22°",
    ),
    val undoCount: Int = 0,
    val redoCount: Int = 0,
    val dirty: Boolean = false,
    val statusMessage: String? = null,
    val showFontPickerForObjectId: String? = null,
) {
    val primarySelectedObject: SceneObject?
        get() = selectedIds.firstOrNull()?.let { id -> preset.objects.firstOrNull { it.id == id } }
}

class EditorViewModel(private val app: StandByApplication) {
    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.Main.immediate)
    private val history = HistoryManager<StandByPreset>(maxSteps = 35)

    private var pendingDebounceBeforeSnapshot: StandByPreset? = null
    private var debounceJob: Job? = null

    private val _uiState = MutableStateFlow(EditorUiState())
    val uiState: StateFlow<EditorUiState> = _uiState.asStateFlow()

    init {
        scope.launch {
            app.presetRepository.activePresetFlow.collect { active ->
                val activeId = app.presetRepository.activePresetIdFlow.value
                if (!_uiState.value.dirty || _uiState.value.preset.objects.isEmpty()) {
                    history.clear()
                    _uiState.value = _uiState.value.copy(
                        presetId = activeId,
                        preset = active,
                        selectedIds = active.objects.firstOrNull()?.let { setOf(it.id) } ?: emptySet(),
                        undoCount = 0,
                        redoCount = 0,
                        dirty = false,
                    )
                }
            }
        }
    }

    fun selectObject(id: String?, toggleInMulti: Boolean = _uiState.value.multiSelectMode) {
        flushPendingHistory()
        val cur = _uiState.value
        val nextSel = when {
            id == null -> emptySet()
            toggleInMulti -> if (id in cur.selectedIds) cur.selectedIds - id else cur.selectedIds + id
            else -> setOf(id)
        }
        _uiState.value = cur.copy(
            selectedIds = nextSel,
            activeGuides = emptyList(),
            activeSpacings = emptyList(),
        )
    }

    fun setMultiSelectMode(enabled: Boolean) {
        _uiState.value = _uiState.value.copy(multiSelectMode = enabled)
    }

    fun setActiveSheet(tab: EditorSheetTab) {
        flushPendingHistory()
        _uiState.value = _uiState.value.copy(activeSheet = tab)
    }

    fun setInspectorSection(section: InspectorSection) {
        flushPendingHistory()
        _uiState.value = _uiState.value.copy(
            activeSheet = EditorSheetTab.INSPECTOR,
            inspectorSection = section,
        )
    }

    fun openFontPicker(objectId: String?) {
        _uiState.value = _uiState.value.copy(showFontPickerForObjectId = objectId)
    }

    fun setZoomAndPan(zoom: Float, panX: Float, panY: Float) {
        _uiState.value = _uiState.value.copy(
            zoom = zoom.coerceIn(0.45f, 3.0f),
            panX = panX,
            panY = panY,
        )
    }

    fun resetViewport() {
        _uiState.value = _uiState.value.copy(zoom = 1f, panX = 0f, panY = 0f)
    }

    fun toggleSnap() {
        _uiState.value = _uiState.value.copy(
            snapEnabled = !_uiState.value.snapEnabled,
            activeGuides = emptyList(),
            activeSpacings = emptyList(),
        )
    }

    fun toggleCompositionGuides() {
        _uiState.value = _uiState.value.copy(
            showCompositionGuides = !_uiState.value.showCompositionGuides
        )
    }

    fun toggleMaskLinks() {
        _uiState.value = _uiState.value.copy(showMaskLinks = !_uiState.value.showMaskLinks)
    }

    fun setSimulatorEnabled(enabled: Boolean) {
        _uiState.value = _uiState.value.copy(simulatorEnabled = enabled)
    }

    fun updateSimulatorSignals(transform: (DeviceSignals) -> DeviceSignals) {
        _uiState.value = _uiState.value.copy(
            simulatorEnabled = true,
            simulatorSignals = transform(_uiState.value.simulatorSignals),
        )
    }

    fun beginDragTransaction() {
        flushPendingHistory()
        history.beginTransaction(_uiState.value.preset)
    }

    fun endDragTransaction() {
        history.commitTransaction(_uiState.value.preset)
        _uiState.value = _uiState.value.copy(
            activeGuides = emptyList(),
            activeSpacings = emptyList(),
            undoCount = history.undoCount,
            redoCount = history.redoCount,
        )
    }

    fun flushPendingHistory() {
        debounceJob?.cancel()
        debounceJob = null
        val snap = pendingDebounceBeforeSnapshot
        pendingDebounceBeforeSnapshot = null
        if (snap != null && snap != _uiState.value.preset) {
            history.recordBeforeChange(snap)
            _uiState.value = _uiState.value.copy(
                undoCount = history.undoCount,
                redoCount = history.redoCount,
            )
        }
    }

    fun undo() {
        flushPendingHistory()
        val prev = history.undo(_uiState.value.preset) ?: return
        val validSel = _uiState.value.selectedIds.intersect(prev.objects.map { it.id }.toSet())
        _uiState.value = _uiState.value.copy(
            preset = prev,
            selectedIds = validSel,
            undoCount = history.undoCount,
            redoCount = history.redoCount,
            dirty = true,
        )
    }

    fun redo() {
        flushPendingHistory()
        val next = history.redo(_uiState.value.preset) ?: return
        val validSel = _uiState.value.selectedIds.intersect(next.objects.map { it.id }.toSet())
        _uiState.value = _uiState.value.copy(
            preset = next,
            selectedIds = validSel,
            undoCount = history.undoCount,
            redoCount = history.redoCount,
            dirty = true,
        )
    }

    fun addObject(objType: String) {
        flushPendingHistory()
        val curPreset = _uiState.value.preset
        history.recordBeforeChange(curPreset)
        val created = SceneObjectOps.createDefaultObject(objType, curPreset.objects)
        val nextPreset = curPreset.copy(objects = curPreset.objects + created)
        _uiState.value = _uiState.value.copy(
            preset = nextPreset,
            selectedIds = setOf(created.id),
            activeSheet = EditorSheetTab.INSPECTOR,
            undoCount = history.undoCount,
            redoCount = history.redoCount,
            dirty = true,
            statusMessage = "Agregado: ${created.name}",
        )
    }

    fun duplicateSelected() {
        flushPendingHistory()
        val cur = _uiState.value
        if (cur.selectedIds.isEmpty()) return
        history.recordBeforeChange(cur.preset)
        val (nextObjs, newIds) = SceneObjectOps.duplicateSelected(cur.preset.objects, cur.selectedIds)
        _uiState.value = cur.copy(
            preset = cur.preset.copy(objects = nextObjs),
            selectedIds = newIds,
            undoCount = history.undoCount,
            redoCount = history.redoCount,
            dirty = true,
            statusMessage = "${newIds.size} elemento(s) duplicado(s)",
        )
    }

    fun deleteSelected() {
        flushPendingHistory()
        val cur = _uiState.value
        if (cur.selectedIds.isEmpty()) return
        history.recordBeforeChange(cur.preset)
        val nextObjs = SceneObjectOps.deleteSelected(cur.preset.objects, cur.selectedIds)
        _uiState.value = cur.copy(
            preset = cur.preset.copy(objects = nextObjs),
            selectedIds = emptySet(),
            activeGuides = emptyList(),
            activeSpacings = emptyList(),
            undoCount = history.undoCount,
            redoCount = history.redoCount,
            dirty = true,
            statusMessage = "Elemento(s) eliminado(s)",
        )
    }

    fun moveZOrder(targetId: String, direction: Int) {
        flushPendingHistory()
        val cur = _uiState.value
        history.recordBeforeChange(cur.preset)
        val nextObjs = SceneObjectOps.moveZOrder(cur.preset.objects, targetId, direction)
        _uiState.value = cur.copy(
            preset = cur.preset.copy(objects = nextObjs),
            undoCount = history.undoCount,
            redoCount = history.redoCount,
            dirty = true,
        )
    }

    fun groupSelected(screenW: Double = 1920.0, screenH: Double = 1080.0) {
        flushPendingHistory()
        val cur = _uiState.value
        if (cur.selectedIds.size < 2) return
        history.recordBeforeChange(cur.preset)
        val (nextObjs, groupId) = SceneObjectOps.groupSelection(
            cur.preset.objects,
            cur.selectedIds,
            screenW,
            screenH,
        )
        if (groupId != null) {
            _uiState.value = cur.copy(
                preset = cur.preset.copy(objects = nextObjs),
                selectedIds = setOf(groupId),
                undoCount = history.undoCount,
                redoCount = history.redoCount,
                dirty = true,
                statusMessage = "Grupo creado",
            )
        }
    }

    fun ungroupSelected(screenW: Double = 1920.0, screenH: Double = 1080.0) {
        flushPendingHistory()
        val cur = _uiState.value
        val primary = cur.primarySelectedObject ?: return
        val groupId = if (primary.objType == "group") primary.id else primary.groupId ?: return
        history.recordBeforeChange(cur.preset)
        val nextObjs = SceneObjectOps.ungroupSelection(cur.preset.objects, groupId, screenW, screenH)
        _uiState.value = cur.copy(
            preset = cur.preset.copy(objects = nextObjs),
            selectedIds = emptySet(),
            undoCount = history.undoCount,
            redoCount = history.redoCount,
            dirty = true,
            statusMessage = "Grupo desagrupado",
        )
    }

    fun updateObjectImmediate(objectId: String, transform: (SceneObject) -> SceneObject) {
        flushPendingHistory()
        val cur = _uiState.value
        history.recordBeforeChange(cur.preset)
        val nextObjs = cur.preset.objects.map { if (it.id == objectId) transform(it) else it }
        _uiState.value = cur.copy(
            preset = cur.preset.copy(objects = nextObjs),
            undoCount = history.undoCount,
            redoCount = history.redoCount,
            dirty = true,
        )
    }

    fun updateObjectDebounced(objectId: String, transform: (SceneObject) -> SceneObject) {
        val cur = _uiState.value
        if (pendingDebounceBeforeSnapshot == null && !history.inTransaction) {
            pendingDebounceBeforeSnapshot = cur.preset
        }
        val nextObjs = cur.preset.objects.map { if (it.id == objectId) transform(it) else it }
        _uiState.value = cur.copy(
            preset = cur.preset.copy(objects = nextObjs),
            dirty = true,
        )
        debounceJob?.cancel()
        debounceJob = scope.launch {
            delay(350L)
            flushPendingHistory()
        }
    }

    fun updateBackgroundDebounced(transform: (BackgroundConfig) -> BackgroundConfig) {
        val cur = _uiState.value
        if (pendingDebounceBeforeSnapshot == null) {
            pendingDebounceBeforeSnapshot = cur.preset
        }
        val nextBg = transform(cur.preset.background)
        _uiState.value = cur.copy(
            preset = cur.preset.copy(background = nextBg),
            dirty = true,
        )
        debounceJob?.cancel()
        debounceJob = scope.launch {
            delay(350L)
            flushPendingHistory()
        }
    }

    fun dragMoveSelected(
        deltaPxX: Float,
        deltaPxY: Float,
        screenW: Float,
        screenH: Float,
    ): Boolean {
        val cur = _uiState.value
        val primary = cur.primarySelectedObject ?: return false
        if (primary.locked) return false
        val sw = screenW.toDouble().coerceAtLeast(100.0)
        val sh = screenH.toDouble().coerceAtLeast(100.0)

        val world = resolveWorldTransform(primary, cur.preset.objects, sw, sh)
        val rawCx = world.visualCenterX + deltaPxX
        val rawCy = world.visualCenterY + deltaPxY

        val objsJson = runCatching { AppJson.encodeToString(cur.preset.objects) }.getOrNull()
        val snap = if (objsJson != null) {
            runCatching {
                val resJson = computeSnapJson(
                    rawCx = rawCx,
                    rawCy = rawCy,
                    myW = world.width,
                    myH = world.height,
                    selectedIds = cur.selectedIds.toList(),
                    objectsJson = objsJson,
                    screenW = sw,
                    screenH = sh,
                    threshold = 8.0,
                    snapEnabled = cur.snapEnabled,
                )
                AppJson.decodeFromString<SnapResultDto>(resJson)
            }.getOrNull()
        } else {
            null
        }

        val finalCx = snap?.x ?: rawCx
        val finalCy = snap?.y ?: rawCy
        val effDx = finalCx - world.visualCenterX
        val effDy = finalCy - world.visualCenterY

        val nextObjs = cur.preset.objects.map { obj ->
            if (obj.id in cur.selectedIds && !obj.locked) {
                if (obj.groupId != null && obj.groupId !in cur.selectedIds) {
                    obj.copy(
                        groupOffsetX = obj.groupOffsetX + effDx,
                        groupOffsetY = obj.groupOffsetY + effDy,
                    )
                } else {
                    obj.copy(
                        posXRatio = ((obj.posXRatio * sw + effDx) / sw).coerceIn(-0.2, 1.2),
                        posYRatio = ((obj.posYRatio * sh + effDy) / sh).coerceIn(-0.2, 1.2),
                    )
                }
            } else {
                obj
            }
        }

        val prevGuideCount = cur.activeGuides.size
        val nextGuides = snap?.guides ?: emptyList()
        val snappedNewGuide = nextGuides.isNotEmpty() && nextGuides.size != prevGuideCount

        _uiState.value = cur.copy(
            preset = cur.preset.copy(objects = nextObjs),
            activeGuides = nextGuides,
            activeSpacings = snap?.spacings ?: emptyList(),
            dirty = true,
        )
        return snappedNewGuide
    }

    fun dragResizePrimary(
        scaleFactorW: Float,
        scaleFactorH: Float,
    ) {
        val cur = _uiState.value
        val primary = cur.primarySelectedObject ?: return
        if (primary.locked) return
        val nextW = (primary.baseWidth * scaleFactorW).coerceIn(24.0, 2400.0)
        val nextH = (primary.baseHeight * scaleFactorH).coerceIn(24.0, 2400.0)
        val isTextBacked = primary.objType.startsWith("clock") ||
            primary.objType == "date" ||
            primary.objType == "datetime" ||
            primary.objType == "custom_text"
        val nextFont = if (isTextBacked) {
            (primary.baseFontSize * ((scaleFactorW + scaleFactorH) / 2f)).coerceIn(12.0, 600.0)
        } else {
            primary.baseFontSize
        }

        val nextObjs = cur.preset.objects.map { obj ->
            if (obj.id == primary.id) {
                obj.copy(
                    baseWidth = nextW,
                    baseHeight = nextH,
                    groupWidth = if (obj.objType == "group") nextW else obj.groupWidth,
                    groupHeight = if (obj.objType == "group") nextH else obj.groupHeight,
                    baseFontSize = nextFont,
                )
            } else {
                obj
            }
        }
        _uiState.value = cur.copy(
            preset = cur.preset.copy(objects = nextObjs),
            dirty = true,
        )
    }

    fun dragRotatePrimary(newAngleDeg: Float, snap15: Boolean) {
        val cur = _uiState.value
        val primary = cur.primarySelectedObject ?: return
        if (primary.locked) return
        val normalized = ((newAngleDeg % 360f) + 360f) % 360f
        val angle = if (snap15) {
            ((normalized / 15f).roundToInt() * 15f) % 360f
        } else {
            normalized
        }
        val nextObjs = cur.preset.objects.map { obj ->
            if (obj.id == primary.id) obj.copy(rotation = angle.toDouble()) else obj
        }
        _uiState.value = cur.copy(
            preset = cur.preset.copy(objects = nextObjs),
            dirty = true,
        )
    }

    fun setPivotKeepVisualPosition(
        newPx: Double,
        newPy: Double,
        screenW: Double = 1920.0,
        screenH: Double = 1080.0,
    ) {
        flushPendingHistory()
        val cur = _uiState.value
        val primary = cur.primarySelectedObject ?: return
        history.recordBeforeChange(cur.preset)

        val updatedPrimary = runCatching {
            val resJson = setPivotKeepVisualJson(
                objJson = AppJson.encodeToString(primary),
                objectsJson = AppJson.encodeToString(cur.preset.objects),
                newPx = newPx.coerceIn(0.0, 1.0),
                newPy = newPy.coerceIn(0.0, 1.0),
                screenW = screenW,
                screenH = screenH,
            )
            AppJson.decodeFromString<SceneObject>(resJson)
        }.getOrElse {
            primary.copy(pivotX = newPx.coerceIn(0.0, 1.0), pivotY = newPy.coerceIn(0.0, 1.0))
        }

        val nextObjs = cur.preset.objects.map { if (it.id == primary.id) updatedPrimary else it }
        _uiState.value = cur.copy(
            preset = cur.preset.copy(objects = nextObjs),
            undoCount = history.undoCount,
            redoCount = history.redoCount,
            dirty = true,
        )
    }

    fun alignPrimaryToScreen(
        anchor: String,
        screenW: Double = 1920.0,
        screenH: Double = 1080.0,
    ) {
        flushPendingHistory()
        val cur = _uiState.value
        val primary = cur.primarySelectedObject ?: return
        history.recordBeforeChange(cur.preset)
        val aligned = runCatching {
            val resJson = alignObjectScreenJson(
                objJson = AppJson.encodeToString(primary),
                objectsJson = AppJson.encodeToString(cur.preset.objects),
                anchor = anchor,
                screenW = screenW,
                screenH = screenH,
            )
            AppJson.decodeFromString<SceneObject>(resJson)
        }.getOrNull() ?: return

        val nextObjs = cur.preset.objects.map { if (it.id == primary.id) aligned else it }
        _uiState.value = cur.copy(
            preset = cur.preset.copy(objects = nextObjs),
            undoCount = history.undoCount,
            redoCount = history.redoCount,
            dirty = true,
        )
    }

    fun alignMultipleSelected(
        mode: String,
        screenW: Double = 1920.0,
        screenH: Double = 1080.0,
    ) {
        flushPendingHistory()
        val cur = _uiState.value
        if (cur.selectedIds.size < 2) return
        history.recordBeforeChange(cur.preset)
        val alignedObjs = runCatching {
            val resJson = alignSelectedObjectsJson(
                mode = mode,
                selectedIds = cur.selectedIds.toList(),
                objectsJson = AppJson.encodeToString(cur.preset.objects),
                screenW = screenW,
                screenH = screenH,
            )
            AppJson.decodeFromString<List<SceneObject>>(resJson)
        }.getOrNull() ?: return

        _uiState.value = cur.copy(
            preset = cur.preset.copy(objects = alignedObjs),
            undoCount = history.undoCount,
            redoCount = history.redoCount,
            dirty = true,
        )
    }

    fun applyGroupAutoLayout(groupId: String, mode: String, gap: Double, align: String) {
        flushPendingHistory()
        val cur = _uiState.value
        history.recordBeforeChange(cur.preset)
        val preUpdated = cur.preset.objects.map {
            if (it.id == groupId) {
                it.copy(autoLayout = mode, autoLayoutGap = gap, autoLayoutAlign = align)
            } else {
                it
            }
        }
        val laidOut = runCatching {
            val resJson = recalculateGroupLayoutJson(
                groupId = groupId,
                objectsJson = AppJson.encodeToString(preUpdated),
            )
            AppJson.decodeFromString<List<SceneObject>>(resJson)
        }.getOrDefault(preUpdated)

        _uiState.value = cur.copy(
            preset = cur.preset.copy(objects = laidOut),
            undoCount = history.undoCount,
            redoCount = history.redoCount,
            dirty = true,
        )
    }

    fun resolveWorldTransform(
        obj: SceneObject,
        objects: List<SceneObject>,
        screenW: Double,
        screenH: Double,
    ): WorldTransformDto {
        return runCatching {
            val json = getObjectWorldTransformJson(
                objJson = AppJson.encodeToString(obj),
                objectsJson = AppJson.encodeToString(objects),
                screenW = screenW,
                screenH = screenH,
                isAnimating = false,
            )
            AppJson.decodeFromString<WorldTransformDto>(json)
        }.getOrElse {
            SceneObjectOps.computePureVisualBox(obj, objects, screenW, screenH)
        }
    }

    fun validateExpression(expr: String): String? {
        if (expr.isBlank()) return null
        return runCatching { validateExpressionStr(expr) }.getOrNull()
    }

    fun saveCurrentPreset(asNewName: String? = null, onDone: (ValidationReport?) -> Unit = {}) {
        flushPendingHistory()
        val cur = _uiState.value
        scope.launch {
            val targetId: String
            val targetPreset: StandByPreset
            if (!asNewName.isNullOrBlank()) {
                targetId = asNewName.lowercase().replace(Regex("[^a-z0-9_]+"), "_").trim('_')
                    .ifEmpty { "custom_${System.currentTimeMillis() / 1000}" }
                targetPreset = cur.preset.copy(name = asNewName.trim())
            } else {
                targetId = cur.presetId
                targetPreset = cur.preset
            }
            val report = runCatching {
                app.presetRepository.savePreset(targetId, targetPreset, activate = true)
            }.getOrNull()
            _uiState.value = _uiState.value.copy(
                presetId = targetId,
                preset = targetPreset,
                dirty = false,
                statusMessage = if (report?.valid != false) {
                    "Preset '${targetPreset.name}' guardado"
                } else {
                    "Guardado con advertencias"
                },
            )
            onDone(report)
        }
    }

    fun loadPresetIntoEditor(presetId: String) {
        flushPendingHistory()
        scope.launch {
            val loaded = app.presetRepository.loadAndActivate(presetId)
            history.clear()
            _uiState.value = _uiState.value.copy(
                presetId = presetId,
                preset = loaded,
                selectedIds = loaded.objects.firstOrNull()?.let { setOf(it.id) } ?: emptySet(),
                undoCount = 0,
                redoCount = 0,
                dirty = false,
                statusMessage = "Cargado: ${loaded.name}",
            )
        }
    }
}
