package com.criperto.standby.editor.canvas

import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.gestures.detectTapGestures
import androidx.compose.foundation.gestures.detectTransformGestures
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.BoxWithConstraints
import androidx.compose.foundation.layout.aspectRatio
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.MaterialTheme
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.unit.dp
import com.criperto.standby.StandByApplication
import com.criperto.standby.editor.EditorUiState
import com.criperto.standby.editor.EditorViewModel
import com.criperto.standby.renderer.CutoutDiskCache
import com.criperto.standby.renderer.DepthMaskRuntimeData
import com.criperto.standby.renderer.SceneRenderer
import kotlin.math.cos
import kotlin.math.sin

@Composable
fun EditorCanvas(
    app: StandByApplication,
    viewModel: EditorViewModel,
    uiState: EditorUiState,
    modifier: Modifier = Modifier,
) {
    val context = LocalContext.current
    val density = LocalDensity.current
    val liveSignals by app.signalProvider.signals.collectAsState()
    val effectiveSignals = if (uiState.simulatorEnabled) uiState.simulatorSignals else liveSignals

    var depthMask by remember { mutableStateOf<DepthMaskRuntimeData?>(null) }
    LaunchedEffect(
        uiState.preset.name,
        uiState.preset.background.mode,
        uiState.preset.background.wallpaperUri,
        uiState.preset.objects,
    ) {
        depthMask = CutoutDiskCache.loadDepthMaskForPreset(context, uiState.preset)
    }

    Box(
        modifier = modifier
            .fillMaxSize()
            .background(MaterialTheme.colorScheme.surfaceContainerLowest)
            .pointerInput(uiState.zoom, uiState.panX, uiState.panY) {
                detectTransformGestures { _, pan, zoomChange, _ ->
                    if (zoomChange != 1f || uiState.zoom > 1.02f) {
                        val nextZoom = (uiState.zoom * zoomChange).coerceIn(0.5f, 3.0f)
                        viewModel.setZoomAndPan(
                            zoom = nextZoom,
                            panX = uiState.panX + pan.x,
                            panY = uiState.panY + pan.y,
                        )
                    }
                }
            }
            .padding(10.dp),
        contentAlignment = Alignment.Center,
    ) {
        BoxWithConstraints(
            modifier = Modifier
                .aspectRatio(16f / 9f)
                .graphicsLayer {
                    scaleX = uiState.zoom
                    scaleY = uiState.zoom
                    translationX = uiState.panX
                    translationY = uiState.panY
                }
                .clip(RoundedCornerShape(16.dp))
                .border(1.5.dp, MaterialTheme.colorScheme.outlineVariant, RoundedCornerShape(16.dp))
                .background(Color.Black),
        ) {
            val sw = with(density) { maxWidth.toPx().coerceAtLeast(100f) }
            val sh = with(density) { maxHeight.toPx().coerceAtLeast(100f) }

            val primary = uiState.primarySelectedObject
            val primaryBox = remember(primary, uiState.preset.objects, sw, sh) {
                primary?.let {
                    viewModel.resolveWorldTransform(it, uiState.preset.objects, sw.toDouble(), sh.toDouble())
                }
            }

            SceneRenderer(
                preset = uiState.preset,
                signals = effectiveSignals,
                shaderRegistry = app.shaderRegistry,
                assetCache = app.assetCache,
                depthMask = depthMask,
                interactive = false,
                overrideObjects = uiState.preset.objects,
                modifier = Modifier
                    .fillMaxSize()
                    .pointerInput(uiState.preset.objects, sw, sh, uiState.multiSelectMode) {
                        detectTapGestures { tapPos ->
                            val hit = uiState.preset.objects
                                .filter { it.visible && !it.locked }
                                .sortedByDescending { it.zIndex }
                                .firstOrNull { candidate ->
                                    val box = viewModel.resolveWorldTransform(
                                        candidate,
                                        uiState.preset.objects,
                                        sw.toDouble(),
                                        sh.toDouble(),
                                    )
                                    val dx = tapPos.x - box.visualCenterX
                                    val dy = tapPos.y - box.visualCenterY
                                    val rad = Math.toRadians(-box.rotationDeg)
                                    val lx = dx * cos(rad) - dy * sin(rad)
                                    val ly = dx * sin(rad) + dy * cos(rad)
                                    val hw = box.width.coerceAtLeast(32.0) / 2.0
                                    val hh = box.height.coerceAtLeast(32.0) / 2.0
                                    lx in -hw..hw && ly in -hh..hh
                                }
                            viewModel.selectObject(hit?.id)
                        }
                    },
            )

            CompositionGuidesOverlay(visible = uiState.showCompositionGuides)

            DepthMaskLinksOverlay(
                visible = uiState.showMaskLinks,
                objects = uiState.preset.objects,
            )

            SmartGuidesOverlay(
                guides = uiState.activeGuides,
                spacings = uiState.activeSpacings,
            )

            TransformGizmoOverlay(
                primary = primary,
                worldBox = primaryBox,
                screenW = sw,
                screenH = sh,
                onBeginDrag = { viewModel.beginDragTransaction() },
                onEndDrag = { viewModel.endDragTransaction() },
                onMoveBy = { dx, dy -> viewModel.dragMoveSelected(dx, dy, sw, sh) },
                onResizeFactor = { fx, fy -> viewModel.dragResizePrimary(fx, fy) },
                onRotateTo = { deg -> viewModel.dragRotatePrimary(deg, snap15 = uiState.snapEnabled) },
                onSetPivot = { px, py ->
                    viewModel.setPivotKeepVisualPosition(px, py, sw.toDouble(), sh.toDouble())
                },
                onUpdateShapeParam = { ratio ->
                    val p = primary ?: return@TransformGizmoOverlay
                    viewModel.updateObjectDebounced(p.id) { obj ->
                        when (obj.shapeType) {
                            "star" -> obj.copy(starRatio = ratio.coerceIn(0.15, 0.85))
                            "circle" -> obj.copy(circleSweep = (ratio * 360.0).coerceIn(15.0, 360.0))
                            else -> obj.copy(radiusUniform = (ratio * 120.0).coerceIn(0.0, 120.0))
                        }
                    }
                },
                onUpdateGradientEndpoint = { isP0, nx, ny ->
                    val p = primary ?: return@TransformGizmoOverlay
                    val g = p.gradient ?: return@TransformGizmoOverlay
                    viewModel.updateObjectDebounced(p.id) { obj ->
                        obj.copy(
                            gradient = if (isP0) {
                                g.copy(p0x = nx, p0y = ny)
                            } else {
                                g.copy(p1x = nx, p1y = ny)
                            }
                        )
                    }
                },
            )
        }
    }
}
