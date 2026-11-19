package com.criperto.standby.renderer

import android.graphics.Bitmap
import android.graphics.Canvas as AndroidCanvas
import android.graphics.Paint as AndroidPaint
import android.graphics.RectF
import android.graphics.RenderEffect
import android.graphics.Shader
import android.graphics.Typeface
import android.os.Build
import android.text.Layout
import android.text.StaticLayout
import android.text.TextPaint
import androidx.compose.foundation.Canvas
import androidx.compose.foundation.Image
import androidx.compose.foundation.background
import androidx.compose.foundation.gestures.detectTapGestures
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.BoxWithConstraints
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.offset
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.rounded.AcUnit
import androidx.compose.material.icons.rounded.BatteryChargingFull
import androidx.compose.material.icons.rounded.BatteryFull
import androidx.compose.material.icons.rounded.Bedtime
import androidx.compose.material.icons.rounded.Bolt
import androidx.compose.material.icons.rounded.Cloud
import androidx.compose.material.icons.rounded.Grain
import androidx.compose.material.icons.rounded.Lightbulb
import androidx.compose.material.icons.rounded.MusicNote
import androidx.compose.material.icons.rounded.Pause
import androidx.compose.material.icons.rounded.PlayArrow
import androidx.compose.material.icons.rounded.Repeat
import androidx.compose.material.icons.rounded.Shuffle
import androidx.compose.material.icons.rounded.SkipNext
import androidx.compose.material.icons.rounded.SkipPrevious
import androidx.compose.material.icons.rounded.Thunderstorm
import androidx.compose.material.icons.rounded.WbSunny
import androidx.compose.material3.Icon
import androidx.compose.material3.LinearProgressIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateMapOf
import androidx.compose.runtime.remember
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.alpha
import androidx.compose.ui.draw.clip
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.BlendMode
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.CompositingStrategy
import androidx.compose.ui.graphics.PathEffect
import androidx.compose.ui.graphics.ShaderBrush
import androidx.compose.ui.graphics.StrokeCap
import androidx.compose.ui.graphics.StrokeJoin
import androidx.compose.ui.graphics.TransformOrigin
import androidx.compose.ui.graphics.asComposeRenderEffect
import androidx.compose.ui.graphics.asImageBitmap
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.graphics.drawscope.drawIntoCanvas
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.graphics.nativeCanvas
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontStyle
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.IntOffset
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.criperto.standby.core.ActionOutputDto
import com.criperto.standby.core.ActionSideEffectDto
import com.criperto.standby.core.AppJson
import com.criperto.standby.core.DeviceSignals
import com.criperto.standby.core.MaterialYouPaletteDto
import com.criperto.standby.core.SceneObject
import com.criperto.standby.core.StandByPreset
import com.criperto.standby.standby.MediaBridge
import uniffi.standby_core.computeOcclusionRatioFfi
import uniffi.standby_core.evalExpressionColorArgb
import uniffi.standby_core.evalExpressionCondition
import uniffi.standby_core.evalExpressionNumeric
import uniffi.standby_core.evaluateDepthLimitFfi
import uniffi.standby_core.executeWidgetActions
import uniffi.standby_core.interpolateCustomText
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale
import kotlin.math.abs
import kotlin.math.cos
import kotlin.math.max
import kotlin.math.roundToInt
import kotlin.math.sin

data class DepthMaskRuntimeData(
    val cutoutBitmap: Bitmap,
    val occupancyGrid: List<UInt>,
)

data class ComputedWorldBox(
    val anchorX: Float,
    val anchorY: Float,
    val centerX: Float,
    val centerY: Float,
    val width: Float,
    val height: Float,
    val rotationDeg: Float,
    val scaleX: Float,
    val scaleY: Float,
    val effectiveOpacity: Float,
    val effectiveVisible: Boolean,
)

