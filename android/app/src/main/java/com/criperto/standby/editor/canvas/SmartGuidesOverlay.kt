package com.criperto.standby.editor.canvas

import android.graphics.Paint
import androidx.compose.foundation.Canvas
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.CornerRadius
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.PathEffect
import androidx.compose.ui.graphics.nativeCanvas
import com.criperto.standby.core.SnapGuideDto
import com.criperto.standby.core.SpacingLabelDto
import kotlin.math.roundToInt

@Composable
fun SmartGuidesOverlay(
    guides: List<SnapGuideDto>,
    spacings: List<SpacingLabelDto>,
    modifier: Modifier = Modifier,
) {
    if (guides.isEmpty() && spacings.isEmpty()) return

    Canvas(modifier = modifier.fillMaxSize()) {
        val snapPink = Color(0xFFF43F5E)
        val spacingAmber = Color(0xFFF59E0B)
        val dash = PathEffect.dashPathEffect(floatArrayOf(8f, 6f), 0f)

        for (g in guides) {
            val col = if (g.isEqualSpacing) spacingAmber else snapPink
            if (g.axis == "v") {
                val x = g.pos.toFloat()
                drawLine(
                    color = col,
                    start = Offset(x, g.min.toFloat()),
                    end = Offset(x, g.max.toFloat()),
                    strokeWidth = 2.2f,
                )
            } else {
                val y = g.pos.toFloat()
                drawLine(
                    color = col,
                    start = Offset(g.min.toFloat(), y),
                    end = Offset(g.max.toFloat(), y),
                    strokeWidth = 2.2f,
                )
            }
        }

        val textPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
            color = android.graphics.Color.WHITE
            textSize = 24f
            textAlign = Paint.Align.CENTER
            isFakeBoldText = true
        }

        for (sp in spacings) {
            val start = sp.start.toFloat()
            val end = (sp.start + sp.length).toFloat()
            val mid = (start + end) / 2f
            val cross = sp.crossPos.toFloat()
            if (sp.axis == "h") {
                drawLine(
                    color = spacingAmber,
                    start = Offset(start, cross),
                    end = Offset(end, cross),
                    strokeWidth = 2f,
                    pathEffect = dash,
                )
                drawRoundRect(
                    color = spacingAmber,
                    topLeft = Offset(mid - 34f, cross - 16f),
                    size = Size(68f, 32f),
                    cornerRadius = CornerRadius(10f, 10f),
                )
                drawContext.canvas.nativeCanvas.drawText(
                    "${sp.length.roundToInt()}",
                    mid,
                    cross + 8f,
                    textPaint,
                )
            } else {
                drawLine(
                    color = spacingAmber,
                    start = Offset(cross, start),
                    end = Offset(cross, end),
                    strokeWidth = 2f,
                    pathEffect = dash,
                )
                drawRoundRect(
                    color = spacingAmber,
                    topLeft = Offset(cross - 34f, mid - 16f),
                    size = Size(68f, 32f),
                    cornerRadius = CornerRadius(10f, 10f),
                )
                drawContext.canvas.nativeCanvas.drawText(
                    "${sp.length.roundToInt()}",
                    cross,
                    mid + 8f,
                    textPaint,
                )
            }
        }
    }
}
