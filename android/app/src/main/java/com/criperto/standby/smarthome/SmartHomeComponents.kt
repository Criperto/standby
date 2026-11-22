package com.criperto.standby.smarthome

import android.content.res.Configuration
import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.Canvas
import androidx.compose.foundation.background
import androidx.compose.foundation.gestures.detectDragGestures
import androidx.compose.foundation.gestures.detectHorizontalDragGestures
import androidx.compose.foundation.gestures.detectTapGestures
import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ExperimentalLayoutApi
import androidx.compose.foundation.layout.FlowRow
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.rounded.AcUnit
import androidx.compose.material.icons.rounded.BookmarkAdd
import androidx.compose.material.icons.rounded.Delete
import androidx.compose.material.icons.rounded.Edit
import androidx.compose.material.icons.rounded.Palette
import androidx.compose.material.icons.rounded.PlayArrow
import androidx.compose.material.icons.rounded.PowerSettingsNew
import androidx.compose.material.icons.rounded.Tv
import androidx.compose.material.icons.rounded.VolumeOff
import androidx.compose.material.icons.rounded.WbSunny
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.AssistChip
import androidx.compose.material3.Button
import androidx.compose.material3.ButtonDefaults
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.FilledTonalButton
import androidx.compose.material3.FilterChip
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Slider
import androidx.compose.material3.Surface
import androidx.compose.material3.Tab
import androidx.compose.material3.TabRow
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.VerticalDivider
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableFloatStateOf
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberUpdatedState
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.graphics.lerp
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.platform.LocalConfiguration
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.compose.ui.window.Dialog
import androidx.compose.ui.window.DialogProperties
import kotlin.math.atan2
import kotlin.math.cos
import kotlin.math.hypot
import kotlin.math.roundToInt
import kotlin.math.sin

/**
 * 100% Material You Dynamic Color Google Home-style card for Lights, ACs, Smart TVs, Switches & Fans.
 * Zero hardcoded blue colors — adapts automatically to green, violet, amber, or any wallpaper palette.
 */