@Composable
fun SceneRenderer(
    preset: StandByPreset,
    signals: DeviceSignals,
    shaderRegistry: ShaderRegistry,
    assetCache: SceneAssetCache,
    modifier: Modifier = Modifier,
    depthMask: DepthMaskRuntimeData? = null,
    interactive: Boolean = true,
    onEmptyTap: () -> Unit = {},
    onWizAction: (command: String, deviceId: String, arg: String?) -> Unit = { _, _, _ -> },
    overrideObjects: List<SceneObject>? = null,
) {
    val palette = MaterialTheme.colorScheme.toPaletteDto()
    val density = LocalDensity.current
    val stateValues = remember(preset.name, preset.objects.size) {
        mutableStateMapOf<String, Double>().apply {
            for ((k, v) in preset.state) put("@|$k", v)
            for (obj in preset.objects) {
                if (obj.objType == "group") {
                    for ((k, v) in obj.state) put("${obj.id}|$k", v)
                }
            }
        }
    }

    val scopeAncestors = remember(preset.objects) {
        preset.objects.mapNotNull { o -> o.groupId?.let { o.id to it } }.toMap()
    }

    BoxWithConstraints(modifier = modifier.fillMaxSize()) {
        val sw = with(density) { maxWidth.toPx().coerceAtLeast(100f) }
        val sh = with(density) { maxHeight.toPx().coerceAtLeast(100f) }

        val backdrop = remember(
            preset.background,
            sw.roundToInt(),
            sh.roundToInt(),
            palette.primary
        ) {
            assetCache.resolveBackdrop(
                mode = preset.background.mode,
                wallpaperUri = preset.background.wallpaperUri,
                brightness = preset.background.brightness.toFloat(),
                blurRadius = preset.background.blurRadius.toFloat(),
                widthPx = sw.roundToInt(),
                heightPx = sh.roundToInt(),
                palette = palette,
            )
        }

        // Background layer + empty canvas tap target
        Box(
            modifier = Modifier
                .fillMaxSize()
                .then(
                    if (interactive) {
                        Modifier.pointerInput(preset.objects) {
                            detectTapGestures(
                                onTap = {
                                    // Trigger any onTapOutside actions first
                                    for (o in (overrideObjects ?: preset.objects)) {
                                        val act = o.actions?.onTapOutside?.trim().orEmpty()
                                        if (act.isNotEmpty()) {
                                            dispatchAction(
                                                act, o, signals, sw, sh, stateValues, scopeAncestors,
                                                palette, overrideObjects ?: preset.objects, onWizAction
                                            )
                                        }
                                    }
                                    onEmptyTap()
                                }
                            )
                        }
                    } else Modifier
                )
        ) {
            if (preset.background.mode == "black") {
                Box(modifier = Modifier.fillMaxSize().background(Color.Black))
            } else {
                Image(
                    bitmap = backdrop.sharp.asImageBitmap(),
                    contentDescription = null,
                    contentScale = ContentScale.Crop,
                    modifier = Modifier.fillMaxSize(),
                )
            }
        }

        val objects = overrideObjects ?: preset.objects
        val byId = remember(objects) { objects.associateBy { it.id } }

        // Resolve masks targeting each object (or parent group)
        val masksByTarget = remember(objects) {
            val map = mutableMapOf<String, MutableList<SceneObject>>()
            for (m in objects) {
                if (!m.visible) continue
                if (m.isMask || m.objType == "wallpaper_mask") {
                    for (tid in m.maskTargetIds) {
                        map.getOrPut(tid) { mutableListOf() }.add(m)
                    }
                    m.maskTargetId?.let { tid ->
                        if (tid.isNotBlank()) map.getOrPut(tid) { mutableListOf() }.add(m)
                    }
                }
            }
            map
        }

        val activeWpMask = objects.firstOrNull { it.objType == "wallpaper_mask" && it.visible }
        val sortedObjects = remember(objects) { objects.sortedBy { it.zIndex } }

        // Split objects into UNDER (behind AI subject cutout) vs OVER (in front of AI subject cutout)
        val wpMaskZ = activeWpMask?.zIndex ?: Int.MAX_VALUE
        val underObjects = mutableListOf<SceneObject>()
        val overObjects = mutableListOf<SceneObject>()

        for (obj in sortedObjects) {
            if (obj.objType == "group" || obj.objType == "wallpaper_mask" || obj.isMask) continue
            val ctxJson = signals.toEvalContextJson(
                scopeId = obj.groupId ?: "@",
                screenW = sw.toDouble(),
                screenH = sh.toDouble(),
                stateValues = stateValues,
                scopeAncestors = scopeAncestors,
                palette = palette,
            )
            if (obj.visibleCondition.isNotBlank() && !evalExpressionCondition(obj.visibleCondition, ctxJson)) {
                continue
            }
            val wb = computeWorldBox(obj, byId, sw, sh, signals, ctxJson)
            if (!wb.effectiveVisible || wb.effectiveOpacity <= 0.005f) continue

            val targetedByWpMask = activeWpMask != null && (
                activeWpMask.maskTargetIds.contains(obj.id) ||
                    (obj.groupId != null && activeWpMask.maskTargetIds.contains(obj.groupId)) ||
                    obj.zIndex < wpMaskZ
                )

            if (depthMask != null && activeWpMask != null && targetedByWpMask) {
                val u0 = ((wb.centerX - wb.width * 0.5f) / sw).coerceIn(0f, 1f)
                val v0 = ((wb.centerY - wb.height * 0.5f) / sh).coerceIn(0f, 1f)
                val u1 = ((wb.centerX + wb.width * 0.5f) / sw).coerceIn(0f, 1f)
                val v1 = ((wb.centerY + wb.height * 0.5f) / sh).coerceIn(0f, 1f)
                val ratio = computeOcclusionRatioFfi(depthMask.occupancyGrid, u0, v0, u1, v1)
                val over = obj.depthCutoutMode == "limit_50" &&
                    evaluateDepthLimitFfi(ratio, obj.depthCutoutThreshold.toFloat(), false)
                if (over) {
                    overObjects.add(obj)
                } else {
                    underObjects.add(obj)
                }
            } else {
                overObjects.add(obj)
            }
        }

        // 1. UNDER layer
        for (obj in underObjects) {
            RenderObjectNode(
                obj = obj,
                byId = byId,
                allObjects = objects,
                sw = sw,
                sh = sh,
                signals = signals,
                stateValues = stateValues,
                scopeAncestors = scopeAncestors,
                palette = palette,
                backdrop = backdrop,
                shaderRegistry = shaderRegistry,
                assetCache = assetCache,
                masks = resolveEffectiveMasks(obj, byId, masksByTarget),
                interactive = interactive,
                onWizAction = onWizAction,
            )
        }

        // 2. Foreground AI Subject Cutout (when wallpaper_mask + depthMask available)
        if (depthMask != null && activeWpMask != null) {
            Image(
                bitmap = depthMask.cutoutBitmap.asImageBitmap(),
                contentDescription = null,
                contentScale = ContentScale.Crop,
                modifier = Modifier.fillMaxSize(),
            )
        }

        // 3. OVER layer (in front of subject)
        for (obj in overObjects) {
            RenderObjectNode(
                obj = obj,
                byId = byId,
                allObjects = objects,
                sw = sw,
                sh = sh,
                signals = signals,
                stateValues = stateValues,
                scopeAncestors = scopeAncestors,
                palette = palette,
                backdrop = backdrop,
                shaderRegistry = shaderRegistry,
                assetCache = assetCache,
                masks = resolveEffectiveMasks(obj, byId, masksByTarget),
                interactive = interactive,
                onWizAction = onWizAction,
            )
        }
    }
}

private fun resolveEffectiveMasks(
    obj: SceneObject,
    byId: Map<String, SceneObject>,
    masksByTarget: Map<String, List<SceneObject>>,
): List<SceneObject> {
    val result = mutableListOf<SceneObject>()
    masksByTarget[obj.id]?.filter { it.isMask && it.objType != "wallpaper_mask" }?.let { result.addAll(it) }
    var gid = obj.groupId
    var depth = 0
    while (gid != null && depth++ < 32) {
        masksByTarget[gid]?.filter { it.isMask && it.objType != "wallpaper_mask" }?.let { result.addAll(it) }
        gid = byId[gid]?.groupId
    }
    return result.distinctBy { it.id }
}

