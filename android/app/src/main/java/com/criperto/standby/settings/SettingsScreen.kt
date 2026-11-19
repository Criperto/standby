package com.criperto.standby.settings

import android.Manifest
import android.content.Context
import android.content.Intent
import android.content.pm.PackageManager
import android.net.Uri
import android.os.Build
import android.os.PowerManager
import android.provider.Settings
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ColumnScope
import androidx.compose.foundation.layout.ExperimentalLayoutApi
import androidx.compose.foundation.layout.FlowRow
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.rounded.Add
import androidx.compose.material.icons.rounded.BatteryChargingFull
import androidx.compose.material.icons.rounded.Bedtime
import androidx.compose.material.icons.rounded.Brightness6
import androidx.compose.material.icons.rounded.CameraAlt
import androidx.compose.material.icons.rounded.Delete
import androidx.compose.material.icons.rounded.Layers
import androidx.compose.material.icons.rounded.NotificationsActive
import androidx.compose.material.icons.rounded.Security
import androidx.compose.material.icons.rounded.TouchApp
import androidx.compose.material.icons.rounded.WbSunny
import androidx.compose.material3.Button
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.ElevatedCard
import androidx.compose.material3.FilterChip
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Slider
import androidx.compose.material3.Switch
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.unit.dp
import androidx.core.content.ContextCompat
import com.criperto.standby.StandByApplication
import com.criperto.standby.core.AppSettings
import com.criperto.standby.core.DateRangeDto
import com.criperto.standby.standby.MediaBridge
import com.criperto.standby.standby.StandByMonitorService
import kotlinx.coroutines.launch
import kotlin.math.roundToInt