@Composable
fun WizDeviceCard(
    device: WizDeviceConfig,
    state: SmartDeviceStateDto,
    reachable: Boolean,
    scenes: List<WizSceneInfoDto> = emptyList(),
    compact: Boolean = false,
    onTogglePower: (Boolean) -> Unit,
    onBrightnessChange: (Int) -> Unit,
    onOpenControls: () -> Unit,
    onAcControl: ((tempC: Int, hvacMode: String?) -> Unit)? = null,
    onTvMuteToggle: (() -> Unit)? = null,
    onTvPlayPause: (() -> Unit)? = null,
    onRenameRequest: (() -> Unit)? = null,
    onDeleteRequest: (() -> Unit)? = null,
    modifier: Modifier = Modifier,
) {
    val curState by rememberUpdatedState(state)
    val curOnToggle by rememberUpdatedState(onTogglePower)
    val curOnBrightness by rememberUpdatedState(onBrightnessChange)

    val cs = MaterialTheme.colorScheme
    val lampHex = remember(state.isOn, state.sceneId, state.tempKelvin, state.r, state.g, state.b) {
        WizColorMath.stateToHex(state)
    }
    val lampColor = remember(lampHex, cs.primaryContainer) {
        runCatching { Color(android.graphics.Color.parseColor(lampHex)) }.getOrDefault(cs.primaryContainer)
    }
    val modeLabel = remember(state, scenes, device.category) {
        WizColorMath.stateModeLabel(state, scenes, device.category)
    }

    val baseBg = cs.surfaceContainerHighest
    val hasExplicitRgb = state.r != null && state.g != null && state.b != null && state.sceneId == null && state.tempKelvin == null
    val fillTint = when {
        !state.isOn -> cs.surfaceContainerHigh
        hasExplicitRgb -> lerp(cs.primaryContainer, lampColor, 0.45f)
        else -> cs.primaryContainer
    }

    val fillFraction = when {
        !state.isOn -> 0f
        device.category == "ac" -> {
            val t = (state.targetTempC ?: state.brightness).coerceIn(16, 30)
            ((t - 16f) / 14f).coerceIn(0.08f, 1f)
        }
        device.category == "tv" -> {
            val v = (state.volumePct ?: state.brightness).coerceIn(0, 100)
            (v / 100f).coerceIn(0.05f, 1f)
        }
        device.category == "switch" -> 1f
        else -> (state.brightness.coerceIn(10, 100) / 100f)
    }

    val rightStatusText = when {
        !state.isOn -> "OFF"
        device.category == "ac" -> "${state.targetTempC ?: state.brightness}°C"
        device.category == "tv" -> if (state.isMuted) "MUTE" else "Vol %${state.volumePct ?: state.brightness}"
        device.category == "switch" -> "ON"
        else -> "%${state.brightness}"
    }

    Card(
        modifier = modifier,
        shape = RoundedCornerShape(26.dp),
        colors = CardDefaults.cardColors(containerColor = baseBg),
    ) {
        Column(modifier = Modifier.fillMaxWidth()) {
            // Top Google Home interactive slider + 1-tap toggle zone
            Box(
                modifier = Modifier
                    .fillMaxWidth()
                    .height(if (compact) 96.dp else 108.dp)
                    .pointerInput(device.id) {
                        detectTapGestures(
                            onTap = {
                                curOnToggle(!curState.isOn)
                            },
                        )
                    }
                    .then(
                        if (device.category != "switch") {
                            Modifier.pointerInput(device.id) {
                                detectHorizontalDragGestures(
                                    onHorizontalDrag = { change, _ ->
                                        change.consume()
                                        val w = size.width.toFloat().coerceAtLeast(1f)
                                        val pct = ((change.position.x / w) * 100f).roundToInt().coerceIn(10, 100)
                                        curOnBrightness(pct)
                                    },
                                )
                            }
                        } else Modifier
                    ),
            ) {
                Canvas(modifier = Modifier.fillMaxSize()) {
                    if (fillFraction > 0.01f) {
                        drawRect(
                            color = fillTint,
                            topLeft = Offset.Zero,
                            size = Size(size.width * fillFraction, size.height),
                        )
                    }
                }

                Row(
                    modifier = Modifier
                        .fillMaxSize()
                        .padding(horizontal = 18.dp, vertical = 14.dp),
                    horizontalArrangement = Arrangement.SpaceBetween,
                    verticalAlignment = Alignment.Top,
                ) {
                    Column(
                        modifier = Modifier.weight(1f).padding(end = 8.dp),
                        verticalArrangement = Arrangement.spacedBy(2.dp),
                    ) {
                        Text(
                            text = device.name,
                            color = if (state.isOn) cs.onPrimaryContainer else cs.onSurface,
                            fontSize = 18.sp,
                            fontWeight = FontWeight.SemiBold,
                            maxLines = 1,
                            overflow = TextOverflow.Ellipsis,
                        )
                        Text(
                            text = "${device.room} · $modeLabel" + (if (!reachable) " (sin resp.)" else ""),
                            color = if (!reachable) {
                                cs.error
                            } else if (state.isOn) {
                                cs.onPrimaryContainer.copy(alpha = 0.85f)
                            } else {
                                cs.onSurfaceVariant
                            },
                            fontSize = 14.sp,
                            maxLines = 1,
                            overflow = TextOverflow.Ellipsis,
                        )
                    }

                    Box(
                        modifier = Modifier.fillMaxHeight(),
                        contentAlignment = Alignment.Center,
                    ) {
                        Text(
                            text = rightStatusText,
                            color = if (state.isOn) cs.primary else cs.onSurfaceVariant.copy(alpha = 0.7f),
                            fontSize = 19.sp,
                            fontWeight = FontWeight.Bold,
                            maxLines = 1,
                        )
                    }
                }
            }

            HorizontalDivider(
                thickness = 1.dp,
                color = cs.outlineVariant.copy(alpha = 0.45f),
            )

            // Bottom strip adapted by device category (Lights, AC, Smart TV, Switch)
            Row(
                modifier = Modifier
                    .fillMaxWidth()
                    .padding(horizontal = 14.dp, vertical = 10.dp),
                horizontalArrangement = Arrangement.SpaceBetween,
                verticalAlignment = Alignment.CenterVertically,
            ) {
                Row(
                    modifier = Modifier.weight(1f).horizontalScroll(rememberScrollState()),
                    horizontalArrangement = Arrangement.spacedBy(8.dp),
                    verticalAlignment = Alignment.CenterVertically,
                ) {
                    when (device.category) {
                        "ac" -> {
                            val curTemp = state.targetTempC ?: 24
                            AssistChip(
                                onClick = { onAcControl?.invoke((curTemp - 1).coerceAtLeast(16), null) },
                                label = { Text("-1°C") },
                            )
                            AssistChip(
                                onClick = { onAcControl?.invoke((curTemp + 1).coerceAtMost(30), null) },
                                label = { Text("+1°C") },
                            )
                            AssistChip(
                                onClick = { onAcControl?.invoke(curTemp, "cool") },
                                leadingIcon = { Icon(Icons.Rounded.AcUnit, contentDescription = null, modifier = Modifier.size(16.dp)) },
                                label = { Text("Frío") },
                            )
                            AssistChip(
                                onClick = { onAcControl?.invoke(curTemp, "heat") },
                                leadingIcon = { Icon(Icons.Rounded.WbSunny, contentDescription = null, modifier = Modifier.size(16.dp)) },
                                label = { Text("Calor") },
                            )
                        }
                        "tv" -> {
                            AssistChip(
                                onClick = { onTvMuteToggle?.invoke() },
                                leadingIcon = { Icon(Icons.Rounded.VolumeOff, contentDescription = null, modifier = Modifier.size(16.dp)) },
                                label = { Text(if (state.isMuted) "Unmute" else "Mute") },
                            )
                            AssistChip(
                                onClick = { onTvPlayPause?.invoke() },
                                leadingIcon = { Icon(Icons.Rounded.PlayArrow, contentDescription = null, modifier = Modifier.size(16.dp)) },
                                label = { Text("Play/Pausa") },
                            )
                            AssistChip(
                                onClick = { onTogglePower(!state.isOn) },
                                leadingIcon = { Icon(Icons.Rounded.Tv, contentDescription = null, modifier = Modifier.size(16.dp)) },
                                label = { Text(device.driver.uppercase()) },
                            )
                        }
                        "switch", "fan" -> {
                            AssistChip(
                                onClick = { onTogglePower(!state.isOn) },
                                label = { Text("${device.driver.uppercase()} · ${if (state.isOn) "Activo" else "Apagado"}") },
                            )
                        }
                        else -> {
                            OutlinedButton(
                                onClick = onOpenControls,
                                shape = RoundedCornerShape(50),
                                border = BorderStroke(1.2.dp, cs.outline),
                                contentPadding = PaddingValues(horizontal = 18.dp, vertical = 10.dp),
                            ) {
                                Icon(
                                    Icons.Rounded.Palette,
                                    contentDescription = null,
                                    tint = cs.primary,
                                    modifier = Modifier.size(20.dp),
                                )
                                Spacer(Modifier.width(8.dp))
                                Text(
                                    text = "Color / Escenas",
                                    color = cs.primary,
                                    fontSize = 15.sp,
                                    fontWeight = FontWeight.Medium,
                                    maxLines = 1,
                                )
                            }
                        }
                    }
                }

                if (onRenameRequest != null || onDeleteRequest != null) {
                    Row {
                        if (onRenameRequest != null) {
                            IconButton(onClick = onRenameRequest) {
                                Icon(
                                    Icons.Rounded.Edit,
                                    contentDescription = "Editar",
                                    tint = cs.onSurfaceVariant,
                                )
                            }
                        }
                        if (onDeleteRequest != null) {
                            IconButton(onClick = onDeleteRequest) {
                                Icon(
                                    Icons.Rounded.Delete,
                                    contentDescription = "Quitar",
                                    tint = cs.onSurfaceVariant,
                                )
                            }
                        }
                    }
                }
            }
        }
    }
}