private fun dispatchAction(
    actionStr: String,
    obj: SceneObject,
    signals: DeviceSignals,
    sw: Float,
    sh: Float,
    stateValues: MutableMap<String, Double>,
    scopeAncestors: Map<String, String>,
    palette: MaterialYouPaletteDto,
    allObjects: List<SceneObject>,
    onWizAction: (String, String, String?) -> Unit,
    eventName: String = "onTap",
    depth: Int = 0,
) {
    if (actionStr.isBlank() || depth >= 8) return
    val ctxJson = signals.toEvalContextJson(
        scopeId = obj.groupId ?: "@",
        screenW = sw.toDouble(),
        screenH = sh.toDouble(),
        stateValues = stateValues,
        scopeAncestors = scopeAncestors,
        palette = palette,
    )
    val resJson = executeWidgetActions(actionStr, obj.id, eventName, depth.toUByte(), ctxJson)
    val out = runCatching { AppJson.decodeFromString<ActionOutputDto>(resJson) }.getOrNull() ?: return
    if (out.stateChanged) {
        stateValues.clear()
        stateValues.putAll(out.stateValues)
    }
    for (fx in out.sideEffects) {
        when (fx.type) {
            "mediaCommand" -> fx.command?.let { MediaBridge.dispatchCommand(it) }
            "wizCommand" -> onWizAction(fx.command ?: "toggle", fx.deviceId ?: "primary", fx.arg)
            "fireTarget" -> {
                val targetAlias = fx.targetAlias ?: continue
                val targetEvent = fx.targetEvent ?: "onTap"
                val targetObj = allObjects.firstOrNull { it.alias == targetAlias } ?: continue
                val nextAct = when (targetEvent) {
                    "onDoubleTap" -> targetObj.actions?.onDoubleTap
                    "onLongPress" -> targetObj.actions?.onLongPress
                    "onTapOutside" -> targetObj.actions?.onTapOutside
                    else -> targetObj.actions?.onTap
                } ?: continue
                dispatchAction(
                    nextAct, targetObj, signals, sw, sh, stateValues, scopeAncestors,
                    palette, allObjects, onWizAction, targetEvent, fx.depth ?: (depth + 1)
                )
            }
        }
    }
}

private fun evalNum(obj: SceneObject, prop: String, base: Double, ctxBaseJson: String, signals: DeviceSignals, stateValues: Map<String, Double>, scopeAncestors: Map<String, String>, palette: MaterialYouPaletteDto, sw: Float, sh: Float): Double {
    val expr = obj.expressions[prop]?.trim()
    if (expr.isNullOrEmpty()) return base
    val json = if (base == 0.0) ctxBaseJson else signals.toEvalContextJson(
        scopeId = obj.groupId ?: "@",
        baseValue = base,
        screenW = sw.toDouble(),
        screenH = sh.toDouble(),
        stateValues = stateValues,
        scopeAncestors = scopeAncestors,
        palette = palette,
    )
    return evalExpressionNumeric(expr, prop, json)
}

fun computeWorldBox(
    obj: SceneObject,
    byId: Map<String, SceneObject>,
    sw: Float,
    sh: Float,
    signals: DeviceSignals,
    ctxJson: String,
): ComputedWorldBox {
    var effVisible = obj.visible
    var effOpacity = evalExpressionOr(obj.expressions["elementOpacity"], "elementOpacity", obj.elementOpacity, ctxJson)
    var gx = 0f
    var gy = 0f
    var gRot = 0f
    var gSx = 1f
    var gSy = 1f

    if (obj.groupId != null) {
        val chain = mutableListOf<SceneObject>()
        var curId = obj.groupId
        var guard = 0
        while (curId != null && guard++ < 64) {
            val g = byId[curId] ?: break
            chain.add(g)
            curId = g.groupId
        }
        chain.reverse()
        for ((idx, grp) in chain.withIndex()) {
            if (!grp.visible) effVisible = false
            effOpacity *= grp.elementOpacity
            val gsX = (grp.scaleX * grp.uniformScale).toFloat()
            val gsY = (grp.scaleY * grp.uniformScale).toFloat()
            if (idx == 0) {
                gx = (grp.posXRatio * sw).toFloat()
                gy = (grp.posYRatio * sh).toFloat()
            } else {
                val ox = (grp.groupOffsetX * gSx).toFloat()
                val oy = (grp.groupOffsetY * gSy).toFloat()
                val rad = Math.toRadians(gRot.toDouble())
                gx += (ox * cos(rad) - oy * sin(rad)).toFloat()
                gy += (ox * sin(rad) + oy * cos(rad)).toFloat()
            }
            gRot += grp.rotation.toFloat()
            gSx *= gsX
            gSy *= gsY
        }
    }

    val scaleX = evalExpressionOr(obj.expressions["scaleX"], "scaleX", obj.scaleX, ctxJson).toFloat()
    val scaleY = evalExpressionOr(obj.expressions["scaleY"], "scaleY", obj.scaleY, ctxJson).toFloat()
    val uniScale = evalExpressionOr(obj.expressions["uniformScale"], "uniformScale", obj.uniformScale, ctxJson).toFloat()
    val rot = evalExpressionOr(obj.expressions["rotation"], "rotation", obj.rotation, ctxJson).toFloat()

    val baseW = evalExpressionOr(obj.expressions["baseWidth"] ?: obj.expressions["width"], "baseWidth", obj.baseWidth, ctxJson).toFloat()
    val baseH = evalExpressionOr(obj.expressions["baseHeight"] ?: obj.expressions["height"], "baseHeight", obj.baseHeight, ctxJson).toFloat()

    val totalW = (baseW * scaleX * uniScale * gSx).coerceAtLeast(16f)
    val totalH = (baseH * scaleY * uniScale * gSy).coerceAtLeast(16f)

    val anchorX: Float
    val anchorY: Float
    if (obj.groupId != null && byId.containsKey(obj.groupId)) {
        val offX = evalExpressionOr(obj.expressions["groupOffsetX"], "groupOffsetX", obj.groupOffsetX, ctxJson).toFloat() * gSx
        val offY = evalExpressionOr(obj.expressions["groupOffsetY"], "groupOffsetY", obj.groupOffsetY, ctxJson).toFloat() * gSy
        val rad = Math.toRadians(gRot.toDouble())
        anchorX = gx + (offX * cos(rad) - offY * sin(rad)).toFloat()
        anchorY = gy + (offX * sin(rad) + offY * cos(rad)).toFloat()
    } else {
        val rx = obj.posXRatio
        val ry = obj.posYRatio
        anchorX = evalExpressionOr(obj.expressions["posX"], "posX", rx * sw, ctxJson).toFloat()
        anchorY = evalExpressionOr(obj.expressions["posY"], "posY", ry * sh, ctxJson).toFloat()
    }

    val totalRot = gRot + rot
    val rad = Math.toRadians(totalRot.toDouble())
    val ux = (0.5 - obj.pivotX) * totalW
    val uy = (0.5 - obj.pivotY) * totalH
    val cx = anchorX + (ux * cos(rad) - uy * sin(rad)).toFloat()
    val cy = anchorY + (ux * sin(rad) + uy * cos(rad)).toFloat()

    return ComputedWorldBox(
        anchorX = anchorX,
        anchorY = anchorY,
        centerX = cx,
        centerY = cy,
        width = totalW,
        height = totalH,
        rotationDeg = totalRot,
        scaleX = scaleX * uniScale * gSx,
        scaleY = scaleY * uniScale * gSy,
        effectiveOpacity = effOpacity.toFloat().coerceIn(0f, 1f),
        effectiveVisible = effVisible,
    )
}

