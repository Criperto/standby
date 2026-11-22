package com.criperto.standby.standby

import android.view.Window
import androidx.activity.compose.BackHandler
import androidx.compose.animation.AnimatedVisibility
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.gestures.detectHorizontalDragGestures
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
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.rounded.Close
import androidx.compose.material.icons.rounded.Lightbulb
import androidx.compose.material.icons.rounded.Notifications
import androidx.compose.material.icons.rounded.Timer
import androidx.compose.material3.Button
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableFloatStateOf
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableLongStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
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
import com.criperto.standby.smarthome.SmartHomeHubOverlay
import kotlinx.coroutines.delay
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch
import uniffi.standby_core.evaluateStandbyRulesJson
import kotlin.math.roundToInt

@Composable
fun StandByHostScreen(
    app: StandByApplication,
    window: Window?,
    manualLaunch: Boolean,
    onExitStandBy: () -> Unit,
    onOrientationAutoExit: () -> Unit = onExitStandBy,
    onWizAction: (command: String, deviceId: String, arg: String?) -> Unit = { cmd, dev, arg ->
        app.smartHomeController.executeSceneWizAction(cmd, dev, arg)
    },
    hubSlot: @Composable (
        remainingSec: Int,
        onDismiss: () -> Unit,
        onInteractionPing: () -> Unit,
        onExitStandBy: () -> Unit,
    ) -> Unit = { remainingSec, onDismiss, onInteractionPing, onExit ->
        SmartHomeHubOverlay(
            app = app,
            remainingSec = remainingSec,
            onDismiss = onDismiss,
            onInteractionPing = onInteractionPing,
            onExitStandBy = onExit,
        )
    },
) {
    val context = LocalContext.current
    val lifecycleOwner = LocalLifecycleOwner.current

    val settings by app.settingsRepository.settingsFlow.collectAsState(initial = AppSettings())
    val preset by app.presetRepository.activePresetFlow.collectAsState()
    val activePresetId by app.presetRepository.activePresetIdFlow.collectAsState()
    val presetIds by app.presetRepository.presetIdsFlow.collectAsState()
    val signals by app.signalProvider.signals.collectAsState()
    val scope = rememberCoroutineScope()

    var showPresetIndicator by remember { mutableStateOf(false) }
    LaunchedEffect(activePresetId) {
        showPresetIndicator = true
        delay(2500L)
        showPresetIndicator = false
    }

    var showNotifBanner by remember { mutableStateOf(false) }
    LaunchedEffect(signals.notifications.lastPostedEpochMs, signals.notifications.lastTitle) {
        val postedAt = signals.notifications.lastPostedEpochMs
        val ageMs = System.currentTimeMillis() - postedAt
        if (postedAt > 0L && ageMs in 0L..8_000L && signals.notifications.lastTitle.isNotBlank()) {
            showNotifBanner = true
            delay((8_000L - ageMs).coerceIn(1_000L, 8_000L))
            showNotifBanner = false
            MediaBridge.dismissNotificationBanner()
        } else {
            showNotifBanner = false
        }
    }

    // Exit StandBy automatically when physically rotated to vertical (portrait)
    LaunchedEffect(signals.isLandscape, settings.orientationReq) {
        if (settings.orientationReq == "landscape_only" && !signals.isLandscape) {
            delay(500L)
            if (!app.signalProvider.signals.value.isLandscape) {
                onOrientationAutoExit()
            }
        }
    }

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

    // Restore the last known blackout / dark-room state so that a restart (e.g. after Android revoked
    // the camera) does not flash the screen ON in a pitch-black room before presence is re-checked.
    val runtimePrefs = remember {
        context.applicationContext.getSharedPreferences("standby_runtime", android.content.Context.MODE_PRIVATE)
    }
    val restoredBlackout = remember { runtimePrefs.getBoolean("last_blackout", false) }
    val restoredDark = remember { runtimePrefs.getBoolean("last_dark_room", false) }

    var hubOpen by remember { mutableStateOf(false) }
    var lastHubInteractionMs by remember { mutableLongStateOf(0L) }
    var remainingSec by remember { mutableIntStateOf(0) }

    var prevDarkRoom by remember { mutableStateOf(restoredDark || restoredBlackout) }
    var decision by remember {
        mutableStateOf(
            if (restoredBlackout && !manualLaunch) {
                StandByDecisionDto(
                    shouldBeActive = true,
                    displayMode = "amoled_blackout",
                    targetBrightness = 0.01f,
                    isDarkRoom = true,
                )
            } else {
                StandByDecisionDto(
                    shouldBeActive = true,
                    displayMode = "active",
                    targetBrightness = 0.28f,
                )
            }
        )
    }
    LaunchedEffect(restoredBlackout) {
        if (restoredBlackout && !manualLaunch) {
            // Do not trust "recent presence" from a fresh controller: require a new positive signal
            presenceController.seedLastPresence(0L)
        }
    }
    LaunchedEffect(decision.displayMode, decision.isDarkRoom) {
        runtimePrefs.edit()
            .putBoolean("last_blackout", decision.displayMode == "amoled_blackout")
            .putBoolean("last_dark_room", decision.isDarkRoom)
            .apply()
    }

    LaunchedEffect(
        settings,
        signals.nowEpochMs,
        signals.localMinuteOfDay,
        signals.isCharging,
        signals.chargeSource,
        signals.isLandscape,
        signals.ambientLux,
        presenceState,
        hubOpen,
        manualLaunch,
    ) {
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
            prevBlackout = decision.displayMode == "amoled_blackout",
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
                                decision = decision.copy(
                                    displayMode = "night_active",
                                    targetBrightness = settings.nightBrightness,
                                    extraDimAlpha = 0.5f,
                                )
                            },
                            onLongPress = {
                                onExitStandBy()
                            },
                        )
                    },
            )
        } else {
            val nightRedTintActive = decision.displayMode == "night_active" && settings.nightRedTintEnabled
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
                nightRedTint = nightRedTintActive,
                onEmptyTap = {
                    presenceController.markTouchPresence()
                    if (settings.tapToWakeEnabled) {
                        lastHubInteractionMs = System.currentTimeMillis()
                        hubOpen = true
                    }
                },
                onWizAction = onWizAction,
            )

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

            // Top floating pill for Live Notification Banner
            Column(
                modifier = Modifier
                    .align(Alignment.TopCenter)
                    .padding(top = 12.dp),
                horizontalAlignment = Alignment.CenterHorizontally,
                verticalArrangement = Arrangement.spacedBy(6.dp),
            ) {
                AnimatedVisibility(
                    visible = showNotifBanner && !hubOpen && signals.notifications.lastTitle.isNotBlank(),
                    enter = fadeIn(),
                    exit = fadeOut(),
                ) {
                    Surface(
                        shape = RoundedCornerShape(22.dp),
                        color = if (nightRedTintActive) {
                            Color(0xE6220404)
                        } else {
                            MaterialTheme.colorScheme.surfaceContainerHigh.copy(alpha = 0.92f)
                        },
                        contentColor = if (nightRedTintActive) {
                            Color(0xFFFF3B30)
                        } else {
                            MaterialTheme.colorScheme.onSurface
                        },
                        modifier = Modifier
                            .widthIn(max = 520.dp)
                            .clickable {
                                showNotifBanner = false
                                MediaBridge.dismissNotificationBanner()
                            },
                    ) {
                        Row(
                            modifier = Modifier.padding(horizontal = 16.dp, vertical = 8.dp),
                            verticalAlignment = Alignment.CenterVertically,
                            horizontalArrangement = Arrangement.spacedBy(10.dp),
                        ) {
                            Icon(
                                Icons.Rounded.Notifications,
                                contentDescription = null,
                                tint = MaterialTheme.colorScheme.primary,
                                modifier = Modifier.size(18.dp),
                            )
                            Column(modifier = Modifier.weight(1f, fill = false)) {
                                Text(
                                    text = signals.notifications.lastTitle,
                                    style = MaterialTheme.typography.labelLarge,
                                    fontWeight = FontWeight.SemiBold,
                                    maxLines = 1,
                                    overflow = TextOverflow.Ellipsis,
                                )
                                if (signals.notifications.lastText.isNotBlank()) {
                                    Text(
                                        text = signals.notifications.lastText,
                                        style = MaterialTheme.typography.bodySmall,
                                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                                        maxLines = 1,
                                        overflow = TextOverflow.Ellipsis,
                                    )
                                }
                            }
                        }
                    }
                }
            }

            // Bottom swipe zone + Auto-fading Preset Carousel Page Dots (iOS StandBy style)
            var swipeAccum by remember { mutableFloatStateOf(0f) }
            Box(
                modifier = Modifier
                    .align(Alignment.BottomCenter)
                    .fillMaxWidth()
                    .height(42.dp)
                    .pointerInput(presetIds) {
                        detectHorizontalDragGestures(
                            onDragStart = {
                                swipeAccum = 0f
                                showPresetIndicator = true
                            },
                            onDragEnd = {
                                if (swipeAccum <= -70f) {
                                    scope.launch { app.presetRepository.cycleActivePreset(1) }
                                } else if (swipeAccum >= 70f) {
                                    scope.launch { app.presetRepository.cycleActivePreset(-1) }
                                }
                                swipeAccum = 0f
                            },
                            onHorizontalDrag = { change, dragAmount ->
                                change.consume()
                                swipeAccum += dragAmount
                            },
                        )
                    },
                contentAlignment = Alignment.Center,
            ) {
                AnimatedVisibility(
                    visible = showPresetIndicator && !hubOpen && presetIds.size > 1,
                    enter = fadeIn(),
                    exit = fadeOut(),
                ) {
                    Surface(
                        shape = RoundedCornerShape(16.dp),
                        color = MaterialTheme.colorScheme.surfaceContainerHigh.copy(alpha = 0.78f),
                    ) {
                        Row(
                            modifier = Modifier.padding(horizontal = 12.dp, vertical = 4.dp),
                            verticalAlignment = Alignment.CenterVertically,
                            horizontalArrangement = Arrangement.spacedBy(6.dp),
                        ) {
                            presetIds.forEach { pid ->
                                val active = pid == activePresetId
                                Box(
                                    modifier = Modifier
                                        .size(if (active) 7.dp else 5.dp)
                                        .clip(CircleShape)
                                        .background(
                                            if (active) {
                                                MaterialTheme.colorScheme.primary
                                            } else {
                                                MaterialTheme.colorScheme.onSurfaceVariant.copy(alpha = 0.45f)
                                            }
                                        ),
                                )
                            }
                            Spacer(Modifier.width(4.dp))
                            Text(
                                text = activePresetId,
                                style = MaterialTheme.typography.labelSmall,
                                color = MaterialTheme.colorScheme.onSurface,
                            )
                        }
                    }
                }
            }

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