@OptIn(ExperimentalLayoutApi::class)
@Composable
fun WizControlDialog(
    device: WizDeviceConfig,
    state: SmartDeviceStateDto,
    scenes: List<WizSceneInfoDto>,
    onDismiss: () -> Unit,
    onInteractionPing: () -> Unit = {},
    onTogglePower: (Boolean) -> Unit,
    onBrightnessChange: (Int) -> Unit,
    onSetRgb: (Int, Int, Int, Int?) -> Unit,
    onSetTemp: (Int, Int?) -> Unit,
    onSetScene: (Int, Int?, Int?) -> Unit,
    onSaveFavorite: (SmartSceneFavorite) -> Unit,
) {
    val isLandscape = LocalConfiguration.current.orientation == Configuration.ORIENTATION_LANDSCAPE

    var tab by remember {
        mutableIntStateOf(
            when {
                state.sceneId != null && state.sceneId > 0 -> 2
                state.tempKelvin != null && state.tempKelvin > 0 -> 1
                state.r != null && state.g != null && state.b != null -> 0
                else -> 1
            }
        )
    }
    var hue by remember { mutableFloatStateOf(30f) }
    var sat by remember { mutableFloatStateOf(0.85f) }
    var kelvin by remember(state.tempKelvin) {
        mutableFloatStateOf((state.tempKelvin ?: 5000).toFloat())
    }
    var sceneSpeed by remember(state.speed) {
        mutableFloatStateOf((state.speed ?: 100).toFloat())
    }
    var favNamePrompt by remember { mutableStateOf(false) }

    val modeLabel = remember(state, scenes) { WizColorMath.stateModeLabel(state, scenes, device.category) }

    Dialog(
        onDismissRequest = onDismiss,
        properties = DialogProperties(usePlatformDefaultWidth = false),
    ) {
        Surface(
            modifier = Modifier
                .fillMaxWidth(if (isLandscape) 0.94f else 0.96f)
                .heightIn(max = if (isLandscape) 480.dp else 640.dp)
                .padding(8.dp),
            shape = RoundedCornerShape(28.dp),
            color = MaterialTheme.colorScheme.surfaceContainerHigh,
            tonalElevation = 8.dp,
        ) {
            Column(
                modifier = Modifier
                    .fillMaxWidth()
                    .padding(16.dp),
                verticalArrangement = Arrangement.spacedBy(10.dp),
            ) {
                Row(
                    modifier = Modifier.fillMaxWidth(),
                    horizontalArrangement = Arrangement.SpaceBetween,
                    verticalAlignment = Alignment.CenterVertically,
                ) {
                    Column(modifier = Modifier.weight(1f)) {
                        Text(
                            text = "${device.name} · $modeLabel",
                            style = MaterialTheme.typography.titleLarge,
                            maxLines = 1,
                            overflow = TextOverflow.Ellipsis,
                        )
                    }
                    Row(
                        horizontalArrangement = Arrangement.spacedBy(8.dp),
                        verticalAlignment = Alignment.CenterVertically,
                    ) {
                        FilledTonalButton(
                            onClick = {
                                onInteractionPing()
                                onSetScene(12, 100, state.brightness)
                            },
                            contentPadding = PaddingValues(horizontal = 14.dp, vertical = 10.dp),
                        ) {
                            Icon(Icons.Rounded.WbSunny, contentDescription = null)
                            Spacer(Modifier.width(6.dp))
                            Text("Luz Diurna")
                        }
                        FilledTonalButton(
                            onClick = {
                                onInteractionPing()
                                favNamePrompt = true
                            },
                        ) {
                            Icon(Icons.Rounded.BookmarkAdd, contentDescription = "Guardar favorito")
                        }
                        Button(
                            onClick = {
                                onInteractionPing()
                                onTogglePower(!state.isOn)
                            },
                            colors = ButtonDefaults.buttonColors(
                                containerColor = if (state.isOn) {
                                    MaterialTheme.colorScheme.primary
                                } else {
                                    MaterialTheme.colorScheme.surfaceVariant
                                },
                            ),
                        ) {
                            Icon(Icons.Rounded.PowerSettingsNew, contentDescription = null)
                            Spacer(Modifier.width(6.dp))
                            Text(if (state.isOn) "ON" else "OFF")
                        }
                        OutlinedButton(onClick = onDismiss) {
                            Text("Listo")
                        }
                    }
                }

                TabRow(selectedTabIndex = tab) {
                    Tab(
                        selected = tab == 0,
                        onClick = {
                            onInteractionPing()
                            tab = 0
                        },
                        text = { Text("Color RGB", fontSize = 15.sp, fontWeight = FontWeight.SemiBold) },
                    )
                    Tab(
                        selected = tab == 1,
                        onClick = {
                            onInteractionPing()
                            tab = 1
                        },
                        text = { Text("Luz Blanca / Día", fontSize = 15.sp, fontWeight = FontWeight.SemiBold) },
                    )
                    Tab(
                        selected = tab == 2,
                        onClick = {
                            onInteractionPing()
                            tab = 2
                        },
                        text = { Text("Escenas (32)", fontSize = 15.sp, fontWeight = FontWeight.SemiBold) },
                    )
                }

                if (isLandscape) {
                    Row(
                        modifier = Modifier
                            .fillMaxWidth()
                            .weight(1f, fill = false),
                        horizontalArrangement = Arrangement.spacedBy(16.dp),
                    ) {
                        Column(
                            modifier = Modifier
                                .weight(0.42f)
                                .verticalScroll(rememberScrollState()),
                            verticalArrangement = Arrangement.spacedBy(10.dp),
                            horizontalAlignment = Alignment.CenterHorizontally,
                        ) {
                            GoogleHomeBrightnessBar(
                                brightness = state.brightness,
                                onBrightnessChange = {
                                    onInteractionPing()
                                    onBrightnessChange(it)
                                },
                            )

                            when (tab) {
                                0 -> {
                                    HsvColorWheel(
                                        hue = hue,
                                        sat = sat,
                                        onPick = { h, s ->
                                            hue = h
                                            sat = s
                                            onInteractionPing()
                                            val (r, g, b) = WizColorMath.hsvToRgb(h, s, 1f)
                                            onSetRgb(r, g, b, state.brightness)
                                        },
                                        modifier = Modifier.size(175.dp),
                                    )
                                }
                                1 -> {
                                    Text(
                                        "Temperatura Blanca: ${kelvin.roundToInt()}K",
                                        style = MaterialTheme.typography.titleMedium,
                                    )
                                    Slider(
                                        value = kelvin.coerceIn(2200f, 6500f),
                                        onValueChange = {
                                            kelvin = it
                                            onInteractionPing()
                                            onSetTemp(it.roundToInt(), state.brightness)
                                        },
                                        valueRange = 2200f..6500f,
                                    )
                                }
                                2 -> {
                                    Text(
                                        "Velocidad de Escena: ${sceneSpeed.roundToInt()}",
                                        style = MaterialTheme.typography.titleMedium,
                                    )
                                    Slider(
                                        value = sceneSpeed.coerceIn(10f, 200f),
                                        onValueChange = {
                                            sceneSpeed = it
                                            onInteractionPing()
                                            val activeScene = state.sceneId ?: 12
                                            onSetScene(activeScene, it.roundToInt(), state.brightness)
                                        },
                                        valueRange = 10f..200f,
                                    )
                                }
                            }
                        }

                        VerticalDivider()

                        Column(
                            modifier = Modifier
                                .weight(0.58f)
                                .verticalScroll(rememberScrollState()),
                            verticalArrangement = Arrangement.spacedBy(10.dp),
                        ) {
                            RightColumnPresetsGrid(
                                tab = tab,
                                state = state,
                                scenes = scenes,
                                sceneSpeed = sceneSpeed.roundToInt(),
                                onInteractionPing = onInteractionPing,
                                onSetRgb = onSetRgb,
                                onSetTemp = { k, d ->
                                    kelvin = k.toFloat()
                                    onSetTemp(k, d)
                                },
                                onSetScene = onSetScene,
                            )
                        }
                    }
                } else {
                    Column(
                        modifier = Modifier
                            .fillMaxWidth()
                            .verticalScroll(rememberScrollState()),
                        verticalArrangement = Arrangement.spacedBy(12.dp),
                        horizontalAlignment = Alignment.CenterHorizontally,
                    ) {
                        GoogleHomeBrightnessBar(
                            brightness = state.brightness,
                            onBrightnessChange = {
                                onInteractionPing()
                                onBrightnessChange(it)
                            },
                        )
                        when (tab) {
                            0 -> HsvColorWheel(
                                hue = hue,
                                sat = sat,
                                onPick = { h, s ->
                                    hue = h
                                    sat = s
                                    onInteractionPing()
                                    val (r, g, b) = WizColorMath.hsvToRgb(h, s, 1f)
                                    onSetRgb(r, g, b, state.brightness)
                                },
                                modifier = Modifier.size(185.dp),
                            )
                            1 -> {
                                Text("Temperatura: ${kelvin.roundToInt()}K", style = MaterialTheme.typography.titleMedium)
                                Slider(
                                    value = kelvin.coerceIn(2200f, 6500f),
                                    onValueChange = {
                                        kelvin = it
                                        onInteractionPing()
                                        onSetTemp(it.roundToInt(), state.brightness)
                                    },
                                    valueRange = 2200f..6500f,
                                )
                            }
                            2 -> {
                                Text("Velocidad: ${sceneSpeed.roundToInt()}", style = MaterialTheme.typography.titleMedium)
                                Slider(
                                    value = sceneSpeed.coerceIn(10f, 200f),
                                    onValueChange = {
                                        sceneSpeed = it
                                        onInteractionPing()
                                        onSetScene(state.sceneId ?: 12, it.roundToInt(), state.brightness)
                                    },
                                    valueRange = 10f..200f,
                                )
                            }
                        }
                        RightColumnPresetsGrid(
                            tab = tab,
                            state = state,
                            scenes = scenes,
                            sceneSpeed = sceneSpeed.roundToInt(),
                            onInteractionPing = onInteractionPing,
                            onSetRgb = onSetRgb,
                            onSetTemp = { k, d ->
                                kelvin = k.toFloat()
                                onSetTemp(k, d)
                            },
                            onSetScene = onSetScene,
                        )
                    }
                }
            }
        }
    }

    if (favNamePrompt) {
        SaveFavoriteDialog(
            defaultName = when (tab) {
                0 -> "Color ${device.name}"
                1 -> "Blanco ${kelvin.roundToInt()}K"
                else -> scenes.firstOrNull { it.id == state.sceneId }?.nameEs ?: "Escena ${device.name}"
            },
            onDismiss = { favNamePrompt = false },
            onConfirm = { name ->
                val (r, g, b) = WizColorMath.hsvToRgb(hue, sat, 1f)
                val fav = when (tab) {
                    0 -> SmartSceneFavorite(
                        id = "",
                        name = name,
                        deviceId = device.id,
                        actionType = "rgb",
                        r = state.r ?: r,
                        g = state.g ?: g,
                        b = state.b ?: b,
                        brightness = state.brightness,
                    )
                    1 -> SmartSceneFavorite(
                        id = "",
                        name = name,
                        deviceId = device.id,
                        actionType = "temp",
                        kelvin = kelvin.roundToInt(),
                        brightness = state.brightness,
                    )
                    else -> SmartSceneFavorite(
                        id = "",
                        name = name,
                        deviceId = device.id,
                        actionType = "scene",
                        sceneId = state.sceneId ?: 12,
                        speed = sceneSpeed.roundToInt(),
                        brightness = state.brightness,
                    )
                }
                onSaveFavorite(fav)
                favNamePrompt = false
            },
        )
    }
}