private fun evalExpressionOr(expr: String?, prop: String, fallback: Double, ctxJson: String): Double {
    if (expr.isNullOrBlank()) return fallback
    return evalExpressionNumeric(expr, prop, ctxJson)
}

@Composable
private fun RenderObjectNode(
    obj: SceneObject,
    byId: Map<String, SceneObject>,
    allObjects: List<SceneObject>,
    sw: Float,
    sh: Float,
    signals: DeviceSignals,
    stateValues: MutableMap<String, Double>,
    scopeAncestors: Map<String, String>,
    palette: MaterialYouPaletteDto,
    backdrop: BackdropBitmaps,
    shaderRegistry: ShaderRegistry,
    assetCache: SceneAssetCache,
    masks: List<SceneObject>,
    interactive: Boolean,
    onWizAction: (String, String, String?) -> Unit,
) {
    val density = LocalDensity.current
    val ctxJson = signals.toEvalContextJson(
        scopeId = obj.groupId ?: "@",
        screenW = sw.toDouble(),
        screenH = sh.toDouble(),
        stateValues = stateValues,
        scopeAncestors = scopeAncestors,
        palette = palette,
    )
    val wb = computeWorldBox(obj, byId, sw, sh, signals, ctxJson)
    val baseFillArgb = if (obj.isCustomColor) {
        parseHexColorInt(obj.customHex)
    } else {
        resolvePaletteTokenInt(obj.textColorChoice, palette)
    }
    val fillExpr = obj.expressions["textColor"] ?: obj.expressions["fillColor"]
    val resolvedFillArgb = if (!fillExpr.isNullOrBlank()) {
        evalExpressionColorArgb(fillExpr, baseFillArgb.toUInt(), ctxJson).toInt()
    } else {
        baseFillArgb
    }
    val fillAlpha = evalExpressionOr(obj.expressions["fillAlpha"], "fillAlpha", obj.fillAlpha, ctxJson)
        .toFloat().coerceIn(0f, 1f)
    val fillColor = Color(resolvedFillArgb).copy(alpha = Color(resolvedFillArgb).alpha * fillAlpha)

    val strokeBaseArgb = if (obj.isCustomStrokeColor) {
        parseHexColorInt(obj.customStrokeHex)
    } else {
        resolvePaletteTokenInt(obj.strokeColorChoice, palette)
    }
    val strokeExpr = obj.expressions["strokeColor"]
    val strokeArgb = if (!strokeExpr.isNullOrBlank()) {
        evalExpressionColorArgb(strokeExpr, strokeBaseArgb.toUInt(), ctxJson).toInt()
    } else {
        strokeBaseArgb
    }

    val widthDp = with(density) { wb.width.toDp() }
    val heightDp = with(density) { wb.height.toDp() }
    val leftPx = (wb.centerX - wb.width * 0.5f).roundToInt()
    val topPx = (wb.centerY - wb.height * 0.5f).roundToInt()

    val hasHitbox = interactive && ((obj.hitbox?.enabled == true) ||
        (obj.actions != null && (obj.actions.onTap.isNotBlank() || obj.actions.onDoubleTap.isNotBlank() || obj.actions.onLongPress.isNotBlank())))

    val camDist = (obj.perspectiveDistance.toFloat().coerceIn(200f, 2000f) / 48f).coerceAtLeast(8f)
    val hasMask = masks.isNotEmpty()

    Box(
        modifier = Modifier
            .offset { IntOffset(leftPx, topPx) }
            .size(widthDp, heightDp)
            .graphicsLayer {
                alpha = wb.effectiveOpacity
                rotationZ = wb.rotationDeg
                rotationX = obj.perspectiveY.toFloat().coerceIn(-60f, 60f)
                rotationY = obj.perspectiveX.toFloat().coerceIn(-60f, 60f)
                cameraDistance = camDist
                transformOrigin = TransformOrigin(obj.pivotX.toFloat(), obj.pivotY.toFloat())
                if (hasMask) {
                    compositingStrategy = CompositingStrategy.Offscreen
                }
            }
            .then(
                if (hasHitbox) {
                    Modifier.pointerInput(obj.id, obj.actions, obj.wizAction) {
                        detectTapGestures(
                            onTap = {
                                val act = obj.actions?.onTap?.trim().orEmpty()
                                if (act.isNotEmpty()) {
                                    dispatchAction(
                                        act, obj, signals, sw, sh, stateValues, scopeAncestors,
                                        palette, allObjects, onWizAction, "onTap"
                                    )
                                } else if (obj.objType == "wiz_button") {
                                    onWizAction(obj.wizAction ?: "toggle", obj.wizDeviceId ?: "primary", obj.wizSceneId?.toString())
                                } else if (obj.objType.startsWith("media_")) {
                                    val cmd = when (obj.objType) {
                                        "media_prev" -> "prev"
                                        "media_next" -> "next"
                                        else -> obj.mediaAction
                                    }
                                    MediaBridge.dispatchCommand(cmd)
                                }
                            },
                            onDoubleTap = {
                                obj.actions?.onDoubleTap?.takeIf { it.isNotBlank() }?.let { act ->
                                    dispatchAction(
                                        act, obj, signals, sw, sh, stateValues, scopeAncestors,
                                        palette, allObjects, onWizAction, "onDoubleTap"
                                    )
                                }
                            },
                            onLongPress = {
                                obj.actions?.onLongPress?.takeIf { it.isNotBlank() }?.let { act ->
                                    dispatchAction(
                                        act, obj, signals, sw, sh, stateValues, scopeAncestors,
                                        palette, allObjects, onWizAction, "onLongPress"
                                    )
                                }
                            }
                        )
                    }
                } else Modifier
            )
    ) {
        when (obj.objType) {
            "clock", "date", "custom_text", "weather_temp",
            "media_title", "media_artist", "media_position", "media_duration" -> {
                val displayText = resolveTextContent(obj, signals, ctxJson)
                val fontSizePx = evalExpressionOr(
                    obj.expressions["fontSize"] ?: obj.expressions["baseFontSize"],
                    "baseFontSize",
                    obj.baseFontSize,
                    ctxJson
                ).toFloat() * wb.scaleY
                val letterSp = evalExpressionOr(
                    obj.expressions["letterSpacing"],
                    "letterSpacing",
                    obj.letterSpacing,
                    ctxJson
                ).toFloat()
                val typeface = remember(obj.customFontFamily, obj.fontFileName, obj.fontSourceId, obj.fontWeight, obj.fontItalic) {
                    assetCache.resolveTypeface(obj)
                }

                RenderTextWidget(
                    obj = obj,
                    text = displayText,
                    fontSizePx = fontSizePx.coerceIn(8f, 500f),
                    letterSpacingPx = letterSp,
                    typeface = typeface,
                    fillColor = fillColor,
                    strokeColor = Color(strokeArgb),
                    palette = palette,
                    wb = wb,
                    backdrop = backdrop,
                    shaderRegistry = shaderRegistry,
                )
            }
            "shape" -> {
                RenderShapeWidget(
                    obj = obj,
                    wb = wb,
                    fillColor = fillColor,
                    strokeColor = Color(strokeArgb),
                    palette = palette,
                    backdrop = backdrop,
                    shaderRegistry = shaderRegistry,
                    signals = signals,
                )
            }
            "svg_icon", "weather_icon", "media_prev", "media_play_pause",
            "media_next", "media_shuffle", "media_loop", "media_button" -> {
                RenderIconWidget(
                    obj = obj,
                    signals = signals,
                    fillColor = fillColor,
                    assetCache = assetCache,
                )
            }
            "battery" -> {
                RenderBatteryWidget(signals, fillColor, Color(resolvePaletteTokenInt("primary", palette)))
            }
            "media" -> {
                RenderMediaCardWidget(signals, fillColor, Color(resolvePaletteTokenInt("primary", palette)))
            }
            "media_art" -> {
                val art = signals.media.albumArt
                if (art != null) {
                    Image(
                        bitmap = art.asImageBitmap(),
                        contentDescription = null,
                        contentScale = ContentScale.Crop,
                        modifier = Modifier
                            .fillMaxSize()
                            .clip(RoundedCornerShape(obj.radiusUniform.dp)),
                    )
                } else if (!obj.hideWhenNoCover) {
                    Box(
                        modifier = Modifier
                            .fillMaxSize()
                            .clip(RoundedCornerShape(obj.radiusUniform.dp))
                            .background(fillColor.copy(alpha = 0.25f)),
                        contentAlignment = Alignment.Center,
                    ) {
                        Icon(Icons.Rounded.MusicNote, contentDescription = null, tint = fillColor)
                    }
                }
            }
            "wiz_button" -> {
                RenderWizButtonWidget(signals, fillColor, Color(resolvePaletteTokenInt("primary", palette)))
            }
        }

        // Apply shape masks targeting this object inside the Offscreen layer
        if (hasMask) {
            Canvas(modifier = Modifier.fillMaxSize()) {
                for (maskObj in masks) {
                    val mCtx = signals.toEvalContextJson(
                        scopeId = maskObj.groupId ?: "@",
                        screenW = sw.toDouble(),
                        screenH = sh.toDouble(),
                        stateValues = stateValues,
                        scopeAncestors = scopeAncestors,
                        palette = palette,
                    )
                    val mBox = computeWorldBox(maskObj, byId, sw, sh, signals, mCtx)
                    val mPath = ShapePathBuilder.buildShapePath(maskObj, mBox.width, mBox.height, 0f)
                    val relLeft = (mBox.centerX - mBox.width * 0.5f) - (wb.centerX - wb.width * 0.5f)
                    val relTop = (mBox.centerY - mBox.height * 0.5f) - (wb.centerY - wb.height * 0.5f)
                    drawIntoCanvas { canvas ->
                        canvas.save()
                        canvas.translate(relLeft, relTop)
                        if (abs(mBox.rotationDeg - wb.rotationDeg) > 0.01f) {
                            canvas.rotate(mBox.rotationDeg - wb.rotationDeg)
                        }
                        val blend = if (maskObj.maskMode == "clipping") BlendMode.DstIn else BlendMode.DstOut
                        drawPath(path = mPath, color = Color.White, blendMode = blend)
                        canvas.restore()
                    }
                }
            }
        }
    }
}

