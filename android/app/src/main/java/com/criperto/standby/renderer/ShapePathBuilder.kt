package com.criperto.standby.renderer

import android.graphics.RectF
import androidx.compose.ui.geometry.CornerRadius
import androidx.compose.ui.geometry.Rect
import androidx.compose.ui.geometry.RoundRect
import androidx.compose.ui.graphics.Path
import androidx.compose.ui.graphics.asComposePath
import com.criperto.standby.core.SceneObject
import kotlin.math.PI
import kotlin.math.acos
import kotlin.math.cos
import kotlin.math.hypot
import kotlin.math.min
import kotlin.math.sin
import kotlin.math.tan

object ShapePathBuilder {
    fun buildShapePath(obj: SceneObject, width: Float, height: Float, insetPx: Float = 0f): Path {
        return when (obj.shapeType.lowercase()) {
            "circle" -> buildCircleArcPath(
                width,
                height,
                obj.circleStart.toFloat(),
                obj.circleSweep.toFloat(),
                obj.circleRatio.toFloat(),
                insetPx,
            )
            "triangle" -> buildPolygonPath(width, height, 3, obj.polygonRadius.toFloat(), insetPx)
            "polygon" -> buildPolygonPath(width, height, obj.polygonSides, obj.polygonRadius.toFloat(), insetPx)
            "star" -> buildStarPath(
                width,
                height,
                obj.starPoints,
                obj.starRatio.toFloat(),
                obj.starRadius.toFloat(),
                insetPx,
            )
            else -> buildRectPath(obj, width, height, insetPx)
        }
    }

    fun buildRectPath(obj: SceneObject, width: Float, height: Float, insetPx: Float = 0f): Path {
        val path = Path()
        val l = insetPx
        val t = insetPx
        val r = (width - insetPx).coerceAtLeast(l + 1f)
        val b = (height - insetPx).coerceAtLeast(t + 1f)
        val maxRad = min(r - l, b - t) * 0.5f

        val tl = (if (obj.radiusIndependent) obj.radiusTopLeft else obj.radiusUniform).toFloat().coerceIn(0f, maxRad)
        val tr = (if (obj.radiusIndependent) obj.radiusTopRight else obj.radiusUniform).toFloat().coerceIn(0f, maxRad)
        val br = (if (obj.radiusIndependent) obj.radiusBottomRight else obj.radiusUniform).toFloat().coerceIn(0f, maxRad)
        val bl = (if (obj.radiusIndependent) obj.radiusBottomLeft else obj.radiusUniform).toFloat().coerceIn(0f, maxRad)

        path.addRoundRect(
            RoundRect(
                rect = Rect(l, t, r, b),
                topLeft = CornerRadius(tl, tl),
                topRight = CornerRadius(tr, tr),
                bottomRight = CornerRadius(br, br),
                bottomLeft = CornerRadius(bl, bl),
            )
        )
        return path
    }

    fun buildCircleArcPath(
        w: Float,
        h: Float,
        startDegRaw: Float,
        sweepDegRaw: Float,
        ratioRaw: Float,
        insetPx: Float,
    ): Path {
        val cx = w * 0.5f
        val cy = h * 0.5f
        val rx = (cx - insetPx).coerceAtLeast(1f)
        val ry = (cy - insetPx).coerceAtLeast(1f)
        val startDeg = ((startDegRaw % 360f) + 360f) % 360f
        val sweepDeg = sweepDegRaw.coerceIn(0.1f, 360f)
        val ratioNorm = (if (ratioRaw > 1f) ratioRaw / 100f else ratioRaw).coerceIn(0f, 0.99f)
        val irx = rx * ratioNorm
        val iry = ry * ratioNorm

        val androidPath = android.graphics.Path()
        val outerRect = RectF(cx - rx, cy - ry, cx + rx, cy + ry)

        if (sweepDeg >= 359.99f) {
            androidPath.addOval(outerRect, android.graphics.Path.Direction.CW)
            if (ratioNorm > 0.001f) {
                val innerRect = RectF(cx - irx, cy - iry, cx + irx, cy + iry)
                androidPath.addOval(innerRect, android.graphics.Path.Direction.CCW)
            }
            return androidPath.asComposePath()
        }

        // Figma convention: 0° = 12 o'clock (-90° in Android arcTo)
        val startAngle = startDeg - 90f
        val a1 = Math.toRadians(startAngle.toDouble())
        val a2 = Math.toRadians((startAngle + sweepDeg).toDouble())

        if (ratioNorm <= 0.001f) {
            androidPath.moveTo(cx, cy)
            androidPath.lineTo((cx + rx * cos(a1)).toFloat(), (cy + ry * sin(a1)).toFloat())
            androidPath.arcTo(outerRect, startAngle, sweepDeg, false)
            androidPath.close()
        } else {
            val innerRect = RectF(cx - irx, cy - iry, cx + irx, cy + iry)
            androidPath.moveTo((cx + rx * cos(a1)).toFloat(), (cy + ry * sin(a1)).toFloat())
            androidPath.arcTo(outerRect, startAngle, sweepDeg, false)
            androidPath.lineTo((cx + irx * cos(a2)).toFloat(), (cy + iry * sin(a2)).toFloat())
            androidPath.arcTo(innerRect, startAngle + sweepDeg, -sweepDeg, false)
            androidPath.close()
        }
        return androidPath.asComposePath()
    }