@Composable
private fun GoogleHomeBrightnessBar(
    brightness: Int,
    onBrightnessChange: (Int) -> Unit,
) {
    val curOnBrightness by rememberUpdatedState(onBrightnessChange)
    val cs = MaterialTheme.colorScheme
    val frac = (brightness.coerceIn(10, 100) / 100f)

    Box(
        modifier = Modifier
            .fillMaxWidth()
            .height(54.dp)
            .clip(RoundedCornerShape(18.dp))
            .background(cs.surfaceContainerHighest)
            .pointerInput(Unit) {
                detectTapGestures { pos ->
                    val w = size.width.toFloat().coerceAtLeast(1f)
                    val pct = ((pos.x / w) * 100f).roundToInt().coerceIn(10, 100)
                    curOnBrightness(pct)
                }
            }
            .pointerInput(Unit) {
                detectHorizontalDragGestures { change, _ ->
                    change.consume()
                    val w = size.width.toFloat().coerceAtLeast(1f)
                    val pct = ((change.position.x / w) * 100f).roundToInt().coerceIn(10, 100)
                    curOnBrightness(pct)
                }
            },
    ) {
        Canvas(modifier = Modifier.fillMaxSize()) {
            drawRect(
                color = cs.primaryContainer,
                topLeft = Offset.Zero,
                size = Size(size.width * frac, size.height),
            )
        }
        Row(
            modifier = Modifier
                .fillMaxSize()
                .padding(horizontal = 16.dp),
            horizontalArrangement = Arrangement.SpaceBetween,
            verticalAlignment = Alignment.CenterVertically,
        ) {
            Text(
                text = "Deslizar Brillo",
                color = cs.onPrimaryContainer,
                fontWeight = FontWeight.Medium,
                fontSize = 15.sp,
            )
            Text(
                text = "%$brightness",
                color = cs.primary,
                fontWeight = FontWeight.Bold,
                fontSize = 17.sp,
            )
        }
    }
}