private fun resolveTextContent(obj: SceneObject, signals: DeviceSignals, ctxJson: String): String {
    val h = signals.hour
    val m = signals.minute
    val s = signals.second.toInt().coerceIn(0, 59)
    return when (obj.objType) {
        "clock" -> when (obj.timeFormat) {
            0 -> "%02d:%02d".format(h, m)
            1 -> "%02d:%02d:%02d".format(h, m, s)
            2 -> {
                val h12 = if (h % 12 == 0) 12 else h % 12
                val ap = if (h < 12) "AM" else "PM"
                "%02d:%02d %s".format(h12, m, ap)
            }
            3 -> "%02d".format(h)
            4 -> "%02d".format(m)
            5 -> "%02d".format(s)
            6 -> "%02d\n%02d".format(h, m)
            7 -> "%02d".format(h)[0].toString()
            8 -> "%02d".format(h)[1].toString()
            9 -> "%02d".format(m)[0].toString()
            10 -> "%02d".format(m)[1].toString()
            else -> "%02d:%02d".format(h, m)
        }
        "date" -> when (obj.dateFormat) {
            1 -> SimpleDateFormat("dd/MM/yyyy", Locale("es", "AR")).format(Date(signals.nowEpochMs))
            2 -> SimpleDateFormat("MMM d, yyyy", Locale("es", "AR")).format(Date(signals.nowEpochMs))
            else -> signals.dateText
        }
        "custom_text" -> interpolateCustomText(obj.customContent, ctxJson)
        "weather_temp" -> signals.weatherTemp.ifBlank { "--°" }
        "media_title" -> signals.media.title.ifBlank { "Sin reproducción" }
        "media_artist" -> signals.media.artist
        "media_position" -> formatSeconds(signals.media.positionSec)
        "media_duration" -> formatSeconds(signals.media.durationSec)
        else -> obj.customContent
    }
}

