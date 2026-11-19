package com.criperto.standby.smarthome

import androidx.compose.foundation.Canvas
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.gestures.detectDragGestures
import androidx.compose.foundation.gestures.detectTapGestures
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ExperimentalLayoutApi
import androidx.compose.foundation.layout.FlowRow
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
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
import androidx.compose.material.icons.rounded.BookmarkAdd
import androidx.compose.material.icons.rounded.Delete
import androidx.compose.material.icons.rounded.Edit
import androidx.compose.material.icons.rounded.Lightbulb
import androidx.compose.material.icons.rounded.Palette
import androidx.compose.material.icons.rounded.PowerSettingsNew
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Button
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.ElevatedCard
import androidx.compose.material3.FilterChip
import androidx.compose.material3.FilledIconToggleButton
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Slider
import androidx.compose.material3.Tab
import androidx.compose.material3.TabRow
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableFloatStateOf
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import kotlin.math.atan2
import kotlin.math.cos
import kotlin.math.hypot
import kotlin.math.roundToInt
import kotlin.math.sin

@Composable
fun WizDeviceCard(
    device: WizDeviceConfig,
    state: SmartDeviceStateDto,
    reachable: Boolean,
    compact: Boolean = false,
    onTogglePower: (Boolean) -> Unit,
    onBrightnessChange: (Int) -> Unit,
    onOpenControls: () -> Unit,
    onRenameRequest: (() -> Unit)? = null,
    onDeleteRequest: (() -> Unit)? = null,
    modifier: Modifier = Modifier,
) {
    val dotColor = remember(state.isOn, state.r, state.g, state.b, state.tempKelvin) {
        val hex = WizColorMath.stateToHex(state)
        runCatching { Color(android.graphics.Color.parseColor(hex)) }.getOrDefault(Color.White)
    }

    ElevatedCard(
        modifier = modifier,
        shape = RoundedCornerShape(24.dp),
        colors = CardDefaults.elevatedCardColors(
            containerColor = if (state.isOn) {
                MaterialTheme.colorScheme.secondaryContainer
            } else {
                MaterialTheme.colorScheme.surfaceContainerHigh
            },
        ),
    ) {
        Column(
            modifier = Modifier
                .fillMaxWidth()
                .padding(if (compact) 14.dp else 16.dp),
            verticalArrangement = Arrangement.spacedBy(10.dp),
        ) {
            Row(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.SpaceBetween,
                verticalAlignment = Alignment.CenterVertically,
            ) {
                Row(
                    modifier = Modifier.weight(1f),
                    horizontalArrangement = Arrangement.spacedBy(10.dp),
                    verticalAlignment = Alignment.CenterVertically,
                ) {
                    Box(
                        modifier = Modifier
                            .size(14.dp)
                            .clip(CircleShape)
                            .background(dotColor)
                            .border(1.dp, MaterialTheme.colorScheme.outlineVariant, CircleShape),
                    )
                    Column {
                        Text(
                            text = device.name,
                            style = MaterialTheme.typography.titleMedium,
                            maxLines = 1,
                            overflow = TextOverflow.Ellipsis,
                        )
                        Text(
                            text = "${device.room} · ${device.ip}" + if (!reachable) " (sin respuesta)" else "",
                            style = MaterialTheme.typography.bodySmall,
                            color = if (reachable) {
                                MaterialTheme.colorScheme.onSurfaceVariant
                            } else {
                                MaterialTheme.colorScheme.error
                            },
                            maxLines = 1,
                            overflow = TextOverflow.Ellipsis,
                        )
                    }
                }
                FilledIconToggleButton(
                    checked = state.isOn,
                    onCheckedChange = onTogglePower,
                ) {
                    Icon(Icons.Rounded.PowerSettingsNew, contentDescription = "Encender/Apagar")
                }
            }

            Column {
                Text(
                    text = if (state.isOn) "Brillo ${state.brightness}%" else "Apagada",
                    style = MaterialTheme.typography.labelMedium,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
                Slider(
                    value = state.brightness.coerceIn(10, 100).toFloat(),
                    onValueChange = { onBrightnessChange(it.roundToInt()) },
                    valueRange = 10f..100f,
                )
            }

            Row(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.SpaceBetween,
                verticalAlignment = Alignment.CenterVertically,
            ) {
                OutlinedButton(onClick = onOpenControls) {
                    Icon(Icons.Rounded.Palette, contentDescription = null)
                    Spacer(Modifier.width(6.dp))
                    Text("Color / Escenas")
                }
                Row {
                    if (onRenameRequest != null) {
                        IconButton(onClick = onRenameRequest) {
                            Icon(Icons.Rounded.Edit, contentDescription = "Renombrar")
                        }
                    }
                    if (onDeleteRequest != null) {
                        IconButton(onClick = onDeleteRequest) {
                            Icon(Icons.Rounded.Delete, contentDescription = "Quitar")
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
    var tab by remember { mutableIntStateOf(0) } // 0 = Color, 1 = Blanco K, 2 = Escenas
    var hue by remember { mutableFloatStateOf(30f) }
    var sat by remember { mutableFloatStateOf(0.85f) }
    var kelvin by remember(state.tempKelvin) {
        mutableFloatStateOf((state.tempKelvin ?: 3200).toFloat())
    }
    var sceneSpeed by remember(state.speed) {
        mutableFloatStateOf((state.speed ?: 100).toFloat())
    }
    var favNamePrompt by remember { mutableStateOf(false) }

    AlertDialog(
        onDismissRequest = onDismiss,
        title = {
            Row(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.SpaceBetween,
                verticalAlignment = Alignment.CenterVertically,
            ) {
                Text(device.name, style = MaterialTheme.typography.titleLarge)
                Row {
                    IconButton(onClick = {
                        onInteractionPing()
                        favNamePrompt = true
                    }) {
                        Icon(Icons.Rounded.BookmarkAdd, contentDescription = "Guardar favorito")
                    }
                    FilledIconToggleButton(
                        checked = state.isOn,
                        onCheckedChange = {
                            onInteractionPing()
                            onTogglePower(it)
                        },
                    ) {
                        Icon(Icons.Rounded.PowerSettingsNew, contentDescription = null)
                    }
                }
            }
        },
        text = {
            Column(
                modifier = Modifier
                    .fillMaxWidth()
                    .heightIn(max = 420.dp)
                    .verticalScroll(rememberScrollState()),
                verticalArrangement = Arrangement.spacedBy(12.dp),
            ) {
                TabRow(selectedTabIndex = tab) {
                    Tab(selected = tab == 0, onClick = { tab = 0 }, text = { Text("Color RGB") })
                    Tab(selected = tab == 1, onClick = { tab = 1 }, text = { Text("Blanco") })
                    Tab(selected = tab == 2, onClick = { tab = 2 }, text = { Text("Escenas") })
                }

                Text(
                    "Brillo: ${state.brightness}%",
                    style = MaterialTheme.typography.labelMedium,
                )
                Slider(
                    value = state.brightness.coerceIn(10, 100).toFloat(),
                    onValueChange = {
                        onInteractionPing()
                        onBrightnessChange(it.roundToInt())
                    },
                    valueRange = 10f..100f,
                )

                when (tab) {
                    0 -> {
                        Box(
                            modifier = Modifier.fillMaxWidth(),
                            contentAlignment = Alignment.Center,
                        ) {
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
                                modifier = Modifier.size(190.dp),
                            )
                        }
                        FlowRow(
                            horizontalArrangement = Arrangement.spacedBy(8.dp),
                            verticalArrangement = Arrangement.spacedBy(6.dp),
                        ) {
                            listOf(
                                Triple(255, 60, 60) to "Rojo",
                                Triple(255, 140, 30) to "Ámbar",
                                Triple(80, 235, 120) to "Esmeralda",
                                Triple(50, 190, 255) to "Cian",
                                Triple(90, 100, 255) to "Índigo",
                                Triple(240, 70, 220) to "Magenta",
                            ).forEach { (rgb, label) ->
                                FilterChip(
                                    selected = state.r == rgb.first && state.g == rgb.second && state.b == rgb.third,
                                    onClick = {
                                        onInteractionPing()
                                        onSetRgb(rgb.first, rgb.second, rgb.third, state.brightness)
                                    },
                                    label = { Text(label) },
                                )
                            }
                        }
                    }

                    1 -> {
                        Text(
                            "Temperatura: ${kelvin.roundToInt()}K",
                            style = MaterialTheme.typography.titleSmall,
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
                        FlowRow(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                            listOf(2200 to "Vela", 2700 to "Cálida", 4000 to "Neutra", 6500 to "Fría")
                                .forEach { (k, label) ->
                                    FilterChip(
                                        selected = state.tempKelvin == k,
                                        onClick = {
                                            kelvin = k.toFloat()
                                            onInteractionPing()
                                            onSetTemp(k, state.brightness)
                                        },
                                        label = { Text("$label ${k}K") },
                                    )
                                }
                        }
                    }

                    2 -> {
                        Text(
                            "Velocidad dinámica: ${sceneSpeed.roundToInt()}",
                            style = MaterialTheme.typography.labelMedium,
                        )
                        Slider(
                            value = sceneSpeed.coerceIn(10f, 200f),
                            onValueChange = {
                                sceneSpeed = it
                                onInteractionPing()
                                val activeScene = state.sceneId ?: 1
                                onSetScene(activeScene, it.roundToInt(), state.brightness)
                            },
                            valueRange = 10f..200f,
                        )
                        FlowRow(
                            horizontalArrangement = Arrangement.spacedBy(8.dp),
                            verticalArrangement = Arrangement.spacedBy(6.dp),
                        ) {
                            scenes.forEach { scene ->
                                FilterChip(
                                    selected = state.sceneId == scene.id,
                                    onClick = {
                                        onInteractionPing()
                                        onSetScene(scene.id, sceneSpeed.roundToInt(), state.brightness)
                                    },
                                    label = { Text(scene.nameEs) },
                                )
                            }
                        }
                    }
                }
            }
        },
        confirmButton = {
            Button(onClick = onDismiss) {
                Text("Listo")
            }
        },
    )

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
                        sceneId = state.sceneId ?: 11,
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

@Composable
fun AddByIpDialog(
    onDismiss: () -> Unit,
    onAdd: (ip: String, name: String?, room: String) -> Unit,
) {
    var ip by remember { mutableStateOf("") }
    var name by remember { mutableStateOf("") }
    var room by remember { mutableStateOf("Escritorio") }

    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text("Añadir lámpara WiZ por IP") },
        text = {
            Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
                OutlinedTextField(
                    value = ip,
                    onValueChange = { ip = it },
                    label = { Text("IP local (ej. 192.168.1.55)") },
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
                onClick = { onAdd(ip, name.ifBlank { null }, room) },
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