@OptIn(ExperimentalLayoutApi::class)
@Composable
private fun RightColumnPresetsGrid(
    tab: Int,
    state: SmartDeviceStateDto,
    scenes: List<WizSceneInfoDto>,
    sceneSpeed: Int,
    onInteractionPing: () -> Unit,
    onSetRgb: (Int, Int, Int, Int?) -> Unit,
    onSetTemp: (Int, Int?) -> Unit,
    onSetScene: (Int, Int?, Int?) -> Unit,
) {
    when (tab) {
        0 -> {
            val swatches = listOf(
                Triple(255, 55, 55) to "Rojo",
                Triple(255, 145, 30) to "Ámbar",
                Triple(255, 215, 45) to "Oro",
                Triple(50, 225, 115) to "Esmeralda",
                Triple(40, 200, 255) to "Cian",
                Triple(60, 110, 255) to "Azul",
                Triple(165, 75, 255) to "Violeta",
                Triple(250, 70, 195) to "Rosa",
            )
            FlowRow(
                horizontalArrangement = Arrangement.spacedBy(10.dp),
                verticalArrangement = Arrangement.spacedBy(10.dp),
                modifier = Modifier.fillMaxWidth(),
            ) {
                swatches.forEach { (rgb, label) ->
                    val selected = state.sceneId == null && state.tempKelvin == null &&
                        state.r == rgb.first && state.g == rgb.second && state.b == rgb.third
                    LargeActionTile(
                        label = label,
                        dotColor = Color(rgb.first, rgb.second, rgb.third),
                        selected = selected,
                        onClick = {
                            onInteractionPing()
                            onSetRgb(rgb.first, rgb.second, rgb.third, state.brightness)
                        },
                    )
                }
            }
        }

        1 -> {
            val whitePresets = listOf(
                Triple(12, true, "Luz Diurna (Escena)") to Color(0xFFEEF6FF),
                Triple(11, true, "Luz Cálida (Escena)") to Color(0xFFFFD6A5),
                Triple(14, true, "Luz Nocturna") to Color(0xFFFFB067),
                Triple(2200, false, "Vela 2200K") to Color(0xFFFFB56B),
                Triple(2700, false, "Cálida 2700K") to Color(0xFFFFD6A5),
                Triple(4000, false, "Neutra 4000K") to Color(0xFFFFF3E2),
                Triple(5000, false, "Día 5000K") to Color(0xFFEEF6FF),
                Triple(6500, false, "Fría 6500K") to Color(0xFFDCEBFF),
            )
            FlowRow(
                horizontalArrangement = Arrangement.spacedBy(10.dp),
                verticalArrangement = Arrangement.spacedBy(10.dp),
                modifier = Modifier.fillMaxWidth(),
            ) {
                whitePresets.forEach { (cfg, col) ->
                    val (valId, isScene, label) = cfg
                    val selected = if (isScene) {
                        state.sceneId == valId
                    } else {
                        state.sceneId == null && state.tempKelvin == valId
                    }
                    LargeActionTile(
                        label = label,
                        dotColor = col,
                        selected = selected,
                        onClick = {
                            onInteractionPing()
                            if (isScene) {
                                onSetScene(valId, sceneSpeed, state.brightness)
                            } else {
                                onSetTemp(valId, state.brightness)
                            }
                        },
                    )
                }
            }
        }

        2 -> {
            FlowRow(
                horizontalArrangement = Arrangement.spacedBy(10.dp),
                verticalArrangement = Arrangement.spacedBy(10.dp),
                modifier = Modifier.fillMaxWidth(),
            ) {
                scenes.forEach { scene ->
                    val selected = state.sceneId == scene.id
                    LargeActionTile(
                        label = scene.nameEs,
                        dotColor = null,
                        selected = selected,
                        onClick = {
                            onInteractionPing()
                            onSetScene(scene.id, sceneSpeed, state.brightness)
                        },
                    )
                }
            }
        }
    }
}

