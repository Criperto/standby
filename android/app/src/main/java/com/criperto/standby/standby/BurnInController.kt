package com.criperto.standby.standby

import kotlin.math.cos
import kotlin.math.sin

data class PixelShiftOffset(
    val xPx: Float = 0f,
    val yPx: Float = 0f,
)

object BurnInController {
    /**
     * Computes a bounded smooth Lissajous/spiral pixel shift offset (±maxPx)
     * so AMOLED pixels distribute wear evenly without moving elements abruptly.
     */
    fun computeShift(stepIndex: Long, enabled: Boolean, maxPx: Float): PixelShiftOffset {
        if (!enabled || maxPx <= 0.1f) return PixelShiftOffset(0f, 0f)
        val clampedMax = maxPx.coerceIn(1f, 24f)
        val theta = (stepIndex % 3600L) * 0.42
        val radiusFrac = 0.45f + 0.55f * ((stepIndex % 8L) / 7f)
        val x = (cos(theta) * clampedMax * radiusFrac).toFloat()
        val y = (sin(theta * 0.73 + 0.6) * clampedMax * radiusFrac).toFloat()
        return PixelShiftOffset(x, y)
    }
}