private fun formatSeconds(sec: Double): String {
    val total = sec.toInt().coerceAtLeast(0)
    return "%d:%02d".format(total / 60, total % 60)
}

@Composable
private fun RenderTextWidget(
    obj: SceneObject,
    text: String,
    fontSizePx: Float,
    letterSpacingPx: Float,
    typeface: Typeface,
    fillColor: Color,
    strokeColor: Color,
    palette: MaterialYouPaletteDto,
    wb: ComputedWorldBox,
    backdrop: BackdropBitmaps,
    shaderRegistry: ShaderRegistry,
) {
    val useGlass = (obj.liquidGlassEnabled || obj.shaderId == "liquid_glass") &&
        Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU
    val useGrad = obj.gradient?.enabled == true && Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU

    if (useGlass || useGrad) {
        Canvas(modifier = Modifier.fillMaxSize()) {
            val w = size.width.roundToInt().coerceAtLeast(2)
            val h = size.height.roundToInt().coerceAtLeast(2)
            val maskBmp = rasterizeTextAlphaBitmap(
                text = text,
                w = w,
                h = h,
                fontSizePx = fontSizePx,
                letterSpacingPx = letterSpacingPx,
                typeface = typeface,
                align = obj.textAlign,
            )

            if (useGlass && Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
                val softMask = downscaleAndBlurBitmap(maskBmp, scaleFactor = 2, blurPasses = 2)
                val softFull = Bitmap.createScaledBitmap(softMask, w, h, true)
                softMask.recycle()

                val tintArgb = android.graphics.Color.argb(
                    (fillColor.alpha * 255f).roundToInt(),
                    (fillColor.red * 255f).roundToInt(),
                    (fillColor.green * 255f).roundToInt(),
                    (fillColor.blue * 255f).roundToInt(),
                )
                val shader = shaderRegistry.buildLiquidGlassShader(
                    obj = obj,
                    itemW = size.width,
                    itemH = size.height,
                    centerX = wb.centerX,
                    centerY = wb.centerY,
                    rotDeg = wb.rotationDeg,
                    backdropSharp = backdrop.sharp,
                    backdropBlur = backdrop.blurred,
                    maskBitmap = maskBmp,
                    maskSoftBitmap = softFull,
                    tintBitmap = null,
                    tintArgb = tintArgb,
                    shapeMode = 1f,
                    opacity = 1f,
                )
                if (shader != null) {
                    drawRect(brush = ShaderBrush(shader))
                } else {
                    drawIntoCanvas { it.nativeCanvas.drawBitmap(maskBmp, 0f, 0f, null) }
                }
                softFull.recycle()
                maskBmp.recycle()
                return@Canvas
            }

            if (useGrad && Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
                val shader = shaderRegistry.buildGradientShader(
                    spec = obj.gradient!!,
                    itemW = size.width,
                    itemH = size.height,
                    palette = palette,
                    opacity = 1f,
                    sourceMask = maskBmp,
                )
                if (shader != null) {
                    drawRect(brush = ShaderBrush(shader))
                    maskBmp.recycle()
                    return@Canvas
                }
            }
            maskBmp.recycle()
        }
        return
    }

    val density = LocalDensity.current
    val fontSizeSp = with(density) { fontSizePx.toSp() }
    val letterSpacingSp = with(density) { letterSpacingPx.toSp() }
    val textAlign = when (obj.textAlign) {
        "left" -> TextAlign.Start
        "right" -> TextAlign.End
        "justify" -> TextAlign.Justify
        else -> TextAlign.Center
    }
    val shadowModifier = if (obj.shadowEnabled) {
        Modifier.graphicsLayer {
            shadowElevation = (obj.shadowBlur.toFloat() * 16f).coerceAtLeast(1f)
        }
    } else Modifier

    Box(
        modifier = Modifier
            .fillMaxSize()
            .then(shadowModifier),
        contentAlignment = when (obj.textAlign) {
            "left" -> Alignment.CenterStart
            "right" -> Alignment.CenterEnd
            else -> Alignment.Center
        }
    ) {
        if (obj.shadowEnabled) {
            val sColor = if (obj.isCustomShadowColor) Color(parseHexColorInt(obj.customShadowHex))
            else Color(resolvePaletteTokenInt(obj.shadowColorChoice, palette))
            Text(
                text = text,
                color = sColor.copy(alpha = obj.customShadowAlpha.toFloat()),
                fontSize = fontSizeSp,
                letterSpacing = letterSpacingSp,
                fontFamily = FontFamily(typeface),
                fontWeight = FontWeight(obj.fontWeight.coerceIn(100, 900)),
                fontStyle = if (obj.fontItalic) FontStyle.Italic else FontStyle.Normal,
                textAlign = textAlign,
                lineHeight = fontSizeSp * 0.96f,
                modifier = Modifier.offset {
                    IntOffset(obj.shadowOffsetX.roundToInt(), obj.shadowOffsetY.roundToInt())
                },
            )
        }
        if (obj.strokeEnabled) {
            Text(
                text = text,
                color = strokeColor,
                fontSize = fontSizeSp,
                letterSpacing = letterSpacingSp,
                fontFamily = FontFamily(typeface),
                fontWeight = FontWeight(obj.fontWeight.coerceIn(100, 900)),
                fontStyle = if (obj.fontItalic) FontStyle.Italic else FontStyle.Normal,
                textAlign = textAlign,
                lineHeight = fontSizeSp * 0.96f,
                style = androidx.compose.ui.text.TextStyle(
                    drawStyle = Stroke(
                        width = obj.strokeWidth.toFloat().coerceAtLeast(1f),
                        join = StrokeJoin.Round,
                    )
                ),
            )
        }
        Text(
            text = text,
            color = fillColor,
            fontSize = fontSizeSp,
            letterSpacing = letterSpacingSp,
            fontFamily = FontFamily(typeface),
            fontWeight = FontWeight(obj.fontWeight.coerceIn(100, 900)),
            fontStyle = if (obj.fontItalic) FontStyle.Italic else FontStyle.Normal,
            textAlign = textAlign,
            lineHeight = fontSizeSp * 0.96f,
        )
    }
}

