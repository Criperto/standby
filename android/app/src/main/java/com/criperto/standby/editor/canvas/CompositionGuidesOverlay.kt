package com.criperto.standby.editor.canvas

import androidx.compose.foundation.Canvas
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.PathEffect
import androidx.compose.ui.graphics.drawscope.Stroke

@Composable
fun CompositionGuidesOverlay(
    visible: Boolean,
    modifier: Modifier = Modifier,
) {
    if (!visible) return

    Canvas(modifier = modifier.fillMaxSize()) {
        val w = size.width
        val h = size.height
        val dash = PathEffect.dashPathEffect(floatArrayOf(10f, 10f), 0f)
        val guideColor = Color(0x38FFFFFF)
        val centerColor = Color(0x5064B5F6)

        // Rule of thirds
        for (i in 1..2) {
            val x = w * (i / 3f)
            val y = h * (i / 3f)
            drawLine(guideColor, Offset(x, 0f), Offset(x, h), strokeWidth = 1f, pathEffect = dash)
            drawLine(guideColor, Offset(0f, y), Offset(w, y), strokeWidth = 1f, pathEffect = dash)
        }

        // Center cross
        drawLine(centerColor, Offset(w / 2f, 0f), Offset(w / 2f, h), strokeWidth = 1.2f)
        drawLine(centerColor, Offset(0f, h / 2f), Offset(w, h / 2f), strokeWidth = 1.2f)

        // Safe margin box (5%)
        val mx = w * 0.05f
        val my = h * 0.05f
        drawRect(
            color = Color(0x26FFFFFF),
            topLeft = Offset(mx, my),
            size = Size(w - mx * 2f, h - my * 2f),
            style = Stroke(width = 1f, pathEffect = dash),
        )
    }
}
