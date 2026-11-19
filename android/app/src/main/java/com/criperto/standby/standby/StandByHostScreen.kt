package com.criperto.standby.standby

import android.view.Window
import androidx.activity.compose.BackHandler
import androidx.compose.animation.AnimatedVisibility
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.foundation.background
import androidx.compose.foundation.gestures.detectTapGestures
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.offset
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.rounded.Close
import androidx.compose.material.icons.rounded.Lightbulb
import androidx.compose.material3.Button
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableLongStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.unit.IntOffset
import androidx.compose.ui.unit.dp
import androidx.lifecycle.compose.LocalLifecycleOwner
import com.criperto.standby.StandByApplication
import com.criperto.standby.core.AppJson
import com.criperto.standby.core.AppSettings
import com.criperto.standby.core.StandByDecisionDto
import com.criperto.standby.core.TriggerSnapshotDto
import com.criperto.standby.renderer.CutoutDiskCache
import com.criperto.standby.renderer.DepthMaskRuntimeData
import com.criperto.standby.renderer.SceneRenderer
import kotlinx.coroutines.delay
import kotlinx.coroutines.isActive
import uniffi.standby_core.evaluateStandbyRulesJson
import kotlin.math.roundToInt

@Composable
fun StandByHostScreen(
    app: StandByApplication,
    window: Window?,
    manualLaunch: Boolean,
    onExitStandBy: () -> Unit,
    onWizAction: (command: String, deviceId: String, arg: String?) -> Unit = { _, _, _ -> },
    hubSlot: @Composable (
        remainingSec: Int,
        onDismiss: () -> Unit,
        onInteractionPing: () -> Unit,
        onExitStandBy: () -> Unit,
    ) -> Unit = { remainingSec, onDismiss, _, onExit ->
        DefaultTapToWakeCard(
            remainingSec = remainingSec,
            onDismiss = onDismiss,
            onExitStandBy = onExit,
        )
    },
) {
    val context = LocalContext.current
    val lifecycleOwner = LocalLifecycleOwner.current

    val settings by app.settingsRepository.settingsFlow.collectAsState(initial = AppSettings())
    val preset by app.presetRepository.activePresetFlow.collectAsState()
    val signals by app.signalProvider.signals.collectAsState()

    DisposableEffect(Unit) {
        app.signalProvider.acquireRenderConsumer()
        onDispose {
            app.signalProvider.releaseRenderConsumer()
        }
    }

    val presenceController = remember { PresenceController(context.applicationContext) }
    val presenceState by presenceController.state.collectAsState()
    DisposableEffect(Unit) {
        onDispose {
            presenceController.shutdown()
        }
    }

    var hubOpen by remember { mutableStateOf(false) }
    var lastHubInteractionMs by remember { mutableLongStateOf(0L) }
    var remainingSec by remember { mutableIntStateOf(0) }

    var prevDarkRoom by remember { mutableStateOf(false) }
    var decision by remember {
        mutableStateOf(
            StandByDecisionDto(
                shouldBeActive = true,
                displayMode = "active",
                targetBrightness = 0.28f,
            )
        )
    }

    LaunchedEffect(settings, signals, presenceState, hubOpen, manualLaunch) {
        val rulesJson = runCatching { AppJson.encodeToString(settings.toTriggerRulesDto()) }.getOrNull()
        val snapshot = TriggerSnapshotDto(
            isCharging = signals.isCharging,
            chargeSource = signals.chargeSource,
            isLandscape = signals.isLandscape,
            nowEpochMs = signals.nowEpochMs,
            localMinuteOfDay = signals.localMinuteOfDay,
            localWeekdayMon0 = signals.localWeekdayMon0,
            localYyyyMmDd = signals.localYyyyMmDd,
            ambientLux = signals.ambientLux,
            faceDetected = presenceState.faceDetected,
            lastPresenceEpochMs = presenceState.lastPresenceEpochMs,
            hubOpen = hubOpen,
            manualLaunch = manualLaunch,
            prevDarkRoom = prevDarkRoom,
        )
        val snapJson = runCatching { AppJson.encodeToString(snapshot) }.getOrNull()
        if (rulesJson != null && snapJson != null) {
            val evaluated = runCatching {
                AppJson.decodeFromString<StandByDecisionDto>(
                    evaluateStandbyRulesJson(rulesJson, snapJson)
                )
            }.getOrNull()
            if (evaluated != null) {
                decision = evaluated
                prevDarkRoom = evaluated.isDarkRoom
            }
        }
    }

    LaunchedEffect(decision.shouldPollCamera, settings.cameraPollIntervalSec) {
        presenceController.updatePolling(
            lifecycleOwner = lifecycleOwner,
            shouldPollCamera = decision.shouldPollCamera,
            intervalSec = settings.cameraPollIntervalSec,
        )
    }

    val effectiveBrightness = when {
        settings.useSystemBrightness -> -1f
        decision.displayMode == "amoled_blackout" -> 0.01f
        hubOpen -> settings.hubBrightness
        else -> decision.targetBrightness
    }

    LaunchedEffect(effectiveBrightness, window) {
        StandByBrightnessController.applyToWindow(window, effectiveBrightness)
    }

    DisposableEffect(window) {
        onDispose {
            StandByBrightnessController.applyToWindow(window, -1f)
        }
    }

    var burnStep by remember { mutableLongStateOf(0L) }
    LaunchedEffect(settings.pixelShiftEnabled, settings.pixelShiftIntervalSec, decision.displayMode) {
        if (!settings.pixelShiftEnabled || decision.displayMode == "amoled_blackout") return@LaunchedEffect
        val intervalMs = settings.pixelShiftIntervalSec.coerceIn(15, 300) * 1000L
        while (isActive) {
            delay(intervalMs)
            burnStep += 1L
        }
    }
    val burnOffset = remember(burnStep, settings.pixelShiftEnabled, settings.pixelShiftMaxPx) {
        BurnInController.computeShift(
            stepIndex = burnStep,
            enabled = settings.pixelShiftEnabled,
            maxPx = settings.pixelShiftMaxPx,
        )
    }

    var depthMask by remember { mutableStateOf<DepthMaskRuntimeData?>(null) }
    LaunchedEffect(preset.name, preset.background.mode, preset.background.wallpaperUri, preset.objects) {
        depthMask = CutoutDiskCache.loadDepthMaskForPreset(context, preset)
    }

    LaunchedEffect(hubOpen, lastHubInteractionMs, settings.autoReturnSec) {
        if (!hubOpen) {
            remainingSec = 0
            return@LaunchedEffect
        }
        val totalSec = settings.autoReturnSec.coerceIn(5, 300)
        while (isActive && hubOpen) {
            val elapsedSec = ((System.currentTimeMillis() - lastHubInteractionMs) / 1000L).toInt().coerceAtLeast(0)
            val left = (totalSec - elapsedSec).coerceAtLeast(0)
            remainingSec = left
            if (left == 0) {
                hubOpen = false
                break
            }
            delay(1000L)
        }
    }

    BackHandler(enabled = true) {
        if (hubOpen) {
            hubOpen = false
        } else {
            onExitStandBy()
        }
    }

    Box(
        modifier = Modifier
            .fillMaxSize()
            .background(Color.Black),
    ) {
        if (decision.displayMode == "amoled_blackout") {
            Box(
                modifier = Modifier
                    .fillMaxSize()
                    .background(Color.Black)
                    .pointerInput(Unit) {
                        detectTapGestures(
                            onTap = {
                                presenceController.markTouchPresence()
                            },
                            onLongPress = {
                                onExitStandBy()
                            },
                        )
                    },
            )
        } else {
            SceneRenderer(
                preset = preset,
                signals = signals,
                shaderRegistry = app.shaderRegistry,
                assetCache = app.assetCache,
                modifier = Modifier
                    .fillMaxSize()
                    .offset {
                        IntOffset(
                            burnOffset.xPx.roundToInt(),
                            burnOffset.yPx.roundToInt(),
                        )
                    },
                depthMask = depthMask,
                interactive = !hubOpen,
                onEmptyTap = {
                    presenceController.markTouchPresence()
                    if (settings.tapToWakeEnabled) {
                        lastHubInteractionMs = System.currentTimeMillis()
                        hubOpen = true
                    }
                },
                onWizAction = onWizAction,
            )

            if (decision.displayMode == "night_active" && settings.nightRedTintEnabled) {
                Box(
                    modifier = Modifier
                        .fillMaxSize()
                        .background(Color(0x40990000)),
                )
            }

            if (decision.extraDimAlpha > 0.005f) {
                Box(
                    modifier = Modifier
                        .fillMaxSize()
                        .background(Color.Black.copy(alpha = decision.extraDimAlpha.coerceIn(0f, 0.90f))),
                )
            }

            // Discreet long-press exit zone at top-right corner when AOD is shown
            Box(
                modifier = Modifier
                    .align(Alignment.TopEnd)
                    .padding(12.dp)
                    .pointerInput(Unit) {
                        detectTapGestures(
                            onDoubleTap = { onExitStandBy() },
                            onLongPress = { onExitStandBy() },
                        )
                    },
            )

            AnimatedVisibility(
                visible = hubOpen,
                enter = fadeIn(),
                exit = fadeOut(),
                modifier = Modifier.fillMaxSize(),
            ) {
                Box(
                    modifier = Modifier
                        .fillMaxSize()
                        .background(Color.Black.copy(alpha = 0.62f))
                        .pointerInput(Unit) {
                            detectTapGestures(onTap = { hubOpen = false })
                        },
                    contentAlignment = Alignment.Center,
                ) {
                    Box(
                        modifier = Modifier.pointerInput(Unit) {
                            detectTapGestures(onTap = {
                                lastHubInteractionMs = System.currentTimeMillis()
                            })
                        },
                    ) {
                        hubSlot(
                            remainingSec,
                            { hubOpen = false },
                            {
                                lastHubInteractionMs = System.currentTimeMillis()
                                presenceController.markTouchPresence()
                            },
                            onExitStandBy,
                        )
                    }
                }
            }
        }
    }
}