@OptIn(ExperimentalLayoutApi::class)
@Composable
fun SettingsScreen(
    app: StandByApplication,
    modifier: Modifier = Modifier,
) {
    val context = LocalContext.current
    val scope = rememberCoroutineScope()
    val settings by app.settingsRepository.settingsFlow.collectAsState(initial = AppSettings())

    var hasCameraPerm by remember {
        mutableStateOf(
            ContextCompat.checkSelfPermission(context, Manifest.permission.CAMERA) ==
                PackageManager.PERMISSION_GRANTED
        )
    }
    var hasNotifPerm by remember {
        mutableStateOf(
            Build.VERSION.SDK_INT < Build.VERSION_CODES.TIRAMISU ||
                ContextCompat.checkSelfPermission(context, Manifest.permission.POST_NOTIFICATIONS) ==
                PackageManager.PERMISSION_GRANTED
        )
    }

    val cameraLauncher = rememberLauncherForActivityResult(
        ActivityResultContracts.RequestPermission()
    ) { granted ->
        hasCameraPerm = granted
    }

    val notifLauncher = rememberLauncherForActivityResult(
        ActivityResultContracts.RequestPermission()
    ) { granted ->
        hasNotifPerm = granted
    }

    fun updateSettings(block: (AppSettings) -> AppSettings) {
        scope.launch { app.settingsRepository.update(block) }
    }

    LazyColumn(
        modifier = modifier
            .fillMaxSize()
            .padding(horizontal = 16.dp),
        verticalArrangement = Arrangement.spacedBy(12.dp),
    ) {
        item { Spacer(Modifier.height(4.dp)) }

        item {
            SettingsSectionCard(
                title = "Activación y Disparadores",
                icon = Icons.Rounded.Bedtime,
            ) {
                SettingSwitchRow(
                    title = "Reglas de activación habilitadas",
                    subtitle = "Evalúa carga, orientación, horario y calendario",
                    checked = settings.triggersEnabled,
                    onCheckedChange = { checked -> updateSettings { it.copy(triggersEnabled = checked) } },
                )

                SettingSwitchRow(
                    title = "Monitor automático al enchufar",
                    subtitle = "Servicio liviano que lanza StandBy al cumplir las reglas",
                    checked = settings.autoTriggerServiceEnabled,
                    onCheckedChange = { checked ->
                        if (checked && Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU && !hasNotifPerm) {
                            notifLauncher.launch(Manifest.permission.POST_NOTIFICATIONS)
                        }
                        updateSettings { it.copy(autoTriggerServiceEnabled = checked) }
                        StandByMonitorService.syncState(context.applicationContext, checked)
                    },
                )

                OutlinedButton(
                    onClick = {
                        runCatching {
                            context.startActivity(Intent(Settings.ACTION_DREAM_SETTINGS))
                        }
                    },
                    modifier = Modifier.fillMaxWidth(),
                ) {
                    Text("Abrir Salvapantallas del Sistema (DreamService)")
                }

                Spacer(Modifier.height(6.dp))
                Text(
                    "Requisito de carga",
                    style = MaterialTheme.typography.labelLarge,
                    color = MaterialTheme.colorScheme.primary,
                )
                FlowRow(
                    horizontalArrangement = Arrangement.spacedBy(8.dp),
                    verticalArrangement = Arrangement.spacedBy(4.dp),
                ) {
                    listOf(
                        "any_charging" to "Cualquiera",
                        "ac_only" to "Solo CA",
                        "usb_only" to "Solo USB",
                        "wireless_only" to "Inalámbrico",
                        "always_ignore_charging" to "Sin cargador",
                    ).forEach { (id, label) ->
                        FilterChip(
                            selected = settings.chargingReq == id,
                            onClick = { updateSettings { it.copy(chargingReq = id) } },
                            label = { Text(label) },
                        )
                    }
                }

                Spacer(Modifier.height(6.dp))
                Text(
                    "Orientación de disparo y pantalla",
                    style = MaterialTheme.typography.labelLarge,
                    color = MaterialTheme.colorScheme.primary,
                )
                Row(
                    modifier = Modifier.horizontalScroll(rememberScrollState()),
                    horizontalArrangement = Arrangement.spacedBy(8.dp),
                ) {
                    listOf(
                        "landscape_only" to "Solo horizontal",
                        "any_orientation" to "Cualquier orientación",
                    ).forEach { (id, label) ->
                        FilterChip(
                            selected = settings.orientationReq == id,
                            onClick = { updateSettings { it.copy(orientationReq = id) } },
                            label = { Text(label) },
                        )
                    }
                }
                Row(
                    modifier = Modifier.horizontalScroll(rememberScrollState()),
                    horizontalArrangement = Arrangement.spacedBy(8.dp),
                ) {
                    listOf(
                        "sensor_landscape" to "Horizontal sensor",
                        "landscape" to "Horizontal fijo",
                        "reverse_landscape" to "Horizontal invertido",
                        "auto" to "Libre",
                    ).forEach { (id, label) ->
                        FilterChip(
                            selected = settings.orientationMode == id,
                            onClick = { updateSettings { it.copy(orientationMode = id) } },
                            label = { Text(label) },
                        )
                    }
                }

                Spacer(Modifier.height(6.dp))
                SettingSwitchRow(
                    title = "Ventana horaria activa",
                    subtitle = if (settings.timeWindowEnabled) {
                        "Activa entre ${formatMinute(settings.timeWindowStartMin)} y ${formatMinute(settings.timeWindowEndMin)}"
                    } else {
                        "Disponible las 24 horas"
                    },
                    checked = settings.timeWindowEnabled,
                    onCheckedChange = { checked -> updateSettings { it.copy(timeWindowEnabled = checked) } },
                )
                if (settings.timeWindowEnabled) {
                    LabeledSlider(
                        label = "Inicio ventana: ${formatMinute(settings.timeWindowStartMin)}",
                        value = settings.timeWindowStartMin.toFloat(),
                        range = 0f..1435f,
                        onValueChange = { v ->
                            updateSettings { it.copy(timeWindowStartMin = roundTo5(v)) }
                        },
                    )
                    LabeledSlider(
                        label = "Fin ventana: ${formatMinute(settings.timeWindowEndMin)}",
                        value = settings.timeWindowEndMin.toFloat(),
                        range = 0f..1435f,
                        onValueChange = { v ->
                            updateSettings { it.copy(timeWindowEndMin = roundTo5(v)) }
                        },
                    )
                }

                Spacer(Modifier.height(6.dp))
                Text(
                    "Días activos",
                    style = MaterialTheme.typography.labelLarge,
                    color = MaterialTheme.colorScheme.primary,
                )
                val dayLabels = listOf("L", "M", "X", "J", "V", "S", "D")
                Row(
                    modifier = Modifier.horizontalScroll(rememberScrollState()),
                    horizontalArrangement = Arrangement.spacedBy(6.dp),
                ) {
                    dayLabels.forEachIndexed { idx, label ->
                        val bit = 1 shl idx
                        val selected = (settings.weekdaysMask and bit) != 0
                        FilterChip(
                            selected = selected,
                            onClick = {
                                val nextMask = settings.weekdaysMask xor bit
                                updateSettings { it.copy(weekdaysMask = if (nextMask == 0) bit else nextMask) }
                            },
                            label = { Text(label) },
                        )
                    }
                }

                Spacer(Modifier.height(8.dp))
                DateExclusionsEditor(
                    ranges = settings.dateExclusions,
                    onAdd = { added ->
                        updateSettings { it.copy(dateExclusions = it.dateExclusions + added) }
                    },
                    onRemoveAt = { idx ->
                        updateSettings {
                            it.copy(dateExclusions = it.dateExclusions.filterIndexed { i, _ -> i != idx })
                        }
                    },
                )
            }
        }

        item {
            SettingsSectionCard(
                title = "Brillo Independiente y Modo Noche",
                icon = Icons.Rounded.Brightness6,
            ) {
                SettingSwitchRow(
                    title = "Usar brillo del sistema",
                    subtitle = "Si está desactivado, aplica un nivel separado solo en StandBy",
                    checked = settings.useSystemBrightness,
                    onCheckedChange = { c -> updateSettings { it.copy(useSystemBrightness = c) } },
                )
                if (!settings.useSystemBrightness) {
                    LabeledSlider(
                        label = "Brillo reloj StandBy: ${(settings.standbyBrightness * 100).roundToInt()}%",
                        value = settings.standbyBrightness,
                        range = 0.01f..1.0f,
                        onValueChange = { v -> updateSettings { it.copy(standbyBrightness = v) } },
                    )
                    LabeledSlider(
                        label = "Brillo nocturno: ${(settings.nightBrightness * 100).roundToInt()}%",
                        value = settings.nightBrightness,
                        range = 0.01f..1.0f,
                        onValueChange = { v -> updateSettings { it.copy(nightBrightness = v) } },
                    )
                    LabeledSlider(
                        label = "Brillo al abrir menú Smart Home: ${(settings.hubBrightness * 100).roundToInt()}%",
                        value = settings.hubBrightness,
                        range = 0.05f..1.0f,
                        onValueChange = { v -> updateSettings { it.copy(hubBrightness = v) } },
                    )
                }

                SettingSwitchRow(
                    title = "Horario nocturno automático",
                    subtitle = "${formatMinute(settings.nightStartMin)} → ${formatMinute(settings.nightEndMin)}",
                    checked = settings.nightModeEnabled,
                    onCheckedChange = { c -> updateSettings { it.copy(nightModeEnabled = c) } },
                )
                if (settings.nightModeEnabled) {
                    LabeledSlider(
                        label = "Inicio noche: ${formatMinute(settings.nightStartMin)}",
                        value = settings.nightStartMin.toFloat(),
                        range = 0f..1435f,
                        onValueChange = { v -> updateSettings { it.copy(nightStartMin = roundTo5(v)) } },
                    )
                    LabeledSlider(
                        label = "Fin noche: ${formatMinute(settings.nightEndMin)}",
                        value = settings.nightEndMin.toFloat(),
                        range = 0f..1435f,
                        onValueChange = { v -> updateSettings { it.copy(nightEndMin = roundTo5(v)) } },
                    )
                    SettingSwitchRow(
                        title = "Tinte rojo nocturno",
                        subtitle = "Velo cálido tenue para reducir luz azul",
                        checked = settings.nightRedTintEnabled,
                        onCheckedChange = { c -> updateSettings { it.copy(nightRedTintEnabled = c) } },
                    )
                }
            }
        }

        item {
            SettingsSectionCard(
                title = "Protección OLED Burn-In",
                icon = Icons.Rounded.Layers,
            ) {
                SettingSwitchRow(
                    title = "Desplazamiento periódico (Pixel Shift)",
                    subtitle = "Mueve suavemente el reloj en paneles AMOLED",
                    checked = settings.pixelShiftEnabled,
                    onCheckedChange = { c -> updateSettings { it.copy(pixelShiftEnabled = c) } },
                )
                if (settings.pixelShiftEnabled) {
                    LabeledSlider(
                        label = "Amplitud máxima: ${settings.pixelShiftMaxPx.roundToInt()} px",
                        value = settings.pixelShiftMaxPx,
                        range = 2f..24f,
                        onValueChange = { v -> updateSettings { it.copy(pixelShiftMaxPx = v) } },
                    )
                    LabeledSlider(
                        label = "Intervalo: ${settings.pixelShiftIntervalSec} s",
                        value = settings.pixelShiftIntervalSec.toFloat(),
                        range = 15f..300f,
                        onValueChange = { v -> updateSettings { it.copy(pixelShiftIntervalSec = v.roundToInt()) } },
                    )
                }
            }
        }

        item {
            SettingsSectionCard(
                title = "Presencia IA (Cámara 24/7) y Luz",
                icon = Icons.Rounded.CameraAlt,
            ) {
                Text(
                    "Modo de presencia",
                    style = MaterialTheme.typography.labelLarge,
                    color = MaterialTheme.colorScheme.primary,
                )
                FlowRow(
                    horizontalArrangement = Arrangement.spacedBy(8.dp),
                    verticalArrangement = Arrangement.spacedBy(4.dp),
                ) {
                    listOf(
                        "disabled" to "Desactivado",
                        "light_only" to "Solo luz",
                        "face_only" to "Solo cara (IA)",
                        "face_or_light" to "Cara o luz",
                        "face_and_light" to "Cara y luz",
                    ).forEach { (id, label) ->
                        FilterChip(
                            selected = settings.presenceMode == id,
                            onClick = {
                                if (id.startsWith("face") && !hasCameraPerm) {
                                    cameraLauncher.launch(Manifest.permission.CAMERA)
                                }
                                updateSettings { it.copy(presenceMode = id) }
                            },
                            label = { Text(label) },
                        )
                    }
                }

                if (settings.presenceMode.startsWith("face")) {
                    Text(
                        text = if (hasCameraPerm) {
                            "Permiso de cámara concedido. Muestreo cada ${settings.cameraPollIntervalSec}s."
                        } else {
                            "Falta permiso de cámara para detección facial local."
                        },
                        style = MaterialTheme.typography.bodySmall,
                        color = if (hasCameraPerm) {
                            MaterialTheme.colorScheme.primary
                        } else {
                            MaterialTheme.colorScheme.error
                        },
                    )
                    if (!hasCameraPerm) {
                        OutlinedButton(onClick = { cameraLauncher.launch(Manifest.permission.CAMERA) }) {
                            Text("Conceder permiso de cámara")
                        }
                    }
                }

                SettingSwitchRow(
                    title = "Apagado AMOLED negro nocturno",
                    subtitle = "Apaga pantalla cuando está oscuro y sin presencia tras el retardo",
                    checked = settings.nightBlackoutEnabled,
                    onCheckedChange = { c -> updateSettings { it.copy(nightBlackoutEnabled = c) } },
                )

                LabeledSlider(
                    label = "Umbral de oscuridad: ${settings.darkThresholdLux.roundToInt()} lux",
                    value = settings.darkThresholdLux,
                    range = 1f..50f,
                    onValueChange = { v -> updateSettings { it.copy(darkThresholdLux = v) } },
                )
                LabeledSlider(
                    label = "Histéresis de luz: ${settings.luxHysteresis.roundToInt()} lux",
                    value = settings.luxHysteresis,
                    range = 1f..15f,
                    onValueChange = { v -> updateSettings { it.copy(luxHysteresis = v) } },
                )
                LabeledSlider(
                    label = "Frecuencia de cámara IA: cada ${settings.cameraPollIntervalSec} s",
                    value = settings.cameraPollIntervalSec.toFloat(),
                    range = 2f..60f,
                    onValueChange = { v -> updateSettings { it.copy(cameraPollIntervalSec = v.roundToInt()) } },
                )
                LabeledSlider(
                    label = "Retardo antes de apagar: ${settings.sleepDelaySec} s",
                    value = settings.sleepDelaySec.toFloat(),
                    range = 5f..180f,
                    onValueChange = { v -> updateSettings { it.copy(sleepDelaySec = v.roundToInt()) } },
                )
            }
        }

        item {
            SettingsSectionCard(
                title = "Tap-to-Wake y Clima",
                icon = Icons.Rounded.TouchApp,
            ) {
                SettingSwitchRow(
                    title = "Tap-to-Wake (Menú Smart Home)",
                    subtitle = "Tocar pantalla abre controles WiZ RGB locales",
                    checked = settings.tapToWakeEnabled,
                    onCheckedChange = { c -> updateSettings { it.copy(tapToWakeEnabled = c) } },
                )
                LabeledSlider(
                    label = "Auto-retorno al reloj tras inactividad: ${settings.autoReturnSec} s",
                    value = settings.autoReturnSec.toFloat(),
                    range = 5f..120f,
                    onValueChange = { v -> updateSettings { it.copy(autoReturnSec = v.roundToInt()) } },
                )
                SettingSwitchRow(
                    title = "Clima en vivo (Open-Meteo)",
                    subtitle = "Sin API key; actualiza periódicamente al mostrar el reloj",
                    checked = settings.weatherEnabled,
                    onCheckedChange = { c -> updateSettings { it.copy(weatherEnabled = c) } },
                )
                if (settings.weatherEnabled) {
                    SettingSwitchRow(
                        title = "Detectar ubicación automáticamente (${settings.weatherCity})",
                        subtitle = "Obtiene ciudad y coordenadas automáticamente por red (ej. Rosario)",
                        checked = settings.weatherAutoLocation,
                        onCheckedChange = { auto -> updateSettings { it.copy(weatherAutoLocation = auto) } },
                    )
                    if (!settings.weatherAutoLocation) {
                        WeatherLocationInputs(
                            city = settings.weatherCity,
                            lat = settings.weatherLat,
                            lon = settings.weatherLon,
                            onApply = { c, la, lo ->
                                updateSettings {
                                    it.copy(weatherCity = c, weatherLat = la, weatherLon = lo)
                                }
                            },
                        )
                    }
                }
            }
        }

        item {
            SettingsSectionCard(
                title = "Permisos del Sistema",
                icon = Icons.Rounded.Security,
            ) {
                val canOverlay = Settings.canDrawOverlays(context)
                val notifAccess = MediaBridge.isNotificationAccessGranted(context)
                val pm = context.getSystemService(Context.POWER_SERVICE) as? PowerManager
                val ignoringBattery = pm?.isIgnoringBatteryOptimizations(context.packageName) ?: false

                PermissionRow(
                    title = "Mostrar sobre otras apps",
                    status = if (canOverlay) "Concedido" else "Necesario para apertura automática",
                    granted = canOverlay,
                    onClick = {
                        runCatching {
                            context.startActivity(
                                Intent(
                                    Settings.ACTION_MANAGE_OVERLAY_PERMISSION,
                                    Uri.parse("package:${context.packageName}"),
                                )
                            )
                        }
                    },
                )
                PermissionRow(
                    title = "Acceso a notificaciones (Multimedia)",
                    status = if (notifAccess) "Concedido" else "Para widget de carátula y reproducción",
                    granted = notifAccess,
                    onClick = {
                        runCatching {
                            context.startActivity(Intent(Settings.ACTION_NOTIFICATION_LISTENER_SETTINGS))
                        }
                    },
                )
                PermissionRow(
                    title = "Optimización de batería",
                    status = if (ignoringBattery) "Exento" else "Recomendado para 24/7 en escritorio",
                    granted = ignoringBattery,
                    onClick = {
                        runCatching {
                            context.startActivity(
                                Intent(
                                    Settings.ACTION_REQUEST_IGNORE_BATTERY_OPTIMIZATIONS,
                                    Uri.parse("package:${context.packageName}"),
                                )
                            )
                        }.onFailure {
                            runCatching {
                                context.startActivity(Intent(Settings.ACTION_IGNORE_BATTERY_OPTIMIZATION_SETTINGS))
                            }
                        }
                    },
                )
            }
        }

        item {
            SettingsSectionCard(
                title = "Acerca de StandBy",
                icon = Icons.Rounded.Layers,
            ) {
                Text(
                    text = "StandBy — Android Desktop Clock & Local Smart Home Hub (Rust + Jetpack Compose M3)",
                    style = MaterialTheme.typography.bodyMedium,
                )
                Text(
                    text = "Autor: Criperto · Versión 0.3.0 · Licencia: GNU GPL v3.0 or later (GPL-3.0-or-later)",
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
                FlowRow(
                    horizontalArrangement = Arrangement.spacedBy(8.dp),
                    verticalArrangement = Arrangement.spacedBy(6.dp),
                ) {
                    Button(
                        onClick = {
                            runCatching {
                                context.startActivity(
                                    Intent(Intent.ACTION_VIEW, Uri.parse("https://github.com/Criperto/standby"))
                                )
                            }
                        },
                    ) {
                        Text("GitHub Repo (Criperto/standby)")
                    }
                    OutlinedButton(
                        onClick = {
                            runCatching {
                                context.startActivity(
                                    Intent(Intent.ACTION_VIEW, Uri.parse("https://github.com/Criperto"))
                                )
                            }
                        },
                    ) {
                        Text("Perfil @Criperto")
                    }
                }
            }
        }

        item { Spacer(Modifier.height(24.dp)) }
    }
}

@Composable
private fun SettingsSectionCard(
    title: String,
    icon: ImageVector,
    content: @Composable ColumnScope.() -> Unit,
) {
    ElevatedCard(
        modifier = Modifier.fillMaxWidth(),
        colors = CardDefaults.elevatedCardColors(
            containerColor = MaterialTheme.colorScheme.surfaceContainerLow,
        ),
    ) {
        Column(
            modifier = Modifier
                .fillMaxWidth()
                .padding(16.dp),
            verticalArrangement = Arrangement.spacedBy(8.dp),
        ) {
            Row(
                verticalAlignment = Alignment.CenterVertically,
                horizontalArrangement = Arrangement.spacedBy(10.dp),
            ) {
                Icon(icon, contentDescription = null, tint = MaterialTheme.colorScheme.primary)
                Text(
                    text = title,
                    style = MaterialTheme.typography.titleMedium,
                    color = MaterialTheme.colorScheme.onSurface,
                )
            }
            content()
        }
    }
}

@Composable
private fun SettingSwitchRow(
    title: String,
    subtitle: String,
    checked: Boolean,
    onCheckedChange: (Boolean) -> Unit,
) {
    Row(
        modifier = Modifier.fillMaxWidth(),
        horizontalArrangement = Arrangement.SpaceBetween,
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Column(modifier = Modifier.weight(1f).padding(end = 12.dp)) {
            Text(title, style = MaterialTheme.typography.bodyLarge)
            Text(
                subtitle,
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
        }
        Switch(checked = checked, onCheckedChange = onCheckedChange)
    }
}

@Composable
private fun LabeledSlider(
    label: String,
    value: Float,
    range: ClosedFloatingPointRange<Float>,
    onValueChange: (Float) -> Unit,
) {
    Column(modifier = Modifier.fillMaxWidth()) {
        Text(
            label,
            style = MaterialTheme.typography.labelMedium,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
        )
        Slider(
            value = value.coerceIn(range.start, range.endInclusive),
            onValueChange = onValueChange,
            valueRange = range,
        )
    }
}

@Composable
private fun DateExclusionsEditor(
    ranges: List<DateRangeDto>,
    onAdd: (DateRangeDto) -> Unit,
    onRemoveAt: (Int) -> Unit,
) {
    var startText by remember { mutableStateOf("20261224") }
    var endText by remember { mutableStateOf("20261226") }
    var isExclusion by remember { mutableStateOf(true) }

    Column(verticalArrangement = Arrangement.spacedBy(6.dp)) {
        Text(
            "Exclusiones / rangos de fecha (YYYYMMDD)",
            style = MaterialTheme.typography.labelLarge,
            color = MaterialTheme.colorScheme.primary,
        )
        ranges.forEachIndexed { idx, r ->
            Row(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.SpaceBetween,
                verticalAlignment = Alignment.CenterVertically,
            ) {
                Text(
                    "${if (r.isExclusion) "Excluir" else "Solo"} ${r.startYyyymmdd} – ${r.endYyyymmdd}",
                    style = MaterialTheme.typography.bodyMedium,
                )
                IconButton(onClick = { onRemoveAt(idx) }) {
                    Icon(Icons.Rounded.Delete, contentDescription = "Quitar rango")
                }
            }
        }
        Row(
            modifier = Modifier.fillMaxWidth(),
            horizontalArrangement = Arrangement.spacedBy(8.dp),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            OutlinedTextField(
                value = startText,
                onValueChange = { s -> startText = s.filter { it.isDigit() }.take(8) },
                label = { Text("Inicio") },
                singleLine = true,
                keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Number),
                modifier = Modifier.weight(1f),
            )
            OutlinedTextField(
                value = endText,
                onValueChange = { s -> endText = s.filter { it.isDigit() }.take(8) },
                label = { Text("Fin") },
                singleLine = true,
                keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Number),
                modifier = Modifier.weight(1f),
            )
            OutlinedButton(
                onClick = {
                    val s = startText.toLongOrNull() ?: return@OutlinedButton
                    val e = endText.toLongOrNull() ?: s
                    onAdd(DateRangeDto(s, maxOf(s, e), isExclusion))
                },
            ) {
                Icon(Icons.Rounded.Add, contentDescription = null)
                Spacer(Modifier.width(4.dp))
                Text(if (isExclusion) "Excl." else "Solo")
            }
        }
        FilterChip(
            selected = isExclusion,
            onClick = { isExclusion = !isExclusion },
            label = {
                Text(if (isExclusion) "Modo: Excluir rango" else "Modo: Permitir solo rango")
            },
        )
    }
}