private fun rasterizeTextAlphaBitmap(
    text: String,
    w: Int,
    h: Int,
    fontSizePx: Float,
    letterSpacingPx: Float,
    typeface: Typeface,
    align: String,
): Bitmap {
    val bmp = Bitmap.createBitmap(w, h, Bitmap.Config.ARGB_8888)
    val canvas = AndroidCanvas(bmp)
    val tp = TextPaint(AndroidPaint.ANTI_ALIAS_FLAG).apply {
        color = android.graphics.Color.WHITE
        textSize = fontSizePx
        this.typeface = typeface
        if (fontSizePx > 1f) letterSpacing = letterSpacingPx / fontSizePx
    }
    val alignment = when (align) {
        "left" -> Layout.Alignment.ALIGN_NORMAL
        "right" -> Layout.Alignment.ALIGN_OPPOSITE
        else -> Layout.Alignment.ALIGN_CENTER
    }
    val layout = StaticLayout.Builder.obtain(text, 0, text.length, tp, w)
        .setAlignment(alignment)
        .setIncludePad(false)
        .build()
    val dy = ((h - layout.height) * 0.5f).coerceAtLeast(0f)
    canvas.save()
    canvas.translate(0f, dy)
    layout.draw(canvas)
    canvas.restore()
    return bmp
}

@Composable
private fun RenderShapeWidget(
    obj: SceneObject,
    wb: ComputedWorldBox,
    fillColor: Color,
    strokeColor: Color,
    palette: MaterialYouPaletteDto,
    backdrop: BackdropBitmaps,
    shaderRegistry: ShaderRegistry,
    signals: DeviceSignals,
) {
    val shapeMode = when (obj.shapeType.lowercase()) {
        "circle" -> 2f
        "triangle", "polygon" -> 3f
        "star" -> 4f
        else -> 0f
    }
    val useGlass = (obj.liquidGlassEnabled || obj.shaderId == "liquid_glass") &&
        Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU
    val customShaderId = obj.shaderId?.takeIf { it != "liquid_glass" && it.isNotBlank() }

    val blurMod = if (!useGlass && obj.blurEnabled && Build.VERSION.SDK_INT >= Build.VERSION_CODES.S) {
        val r = (obj.blurAmount * obj.blurMax).toFloat().coerceIn(1f, 80f)
        Modifier.graphicsLayer {
            renderEffect = RenderEffect.createBlurEffect(r, r, Shader.TileMode.CLAMP).asComposeRenderEffect()
        }
    } else Modifier

    Canvas(modifier = Modifier.fillMaxSize().then(blurMod)) {
        val inset = if (obj.strokeEnabled) obj.strokeWidth.toFloat() * 0.5f else 0f
        val path = ShapePathBuilder.buildShapePath(obj, size.width, size.height, inset)

        if (obj.shadowEnabled) {
            val sColor = if (obj.isCustomShadowColor) Color(parseHexColorInt(obj.customShadowHex))
            else Color(resolvePaletteTokenInt(obj.shadowColorChoice, palette))
            val shadowPath = ShapePathBuilder.buildShapePath(obj, size.width, size.height, inset)
            drawIntoCanvas { c ->
                c.save()
                c.translate(obj.shadowOffsetX.toFloat(), obj.shadowOffsetY.toFloat())
                drawPath(shadowPath, color = sColor.copy(alpha = obj.customShadowAlpha.toFloat() * 0.6f))
                c.restore()
            }
        }

        if (useGlass && Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
            val tintArgb = android.graphics.Color.argb(
                (fillColor.alpha * 255f).roundToInt(),
                (fillColor.red * 255f).roundToInt(),
                (fillColor.green * 255f).roundToInt(),
                (fillColor.blue * 255f).roundToInt(),
            )
            val tintBmp = obj.tintGradient?.takeIf { it.enabled }?.let { tg ->
                shaderRegistry.buildGradientShader(tg, size.width, size.height, palette, 1f)?.let { gs ->
                    val b = Bitmap.createBitmap(size.width.roundToInt().coerceAtLeast(2), size.height.roundToInt().coerceAtLeast(2), Bitmap.Config.ARGB_8888)
                    val p = AndroidPaint().apply { shader = gs }
                    AndroidCanvas(b).drawRect(0f, 0f, b.width.toFloat(), b.height.toFloat(), p)
                    b
                }
            }
            val shader = shaderRegistry.buildLiquidGlassShader(
                obj = obj,
                itemW = size.width,
                itemH = size.height,
                centerX = wb.centerX,
                centerY = wb.centerY,
                rotDeg = wb.rotationDeg,
                backdropSharp = backdrop.sharp,
                backdropBlur = backdrop.blurred,
                maskBitmap = null,
                maskSoftBitmap = null,
                tintBitmap = tintBmp,
                tintArgb = tintArgb,
                shapeMode = shapeMode,
                opacity = 1f,
            )
            if (shader != null) {
                drawPath(path = path, brush = ShaderBrush(shader))
                tintBmp?.recycle()
            } else {
                drawPath(path = path, color = fillColor)
                tintBmp?.recycle()
            }
        } else if (customShaderId != null && Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
            val customShader = shaderRegistry.buildCustomShader(
                customShaderId, size.width, size.height, signals.tSeconds.toFloat(), backdrop.sharp, obj.shaderParams
            )
            if (customShader != null) {
                drawPath(path = path, brush = ShaderBrush(customShader))
            } else {
                drawPath(path = path, color = fillColor)
            }
        } else if (obj.gradient?.enabled == true && Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
            val gShader = shaderRegistry.buildGradientShader(obj.gradient, size.width, size.height, palette, 1f)
            if (gShader != null) {
                drawPath(path = path, brush = ShaderBrush(gShader))
            } else {
                drawPath(path = path, color = fillColor)
            }
        } else {
            drawPath(path = path, color = fillColor)
        }

        if (obj.strokeEnabled && obj.strokeWidth > 0.1) {
            val sw = obj.strokeWidth.toFloat()
            if (obj.strokeGradient?.enabled == true && Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
                val sg = shaderRegistry.buildGradientShader(obj.strokeGradient, size.width, size.height, palette, 1f)
                if (sg != null) {
                    drawPath(path = path, brush = ShaderBrush(sg), style = Stroke(width = sw, join = StrokeJoin.Round))
                } else {
                    drawPath(path = path, color = strokeColor, style = Stroke(width = sw, join = StrokeJoin.Round))
                }
            } else {
                drawPath(path = path, color = strokeColor, style = Stroke(width = sw, join = StrokeJoin.Round))
            }
        }
    }
}