@Composable
private fun LargeActionTile(
    label: String,
    dotColor: Color?,
    selected: Boolean,
    onClick: () -> Unit,
) {
    Button(
        onClick = onClick,
        modifier = Modifier.height(52.dp),
        shape = RoundedCornerShape(16.dp),
        colors = ButtonDefaults.buttonColors(
            containerColor = if (selected) {
                MaterialTheme.colorScheme.primary
            } else {
                MaterialTheme.colorScheme.surfaceContainerHighest
            },
            contentColor = if (selected) {
                MaterialTheme.colorScheme.onPrimary
            } else {
                MaterialTheme.colorScheme.onSurface
            },
        ),
        contentPadding = PaddingValues(horizontal = 16.dp, vertical = 10.dp),
    ) {
        if (dotColor != null) {
            Box(
                modifier = Modifier
                    .size(14.dp)
                    .clip(CircleShape)
                    .background(dotColor),
            )
            Spacer(Modifier.width(8.dp))
        }
        Text(
            text = label,
            fontSize = 15.sp,
            fontWeight = if (selected) FontWeight.Bold else FontWeight.Medium,
            maxLines = 1,
        )
    }
}

@Composable
fun HsvColorWheel(
    hue: Float,
    sat: Float,
    onPick: (Float, Float) -> Unit,
    modifier: Modifier = Modifier,
) {
    val hueColors = remember {
        listOf(
            Color.Red,
            Color.Yellow,
            Color.Green,
            Color.Cyan,
            Color.Blue,
            Color.Magenta,
            Color.Red,
        )
    }

    fun handleTouch(pos: Offset, width: Float, height: Float) {
        val cx = width / 2f
        val cy = height / 2f
        val radius = (minOf(width, height) / 2f).coerceAtLeast(1f)
        val dx = pos.x - cx
        val dy = pos.y - cy
        val dist = hypot(dx, dy).coerceAtMost(radius)
        val deg = ((Math.toDegrees(atan2(dy.toDouble(), dx.toDouble())).toFloat()) + 360f) % 360f
        onPick(deg, (dist / radius).coerceIn(0f, 1f))
    }

    Box(modifier = modifier) {
        Canvas(
            modifier = Modifier
                .fillMaxSize()
                .pointerInput(Unit) {
                    detectTapGestures { pos ->
                        handleTouch(pos, size.width.toFloat(), size.height.toFloat())
                    }
                }
                .pointerInput(Unit) {
                    detectDragGestures { change, _ ->
                        change.consume()
                        handleTouch(change.position, size.width.toFloat(), size.height.toFloat())
                    }
                },
        ) {
            val radius = size.minDimension / 2f
            drawCircle(
                brush = Brush.sweepGradient(hueColors),
                radius = radius,
            )
            drawCircle(
                brush = Brush.radialGradient(
                    colors = listOf(Color.White, Color.Transparent),
                    radius = radius,
                ),
                radius = radius,
            )
            val rad = Math.toRadians(hue.toDouble())
            val mx = center.x + (cos(rad) * radius * sat).toFloat()
            val my = center.y + (sin(rad) * radius * sat).toFloat()
            drawCircle(
                color = Color.White,
                radius = 12f,
                center = Offset(mx, my),
                style = Stroke(width = 3.5f),
            )
            drawCircle(
                color = Color.Black.copy(alpha = 0.45f),
                radius = 14f,
                center = Offset(mx, my),
                style = Stroke(width = 1.5f),
            )
        }
    }
}