@Composable
private fun DefaultTapToWakeCard(
    remainingSec: Int,
    onDismiss: () -> Unit,
    onExitStandBy: () -> Unit,
) {
    Card(
        modifier = Modifier
            .widthIn(max = 460.dp)
            .padding(24.dp),
        shape = RoundedCornerShape(28.dp),
        colors = CardDefaults.cardColors(
            containerColor = MaterialTheme.colorScheme.surfaceContainerHigh,
        ),
    ) {
        Column(modifier = Modifier.padding(20.dp)) {
            Row(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.SpaceBetween,
                verticalAlignment = Alignment.CenterVertically,
            ) {
                Row(
                    horizontalArrangement = Arrangement.spacedBy(10.dp),
                    verticalAlignment = Alignment.CenterVertically,
                ) {
                    Icon(
                        imageVector = Icons.Rounded.Lightbulb,
                        contentDescription = null,
                        tint = MaterialTheme.colorScheme.primary,
                    )
                    Text(
                        text = "Control Rápido (${remainingSec}s)",
                        style = MaterialTheme.typography.titleMedium,
                        color = MaterialTheme.colorScheme.onSurface,
                    )
                }
                IconButton(onClick = onDismiss) {
                    Icon(Icons.Rounded.Close, contentDescription = "Volver al reloj")
                }
            }
            Spacer(modifier = Modifier.height(8.dp))
            Text(
                text = "Panel Tap-to-Wake listo. Los controles locales WiZ RGB se conectan en este panel.",
                style = MaterialTheme.typography.bodyMedium,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
            Spacer(modifier = Modifier.height(16.dp))
            Row(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.spacedBy(12.dp, Alignment.End),
            ) {
                OutlinedButton(onClick = onExitStandBy) {
                    Text("Salir de StandBy")
                }
                Button(onClick = onDismiss) {
                    Text("Volver al reloj")
                }
            }
        }
    }
}