@Composable
private fun RenderIconWidget(
    obj: SceneObject,
    signals: DeviceSignals,
    fillColor: Color,
    assetCache: SceneAssetCache,
) {
    val customAsset = obj.svgSource?.removePrefix("asset:")?.takeIf { obj.svgSource?.startsWith("asset:") == true }
    if (customAsset != null) {
        val svg = remember(customAsset) { assetCache.loadSvgAsset(customAsset) }
        if (svg != null) {
            Canvas(modifier = Modifier.fillMaxSize()) {
                drawIntoCanvas { canvas ->
                    svg.setDocumentWidth(size.width)
                    svg.setDocumentHeight(size.height)
                    svg.renderToCanvas(canvas.nativeCanvas)
                }
            }
            return
        }
    }

    val icon: ImageVector = when (obj.objType) {
        "media_prev" -> Icons.Rounded.SkipPrevious
        "media_next" -> Icons.Rounded.SkipNext
        "media_play_pause" -> if (signals.media.isPlaying) Icons.Rounded.Pause else Icons.Rounded.PlayArrow
        "media_shuffle" -> Icons.Rounded.Shuffle
        "media_loop" -> Icons.Rounded.Repeat
        "weather_icon" -> weatherCodeToIcon(signals.weatherCode)
        else -> {
            val name = obj.svgSource?.removePrefix("material:") ?: obj.iconName
            materialIconByName(name)
        }
    }
    Box(modifier = Modifier.fillMaxSize(), contentAlignment = Alignment.Center) {
        Icon(
            imageVector = icon,
            contentDescription = obj.name,
            tint = fillColor,
            modifier = Modifier.fillMaxSize(0.85f),
        )
    }
}

@Composable
private fun RenderBatteryWidget(signals: DeviceSignals, textColor: Color, accentColor: Color) {
    Row(
        modifier = Modifier.fillMaxSize().padding(horizontal = 8.dp),
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.Center,
    ) {
        Icon(
            imageVector = if (signals.isCharging) Icons.Rounded.BatteryChargingFull else Icons.Rounded.BatteryFull,
            contentDescription = null,
            tint = if (signals.isCharging) accentColor else textColor,
            modifier = Modifier.size(22.dp),
        )
        Spacer(modifier = Modifier.width(6.dp))
        Text(
            text = "${signals.batteryLevel}%",
            color = textColor,
            style = MaterialTheme.typography.titleMedium,
            fontWeight = FontWeight.SemiBold,
        )
    }
}

@Composable
private fun RenderMediaCardWidget(signals: DeviceSignals, textColor: Color, accentColor: Color) {
    val m = signals.media
    Row(
        modifier = Modifier.fillMaxSize().padding(10.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        if (m.albumArt != null) {
            Image(
                bitmap = m.albumArt.asImageBitmap(),
                contentDescription = null,
                contentScale = ContentScale.Crop,
                modifier = Modifier.size(64.dp).clip(RoundedCornerShape(14.dp)),
            )
        } else {
            Box(
                modifier = Modifier
                    .size(64.dp)
                    .clip(RoundedCornerShape(14.dp))
                    .background(accentColor.copy(alpha = 0.2f)),
                contentAlignment = Alignment.Center,
            ) {
                Icon(Icons.Rounded.MusicNote, contentDescription = null, tint = accentColor)
            }
        }
        Spacer(modifier = Modifier.width(12.dp))
        Column(modifier = Modifier.weight(1f)) {
            Text(
                text = m.title.ifBlank { "Sin música" },
                color = textColor,
                style = MaterialTheme.typography.titleMedium,
                maxLines = 1,
                overflow = TextOverflow.Ellipsis,
            )
            if (m.artist.isNotBlank()) {
                Text(
                    text = m.artist,
                    color = textColor.copy(alpha = 0.72f),
                    style = MaterialTheme.typography.bodySmall,
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis,
                )
            }
            Spacer(modifier = Modifier.height(6.dp))
            LinearProgressIndicator(
                progress = { m.progress.toFloat().coerceIn(0f, 1f) },
                color = accentColor,
                trackColor = textColor.copy(alpha = 0.2f),
                modifier = Modifier.height(4.dp),
            )
        }
    }
}

@Composable
private fun RenderWizButtonWidget(signals: DeviceSignals, textColor: Color, accentColor: Color) {
    val w = signals.wizStatus
    val bulbTint = if (w.isOn) {
        runCatching { Color(parseHexColorInt(w.hex)) }.getOrDefault(accentColor)
    } else {
        textColor.copy(alpha = 0.45f)
    }
    Row(
        modifier = Modifier.fillMaxSize().padding(horizontal = 12.dp),
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.SpaceBetween,
    ) {
        Row(verticalAlignment = Alignment.CenterVertically) {
            Icon(
                imageVector = Icons.Rounded.Lightbulb,
                contentDescription = null,
                tint = bulbTint,
                modifier = Modifier.size(28.dp),
            )
            Spacer(modifier = Modifier.width(10.dp))
            Column {
                Text(
                    text = w.name,
                    color = textColor,
                    style = MaterialTheme.typography.titleSmall,
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis,
                )
                Text(
                    text = if (w.isOn) "Encendida • ${w.dimming}%" else "Apagada",
                    color = textColor.copy(alpha = 0.7f),
                    style = MaterialTheme.typography.bodySmall,
                )
            }
        }
    }
}

fun weatherCodeToIcon(code: Int): ImageVector = when (code) {
    0, 1 -> Icons.Rounded.WbSunny
    2, 3, 45, 48 -> Icons.Rounded.Cloud
    51, 53, 55, 61, 63, 65, 80, 81, 82 -> Icons.Rounded.Grain
    71, 73, 75, 77, 85, 86 -> Icons.Rounded.AcUnit
    95, 96, 99 -> Icons.Rounded.Thunderstorm
    else -> Icons.Rounded.WbSunny
}

fun materialIconByName(name: String): ImageVector = when (name.lowercase()) {
    "lightbulb", "bulb", "lamp" -> Icons.Rounded.Lightbulb
    "bedtime", "night" -> Icons.Rounded.Bedtime
    "music", "music_note" -> Icons.Rounded.MusicNote
    "bolt", "charging" -> Icons.Rounded.Bolt
    "sun", "wb_sunny" -> Icons.Rounded.WbSunny
    "cloud" -> Icons.Rounded.Cloud
    "play" -> Icons.Rounded.PlayArrow
    "pause" -> Icons.Rounded.Pause
    else -> Icons.Rounded.Lightbulb
}
