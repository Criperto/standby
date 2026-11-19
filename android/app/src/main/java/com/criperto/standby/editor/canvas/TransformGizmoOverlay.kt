package com.criperto.standby.editor.canvas

import androidx.compose.foundation.Canvas
import androidx.compose.foundation.gestures.detectDragGestures
import androidx.compose.foundation.gestures.detectTapGestures
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberUpdatedState
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.Path
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.graphics.drawscope.withTransform
import androidx.compose.ui.hapticfeedback.HapticFeedbackType
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.platform.LocalHapticFeedback
import com.criperto.standby.core.SceneObject
import com.criperto.standby.core.WorldTransformDto
import kotlin.math.abs
import kotlin.math.atan2
import kotlin.math.cos
import kotlin.math.hypot
import kotlin.math.max
import kotlin.math.sin

private enum class GizmoDragPart {
    NONE,
    MOVE,
    ROTATE,
    PIVOT,
    CORNER_TL,
    CORNER_TR,
    CORNER_BR,
    CORNER_BL,
    EDGE_L,
    EDGE_R,
    EDGE_T,
    EDGE_B,
    SHAPE_PARAM,
    GRAD_P0,
    GRAD_P1,
}

@Composable
fun TransformGizmoOverlay(
    primary: SceneObject?,
    worldBox: WorldTransformDto?,
    screenW: Float,
    screenH: Float,
    onHitTestObjectAt: (Offset) -> SceneObject?,
    onSelectObject: (String?) -> Unit,
    onBeginDrag: () -> Unit,
    onEndDrag: () -> Unit,
    onMoveBy: (dx: Float, dy: Float) -> Boolean,
    onResizeFactor: (fx: Float, fy: Float) -> Unit,
    onRotateTo: (angleDeg: Float) -> Unit,
    onSetPivot: (px: Double, py: Double) -> Unit,
    onUpdateShapeParam: (Double) -> Unit,
    onUpdateGradientEndpoint: (isP0: Boolean, normX: Double, normY: Double) -> Unit,
    modifier: Modifier = Modifier,
) {
    val haptics = LocalHapticFeedback.current
    val curPrimary by rememberUpdatedState(primary)
    val curBox by rememberUpdatedState(worldBox)
    val curHitTest by rememberUpdatedState(onHitTestObjectAt)
    val curSelect by rememberUpdatedState(onSelectObject)

    var activePart by remember { mutableStateOf(GizmoDragPart.NONE) }

    fun worldToLocal(pt: Offset, box: WorldTransformDto): Offset {
        val dx = pt.x - box.visualCenterX.toFloat()
        val dy = pt.y - box.visualCenterY.toFloat()
        val rad = Math.toRadians(-box.rotationDeg)
        val lx = (dx * cos(rad) - dy * sin(rad)).toFloat()
        val ly = (dx * sin(rad) + dy * cos(rad)).toFloat()
        return Offset(lx, ly)
    }

    fun pickHandle(pt: Offset, obj: SceneObject, box: WorldTransformDto): GizmoDragPart {
        val local = worldToLocal(pt, box)
        val hw = (box.width.toFloat().coerceAtLeast(32f)) / 2f
        val hh = (box.height.toFloat().coerceAtLeast(32f)) / 2f
        val hitR = 32f

        // 1. Rotation stem above top edge
        val rotHandle = Offset(0f, -hh - 38f)
        if (hypot(local.x - rotHandle.x, local.y - rotHandle.y) <= hitR) {
            return GizmoDragPart.ROTATE
        }

        // 2. Gradient endpoints when gradient is enabled
        val grad = obj.gradient
        if (grad?.enabled == true) {
            val p0Local = Offset(
                ((grad.p0x - 0.5) * box.width).toFloat(),
                ((grad.p0y - 0.5) * box.height).toFloat(),
            )
            val p1Local = Offset(
                ((grad.p1x - 0.5) * box.width).toFloat(),
                ((grad.p1y - 0.5) * box.height).toFloat(),
            )
            if (hypot(local.x - p0Local.x, local.y - p0Local.y) <= 26f) return GizmoDragPart.GRAD_P0
            if (hypot(local.x - p1Local.x, local.y - p1Local.y) <= 26f) return GizmoDragPart.GRAD_P1
        }

        // 3. 4 corners
        if (hypot(local.x - (-hw), local.y - (-hh)) <= hitR) return GizmoDragPart.CORNER_TL
        if (hypot(local.x - hw, local.y - (-hh)) <= hitR) return GizmoDragPart.CORNER_TR
        if (hypot(local.x - hw, local.y - hh) <= hitR) return GizmoDragPart.CORNER_BR
        if (hypot(local.x - (-hw), local.y - hh) <= hitR) return GizmoDragPart.CORNER_BL

        // 4. 4 edges
        if (hypot(local.x - (-hw), local.y) <= hitR) return GizmoDragPart.EDGE_L
        if (hypot(local.x - hw, local.y) <= hitR) return GizmoDragPart.EDGE_R
        if (hypot(local.x, local.y - (-hh)) <= hitR) return GizmoDragPart.EDGE_T
        if (hypot(local.x, local.y - hh) <= hitR) return GizmoDragPart.EDGE_B

        // 5. Shape param handle (top-left inside corner)
        if (obj.objType == "shape") {
            val spHandle = Offset(-hw + 22f, -hh + 22f)
            if (hypot(local.x - spHandle.x, local.y - spHandle.y) <= 22f) {
                return GizmoDragPart.SHAPE_PARAM
            }
        }

        // 6. Pivot diamond (only if fairly close so interior move stays easy)
        val pivLocal = Offset(
            ((obj.pivotX - 0.5) * box.width).toFloat(),
            ((obj.pivotY - 0.5) * box.height).toFloat(),
        )
        if (hypot(local.x - pivLocal.x, local.y - pivLocal.y) <= 18f) {
            return GizmoDragPart.PIVOT
        }

        // 7. Interior move
        if (local.x in (-hw - 10f)..(hw + 10f) && local.y in (-hh - 10f)..(hh + 10f)) {
            return GizmoDragPart.MOVE
        }
        return GizmoDragPart.NONE
    }

    Canvas(
        modifier = modifier
            .fillMaxSize()
            .pointerInput(screenW, screenH) {
                detectTapGestures { tapOffset ->
                    val hit = curHitTest(tapOffset)
                    curSelect(hit?.id)
                }
            }
            .pointerInput(screenW, screenH) {
                detectDragGestures(
                    onDragStart = { startOffset ->
                        val p = curPrimary
                        val b = curBox
                        val initialPick = if (p != null && b != null) {
                            pickHandle(startOffset, p, b)
                        } else {
                            GizmoDragPart.NONE
                        }

                        // If user didn't grab a specific resize/rotate/pivot handle, check if they grabbed another object!
                        if (initialPick == GizmoDragPart.NONE || initialPick == GizmoDragPart.MOVE) {
                            val hitObj = curHitTest(startOffset)
                            if (hitObj != null && hitObj.id != p?.id) {
                                curSelect(hitObj.id)
                                activePart = GizmoDragPart.MOVE
                                onBeginDrag()
                                return@detectDragGestures
                            }
                        }

                        activePart = initialPick
                        if (initialPick != GizmoDragPart.NONE) {
                            onBeginDrag()
                        }
                    },
                    onDragEnd = {
                        if (activePart != GizmoDragPart.NONE) {
                            onEndDrag()
                            activePart = GizmoDragPart.NONE
                        }
                    },
                    onDragCancel = {
                        if (activePart != GizmoDragPart.NONE) {
                            onEndDrag()
                            activePart = GizmoDragPart.NONE
                        }
                    },
                    onDrag = { change, dragAmount ->
                        if (activePart == GizmoDragPart.NONE) return@detectDragGestures
                        val box = curBox ?: return@detectDragGestures
                        val obj = curPrimary ?: return@detectDragGestures
                        change.consume()

                        val bw = box.width.toFloat().coerceAtLeast(32f)
                        val bh = box.height.toFloat().coerceAtLeast(32f)

                        when (activePart) {
                            GizmoDragPart.MOVE -> {
                                val snapped = onMoveBy(dragAmount.x, dragAmount.y)
                                if (snapped) {
                                    haptics.performHapticFeedback(HapticFeedbackType.TextHandleMove)
                                }
                            }
                            GizmoDragPart.ROTATE -> {
                                val dx = change.position.x - box.visualCenterX.toFloat()
                                val dy = change.position.y - box.visualCenterY.toFloat()
                                val angle = (Math.toDegrees(atan2(dy.toDouble(), dx.toDouble())).toFloat() + 90f + 360f) % 360f
                                onRotateTo(angle)
                            }
                            GizmoDragPart.PIVOT -> {
                                val local = worldToLocal(change.position, box)
                                val nx = ((local.x / bw) + 0.5f).toDouble().coerceIn(0.0, 1.0)
                                val ny = ((local.y / bh) + 0.5f).toDouble().coerceIn(0.0, 1.0)
                                onSetPivot(nx, ny)
                            }
                            GizmoDragPart.CORNER_BR, GizmoDragPart.CORNER_TR, GizmoDragPart.CORNER_BL, GizmoDragPart.CORNER_TL -> {
                                val local = worldToLocal(change.position, box)
                                val fx = (abs(local.x) * 2f / bw).coerceIn(0.75f, 1.35f)
                                val fy = (abs(local.y) * 2f / bh).coerceIn(0.75f, 1.35f)
                                onResizeFactor(fx, fy)
                            }
                            GizmoDragPart.EDGE_L, GizmoDragPart.EDGE_R -> {
                                val local = worldToLocal(change.position, box)
                                val fx = (abs(local.x) * 2f / bw).coerceIn(0.75f, 1.35f)
                                onResizeFactor(fx, 1f)
                            }
                            GizmoDragPart.EDGE_T, GizmoDragPart.EDGE_B -> {
                                val local = worldToLocal(change.position, box)
                                val fy = (abs(local.y) * 2f / bh).coerceIn(0.75f, 1.35f)
                                onResizeFactor(1f, fy)
                            }
                            GizmoDragPart.SHAPE_PARAM -> {
                                val local = worldToLocal(change.position, box)
                                val ratio = ((local.x + bw / 2f) / max(bw, 1f)).toDouble().coerceIn(0.0, 1.0)
                                onUpdateShapeParam(ratio)
                            }
                            GizmoDragPart.GRAD_P0 -> {
                                val local = worldToLocal(change.position, box)
                                val nx = ((local.x / bw) + 0.5f).toDouble().coerceIn(0.0, 1.0)
                                val ny = ((local.y / bh) + 0.5f).toDouble().coerceIn(0.0, 1.0)
                                onUpdateGradientEndpoint(true, nx, ny)
                            }
                            GizmoDragPart.GRAD_P1 -> {
                                val local = worldToLocal(change.position, box)
                                val nx = ((local.x / bw) + 0.5f).toDouble().coerceIn(0.0, 1.0)
                                val ny = ((local.y / bh) + 0.5f).toDouble().coerceIn(0.0, 1.0)
                                onUpdateGradientEndpoint(false, nx, ny)
                            }
                            GizmoDragPart.NONE -> Unit
                        }
                    },
                )
            },
    ) {
        val p = primary ?: return@Canvas
        val box = worldBox ?: return@Canvas

        val cx = box.visualCenterX.toFloat()
        val cy = box.visualCenterY.toFloat()
        val w = box.width.toFloat().coerceAtLeast(32f)
        val h = box.height.toFloat().coerceAtLeast(32f)
        val rotDeg = box.rotationDeg.toFloat()

        val cyan = Color(0xFF38BDF8)
        val amber = Color(0xFFF59E0B)
        val purple = Color(0xFFA855F7)

        withTransform({
            translate(cx, cy)
            rotate(rotDeg, Offset.Zero)
        }) {
            val hw = w / 2f
            val hh = h / 2f

            // Bounding rectangle
            drawRect(
                color = cyan,
                topLeft = Offset(-hw, -hh),
                size = Size(w, h),
                style = Stroke(width = 2.5f),
            )

            // Rotation stem & handle
            drawLine(cyan, Offset(0f, -hh), Offset(0f, -hh - 38f), strokeWidth = 2.5f)
            drawCircle(Color.White, radius = 10f, center = Offset(0f, -hh - 38f))
            drawCircle(cyan, radius = 10f, center = Offset(0f, -hh - 38f), style = Stroke(width = 3f))

            // 4 corner handles + 4 edge handles
            val handles = listOf(
                Offset(-hw, -hh),
                Offset(hw, -hh),
                Offset(hw, hh),
                Offset(-hw, hh),
                Offset(-hw, 0f),
                Offset(hw, 0f),
                Offset(0f, -hh),
                Offset(0f, hh),
            )
            for (pt in handles) {
                drawCircle(Color.White, radius = 8f, center = pt)
                drawCircle(cyan, radius = 8f, center = pt, style = Stroke(width = 2.5f))
            }

            // Pivot diamond
            val px = ((p.pivotX - 0.5) * w).toFloat()
            val py = ((p.pivotY - 0.5) * h).toFloat()
            val diamond = Path().apply {
                moveTo(px, py - 9f)
                lineTo(px + 9f, py)
                lineTo(px, py + 9f)
                lineTo(px - 9f, py)
                close()
            }
            drawPath(diamond, color = amber)
            drawPath(diamond, color = Color.Black, style = Stroke(width = 1.5f))

            // Parametric shape handle
            if (p.objType == "shape") {
                val sp = Offset(-hw + 22f, -hh + 22f)
                drawCircle(amber, radius = 7.5f, center = sp)
                drawCircle(Color.White, radius = 7.5f, center = sp, style = Stroke(width = 2f))
            }

            // Gradient vector gizmo if enabled
            val grad = p.gradient
            if (grad?.enabled == true) {
                val g0 = Offset(((grad.p0x - 0.5) * w).toFloat(), ((grad.p0y - 0.5) * h).toFloat())
                val g1 = Offset(((grad.p1x - 0.5) * w).toFloat(), ((grad.p1y - 0.5) * h).toFloat())
                drawLine(purple, g0, g1, strokeWidth = 2.5f)
                drawCircle(Color.White, radius = 8f, center = g0)
                drawCircle(purple, radius = 8f, center = g0, style = Stroke(width = 3f))
                drawCircle(purple, radius = 8f, center = g1)
                drawCircle(Color.White, radius = 8f, center = g1, style = Stroke(width = 2f))
            }
        }
    }
}
