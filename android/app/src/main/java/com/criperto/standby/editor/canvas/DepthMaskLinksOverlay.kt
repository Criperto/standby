package com.criperto.standby.editor.canvas

import androidx.compose.foundation.Canvas
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.PathEffect
import com.criperto.standby.core.SceneObject

@Composable
fun DepthMaskLinksOverlay(
    visible: Boolean,
    objects: List<SceneObject>,
    modifier: Modifier = Modifier,
) {
    if (!visible) return

    Canvas(modifier = modifier.fillMaxSize()) {
        val w = size.width
        val h = size.height
        val linkColor = Color(0xCC10B981)
        val dash = PathEffect.dashPathEffect(floatArrayOf(12f, 8f), 0f)

        val byId = objects.associateBy { it.id }
        for (src in objects) {
            if (!src.isMask) continue
            val sx = (src.posXRatio * w).toFloat()
            val sy = (src.posYRatio * h).toFloat()
            val targets = buildSet {
                src.maskTargetId?.takeIf { it.isNotBlank() }?.let { add(it) }
                addAll(src.maskTargetIds)
            }
            for (tid in targets) {
                val dst = byId[tid] ?: continue
                val dx = (dst.posXRatio * w).toFloat()
                val dy = (dst.posYRatio * h).toFloat()
                drawLine(
                    color = linkColor,
                    start = Offset(sx, sy),
                    end = Offset(dx, dy),
                    strokeWidth = 2.5f,
                    pathEffect = dash,
                )
                drawCircle(color = linkColor, radius = 6f, center = Offset(sx, sy))
                drawCircle(color = linkColor, radius = 6f, center = Offset(dx, dy))
            }
        }
    }
}