    fun buildPolygonPath(w: Float, h: Float, sidesRaw: Int, cornerRadius: Float, insetPx: Float): Path {
        val sides = sidesRaw.coerceIn(3, 30)
        val cx = w * 0.5f
        val cy = h * 0.5f
        val rx = (cx - insetPx).coerceAtLeast(1f)
        val ry = (cy - insetPx).coerceAtLeast(1f)
        val pts = ArrayList<Pair<Float, Float>>(sides)
        for (i in 0 until sides) {
            val angle = -PI / 2.0 + i * (2.0 * PI / sides)
            pts.add((cx + rx * cos(angle).toFloat()) to (cy + ry * sin(angle).toFloat()))
        }
        return buildRoundedPolyPath(pts, cornerRadius.coerceAtLeast(0f))
    }

    fun buildStarPath(
        w: Float,
        h: Float,
        pointsRaw: Int,
        ratioRaw: Float,
        cornerRadius: Float,
        insetPx: Float,
    ): Path {
        val points = pointsRaw.coerceIn(3, 30)
        val ratio = (if (ratioRaw > 1f) ratioRaw / 100f else ratioRaw).coerceIn(0.05f, 0.95f)
        val cx = w * 0.5f
        val cy = h * 0.5f
        val rx = (cx - insetPx).coerceAtLeast(1f)
        val ry = (cy - insetPx).coerceAtLeast(1f)
        val total = points * 2
        val pts = ArrayList<Pair<Float, Float>>(total)
        for (i in 0 until total) {
            val angle = -PI / 2.0 + i * (PI / points)
            val curRx = if (i % 2 == 0) rx else rx * ratio
            val curRy = if (i % 2 == 0) ry else ry * ratio
            pts.add((cx + curRx * cos(angle).toFloat()) to (cy + curRy * sin(angle).toFloat()))
        }
        return buildRoundedPolyPath(pts, cornerRadius.coerceAtLeast(0f))
    }

    private fun buildRoundedPolyPath(pts: List<Pair<Float, Float>>, cr: Float): Path {
        val path = Path()
        val n = pts.size
        if (n < 3) return path

        if (cr <= 0.01f) {
            path.moveTo(pts[0].first, pts[0].second)
            for (i in 1 until n) path.lineTo(pts[i].first, pts[i].second)
            path.close()
            return path
        }

        var started = false
        for (k in 0 until n) {
            val prev = pts[(k - 1 + n) % n]
            val curr = pts[k]
            val next = pts[(k + 1) % n]
            val v1x = prev.first - curr.first
            val v1y = prev.second - curr.second
            val v2x = next.first - curr.first
            val v2y = next.second - curr.second
            val l1 = hypot(v1x, v1y)
            val l2 = hypot(v2x, v2y)
            if (l1 <= 1e-4f || l2 <= 1e-4f) continue

            val u1x = v1x / l1
            val u1y = v1y / l1
            val u2x = v2x / l2
            val u2y = v2y / l2
            val dot = (u1x * u2x + u1y * u2y).coerceIn(-1f, 1f)
            val theta = acos(dot)
            val tanHalf = if (theta > 0.001f) tan(theta * 0.5f) else 1f
            val cutDist = min(if (tanHalf > 1e-4f) cr / tanHalf else 0f, min(l1, l2) * 0.49f)

            val t1x = curr.first + cutDist * u1x
            val t1y = curr.second + cutDist * u1y
            val t2x = curr.first + cutDist * u2x
            val t2y = curr.second + cutDist * u2y

            if (!started) {
                path.moveTo(t1x, t1y)
                started = true
            } else {
                path.lineTo(t1x, t1y)
            }
            path.quadraticTo(curr.first, curr.second, t2x, t2y)
        }
        path.close()
        return path
    }
}