@Composable
private fun WeatherLocationInputs(
    city: String,
    lat: Double,
    lon: Double,
    onApply: (String, Double, Double) -> Unit,
) {
    var cityBuf by remember(city) { mutableStateOf(city) }
    var latBuf by remember(lat) { mutableStateOf(lat.toString()) }
    var lonBuf by remember(lon) { mutableStateOf(lon.toString()) }

    LaunchedEffect(city, lat, lon) {
        if (cityBuf != city) cityBuf = city
        if (latBuf != lat.toString()) latBuf = lat.toString()
        if (lonBuf != lon.toString()) lonBuf = lon.toString()
    }

    Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
        OutlinedTextField(
            value = cityBuf,
            onValueChange = { cityBuf = it },
            label = { Text("Ciudad") },
            singleLine = true,
            modifier = Modifier.fillMaxWidth(),
        )
        Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
            OutlinedTextField(
                value = latBuf,
                onValueChange = { latBuf = it },
                label = { Text("Latitud") },
                singleLine = true,
                keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Decimal),
                modifier = Modifier.weight(1f),
            )
            OutlinedTextField(
                value = lonBuf,
                onValueChange = { lonBuf = it },
                label = { Text("Longitud") },
                singleLine = true,
                keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Decimal),
                modifier = Modifier.weight(1f),
            )
        }
        Button(
            onClick = {
                val parsedLat = latBuf.toDoubleOrNull() ?: lat
                val parsedLon = lonBuf.toDoubleOrNull() ?: lon
                onApply(cityBuf.trim().ifEmpty { city }, parsedLat, parsedLon)
            },
            modifier = Modifier.align(Alignment.End),
        ) {
            Text("Aplicar ubicación")
        }
    }
}

@Composable
private fun PermissionRow(
    title: String,
    status: String,
    granted: Boolean,
    onClick: () -> Unit,
) {
    Row(
        modifier = Modifier.fillMaxWidth(),
        horizontalArrangement = Arrangement.SpaceBetween,
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Column(modifier = Modifier.weight(1f).padding(end = 8.dp)) {
            Text(title, style = MaterialTheme.typography.bodyLarge)
            Text(
                status,
                style = MaterialTheme.typography.bodySmall,
                color = if (granted) {
                    MaterialTheme.colorScheme.primary
                } else {
                    MaterialTheme.colorScheme.onSurfaceVariant
                },
            )
        }
        OutlinedButton(onClick = onClick) {
            Text(if (granted) "Revisar" else "Abrir")
        }
    }
}

private fun formatMinute(minuteOfDay: Int): String {
    val m = minuteOfDay.coerceIn(0, 1439)
    return "%02d:%02d".format(m / 60, m % 60)
}

private fun roundTo5(value: Float): Int {
    return ((value / 5f).roundToInt() * 5).coerceIn(0, 1435)
}