@OptIn(ExperimentalLayoutApi::class)
@Composable
fun AddByIpDialog(
    onDismiss: () -> Unit,
    onAdd: (ip: String, name: String?, room: String) -> Unit,
    onAddMultiBrand: ((ip: String, name: String?, room: String, driver: String, category: String) -> Unit)? = null,
) {
    var ip by remember { mutableStateOf("") }
    var name by remember { mutableStateOf("") }
    var room by remember { mutableStateOf("Escritorio") }
    var driver by remember { mutableStateOf("wiz") }
    var category by remember { mutableStateOf("light") }

    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text("Añadir Dispositivo Local (Multi-Marca)") },
        text = {
            Column(
                modifier = Modifier.verticalScroll(rememberScrollState()),
                verticalArrangement = Arrangement.spacedBy(8.dp),
            ) {
                Text("Protocolo / Marca", style = MaterialTheme.typography.labelLarge, color = MaterialTheme.colorScheme.primary)
                FlowRow(horizontalArrangement = Arrangement.spacedBy(6.dp)) {
                    listOf(
                        "wiz" to "WiZ UDP",
                        "wled" to "WLED",
                        "shelly" to "Shelly",
                        "tasmota" to "Tasmota",
                        "yeelight" to "Yeelight",
                    ).forEach { (id, label) ->
                        FilterChip(
                            selected = driver == id,
                            onClick = { driver = id },
                            label = { Text(label) },
                        )
                    }
                }

                Text("Tipo de Dispositivo", style = MaterialTheme.typography.labelLarge, color = MaterialTheme.colorScheme.primary)
                FlowRow(horizontalArrangement = Arrangement.spacedBy(6.dp)) {
                    listOf(
                        "light" to "Foco / Luz",
                        "ac" to "Aire Acond.",
                        "tv" to "Smart TV",
                        "switch" to "Enchufe",
                        "fan" to "Ventilador",
                    ).forEach { (id, label) ->
                        FilterChip(
                            selected = category == id,
                            onClick = { category = id },
                            label = { Text(label) },
                        )
                    }
                }

                OutlinedTextField(
                    value = ip,
                    onValueChange = { ip = it },
                    label = { Text("IP local (ej. 192.168.100.138)") },
                    singleLine = true,
                    modifier = Modifier.fillMaxWidth(),
                )
                OutlinedTextField(
                    value = name,
                    onValueChange = { name = it },
                    label = { Text("Nombre (opcional)") },
                    singleLine = true,
                    modifier = Modifier.fillMaxWidth(),
                )
                OutlinedTextField(
                    value = room,
                    onValueChange = { room = it },
                    label = { Text("Ambiente") },
                    singleLine = true,
                    modifier = Modifier.fillMaxWidth(),
                )
            }
        },
        confirmButton = {
            Button(
                enabled = ip.isNotBlank(),
                onClick = {
                    if (onAddMultiBrand != null) {
                        onAddMultiBrand(ip, name.ifBlank { null }, room, driver, category)
                    } else {
                        onAdd(ip, name.ifBlank { null }, room)
                    }
                },
            ) {
                Text("Conectar")
            }
        },
        dismissButton = {
            TextButton(onClick = onDismiss) { Text("Cancelar") }
        },
    )
}

@Composable
fun HomeAssistantConnectDialog(
    initialConfig: HomeAssistantConfig,
    detectedUrl: String?,
    onDismiss: () -> Unit,
    onConnect: (serverUrl: String, token: String) -> Unit,
) {
    var url by remember(initialConfig.serverUrl, detectedUrl) {
        mutableStateOf(
            initialConfig.serverUrl.ifBlank { detectedUrl ?: "http://homeassistant.local:8123" }
        )
    }
    var token by remember(initialConfig.accessToken) {
        mutableStateOf(initialConfig.accessToken)
    }

    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text("Vincular Home Assistant Server") },
        text = {
            Column(
                modifier = Modifier.verticalScroll(rememberScrollState()),
                verticalArrangement = Arrangement.spacedBy(8.dp),
            ) {
                Text(
                    "Importa y controla automáticamente focos de cualquier marca (light.*), Smart TVs (media_player.*), Aires Acondicionados (climate.*), enchufes (switch.*) y ventiladores (fan.*).",
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
                OutlinedTextField(
                    value = url,
                    onValueChange = { url = it },
                    label = { Text("URL del servidor (ej. http://192.168.100.50:8123)") },
                    singleLine = true,
                    modifier = Modifier.fillMaxWidth(),
                )
                OutlinedTextField(
                    value = token,
                    onValueChange = { token = it },
                    label = { Text("Long-Lived Access Token (Perfil -> Tokens)") },
                    maxLines = 3,
                    modifier = Modifier.fillMaxWidth(),
                )
            }
        },
        confirmButton = {
            Button(
                enabled = url.isNotBlank(),
                onClick = { onConnect(url, token) },
            ) {
                Text("Sincronizar Dispositivos")
            }
        },
        dismissButton = {
            TextButton(onClick = onDismiss) { Text("Cancelar") }
        },
    )
}

@Composable
private fun SaveFavoriteDialog(
    defaultName: String,
    onDismiss: () -> Unit,
    onConfirm: (String) -> Unit,
) {
    var name by remember(defaultName) { mutableStateOf(defaultName) }
    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text("Guardar favorito") },
        text = {
            OutlinedTextField(
                value = name,
                onValueChange = { name = it },
                label = { Text("Nombre del acceso rápido") },
                singleLine = true,
                modifier = Modifier.fillMaxWidth(),
            )
        },
        confirmButton = {
            Button(
                enabled = name.isNotBlank(),
                onClick = { onConfirm(name.trim()) },
            ) {
                Text("Guardar")
            }
        },
        dismissButton = {
            TextButton(onClick = onDismiss) { Text("Cancelar") }
        },
    )
}
