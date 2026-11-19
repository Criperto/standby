package com.criperto.standby.smarthome

import android.content.Context
import android.net.ConnectivityManager
import android.net.wifi.WifiManager
import com.criperto.standby.core.AppJson
import com.criperto.standby.core.DeviceSignalProvider
import com.criperto.standby.core.PrimaryWizStatus
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import kotlinx.serialization.Serializable
import org.json.JSONArray
import org.json.JSONObject
import uniffi.standby_core.wizDiscoverJson
import uniffi.standby_core.wizGetStateJson
import uniffi.standby_core.wizProbeJson
import uniffi.standby_core.wizScenesCatalogJson
import uniffi.standby_core.wizSetBrightnessFfi
import uniffi.standby_core.wizSetPowerFfi
import uniffi.standby_core.wizSetRgbFfi
import uniffi.standby_core.wizSetSceneFfi
import uniffi.standby_core.wizSetTempFfi
import java.net.Inet4Address
import kotlin.math.abs
import kotlin.math.roundToInt

@Serializable
data class SmartDeviceStateDto(
    val isOn: Boolean = false,
    val brightness: Int = 100,
    val r: Int? = null,
    val g: Int? = null,
    val b: Int? = null,
    val c: Int? = null,
    val w: Int? = null,
    val tempKelvin: Int? = null,
    val sceneId: Int? = null,
    val speed: Int? = null,
    val rssi: Int? = null,
    // AC / Climate fields
    val targetTempC: Int? = null,
    val currentTempC: Int? = null,
    val hvacMode: String? = null, // cool | heat | auto | fan_only | dry | off
    // Smart TV / Media Player fields
    val volumePct: Int? = null,
    val isMuted: Boolean = false,
    val tvSource: String? = null,
    val tvSources: List<String> = emptyList(),
)

@Serializable
data class SmartDeviceInfoDto(
    val id: String = "",
    val driver: String = "wiz",
    val ip: String = "",
    val port: Int = 38899,
    val mac: String = "",
    val name: String = "Lámpara WiZ",
    val moduleName: String = "",
    val roomId: Int? = null,
    val supportsRgb: Boolean = true,
    val supportsTemp: Boolean = true,
    val state: SmartDeviceStateDto = SmartDeviceStateDto(),
)

@Serializable
data class WizSceneInfoDto(
    val id: Int,
    val nameEs: String,
    val category: String,
)

data class SmartHomeUiState(
    val devices: List<WizDeviceConfig> = emptyList(),
    val statesByDeviceId: Map<String, SmartDeviceStateDto> = emptyMap(),
    val reachableByDeviceId: Map<String, Boolean> = emptyMap(),
    val scenes: List<WizSceneInfoDto> = emptyList(),
    val favorites: List<SmartSceneFavorite> = emptyList(),
    val rooms: List<String> = emptyList(),
    val homeAssistant: HomeAssistantConfig = HomeAssistantConfig(),
    val detectedHaUrl: String? = null,
    val discovering: Boolean = false,
    val statusMessage: String? = null,
    val errorMessage: String? = null,
)

class CoalescingCommandThrottler(
    private val scope: CoroutineScope,
    private val minIntervalMs: Long = 50L,
) {
    private val pendingJobs = mutableMapOf<String, Job>()
    private val lastSentAtMs = mutableMapOf<String, Long>()
    private val lock = Any()

    fun submit(key: String, block: suspend () -> Unit) {
        val now = System.currentTimeMillis()
        synchronized(lock) {
            val prevLast = lastSentAtMs[key] ?: 0L
            val elapsed = now - prevLast
            pendingJobs[key]?.cancel()
            if (elapsed >= minIntervalMs) {
                lastSentAtMs[key] = now
                pendingJobs[key] = scope.launch { block() }
            } else {
                val waitMs = (minIntervalMs - elapsed).coerceAtLeast(1L)
                pendingJobs[key] = scope.launch {
                    delay(waitMs)
                    synchronized(lock) {
                        lastSentAtMs[key] = System.currentTimeMillis()
                    }
                    block()
                }
            }
        }
    }

    fun cancelAll() {
        synchronized(lock) {
            pendingJobs.values.forEach { it.cancel() }
            pendingJobs.clear()
        }
    }
}

object WizColorMath {
    fun hsvToRgb(hueDeg: Float, sat: Float, value: Float): Triple<Int, Int, Int> {
        val h = ((hueDeg % 360f) + 360f) % 360f
        val s = sat.coerceIn(0f, 1f)
        val v = value.coerceIn(0f, 1f)
        val c = v * s
        val x = c * (1f - abs(((h / 60f) % 2f) - 1f))
        val m = v - c
        val (r1, g1, b1) = when {
            h < 60f -> Triple(c, x, 0f)
            h < 120f -> Triple(x, c, 0f)
            h < 180f -> Triple(0f, c, x)
            h < 240f -> Triple(0f, x, c)
            h < 300f -> Triple(x, 0f, c)
            else -> Triple(c, 0f, x)
        }
        return Triple(
            ((r1 + m) * 255f).roundToInt().coerceIn(0, 255),
            ((g1 + m) * 255f).roundToInt().coerceIn(0, 255),
            ((b1 + m) * 255f).roundToInt().coerceIn(0, 255),
        )
    }

    fun rgbToHex(r: Int, g: Int, b: Int): String {
        return "#%02x%02x%02x".format(r.coerceIn(0, 255), g.coerceIn(0, 255), b.coerceIn(0, 255))
    }

    fun parseHexRgb(hex: String): Triple<Int, Int, Int>? {
        val clean = hex.trim().removePrefix("#")
        if (clean.length != 6) return null
        val r = clean.substring(0, 2).toIntOrNull(16) ?: return null
        val g = clean.substring(2, 4).toIntOrNull(16) ?: return null
        val b = clean.substring(4, 6).toIntOrNull(16) ?: return null
        return Triple(r, g, b)
    }

    fun stateToHex(state: SmartDeviceStateDto): String {
        if (!state.isOn) return "#485262"

        val sid = state.sceneId
        if (sid != null && sid > 0) {
            return when (sid) {
                12, 15, 17 -> "#EEF6FF"
                11, 6, 23 -> "#FFD6A5"
                13, 27, 29 -> "#DCEBFF"
                14, 22, 5 -> "#FFB067"
                16, 10, 9 -> "#FFC68A"
                3 -> "#FF7B54"
                1 -> "#38BDF8"
                2, 30 -> "#D946EF"
                4, 32 -> "#A855F7"
                7, 31 -> "#34D399"
                8 -> "#F9A8D4"
                18 -> "#818CF8"
                else -> "#EEF6FF"
            }
        }

        val k = state.tempKelvin
        if (k != null && k > 0) {
            return when {
                k <= 2500 -> "#FFB56B"
                k <= 3200 -> "#FFD6A5"
                k <= 4300 -> "#FFF3E2"
                k <= 5300 -> "#EEF6FF"
                else -> "#DCEBFF"
            }
        }

        val r = state.r
        val g = state.g
        val b = state.b
        if (r != null && g != null && b != null && (r > 0 || g > 0 || b > 0)) {
            return rgbToHex(r, g, b)
        }
        return "#EEF6FF"
    }

    fun stateModeLabel(
        state: SmartDeviceStateDto,
        scenes: List<WizSceneInfoDto> = emptyList(),
        category: String = "light",
    ): String {
        if (!state.isOn) return "Apagado"
        when (category) {
            "ac" -> {
                val modeEs = when (state.hvacMode) {
                    "cool" -> "Frío"
                    "heat" -> "Calor"
                    "auto", "heat_cool" -> "Auto"
                    "fan_only" -> "Ventilador"
                    "dry" -> "Seco"
                    else -> "Clima"
                }
                val cur = state.currentTempC?.let { " (Amb. ${it}°C)" }.orEmpty()
                return "$modeEs$cur"
            }
            "tv" -> {
                val src = state.tvSource?.takeIf { it.isNotBlank() } ?: "Activo"
                return if (state.isMuted) "$src · Silenciado" else src
            }
            "switch" -> return "Encendido"
            "fan" -> return "Velocidad ${state.brightness}%"
        }
        val sid = state.sceneId
        if (sid != null && sid > 0) {
            return scenes.firstOrNull { it.id == sid }?.nameEs ?: when (sid) {
                11 -> "Luz cálida"
                12 -> "Luz diurna"
                13 -> "Luz fría"
                14 -> "Luz nocturna"
                3 -> "Atardecer"
                6 -> "Acogedor"
                30 -> "Ciberpunk"
                else -> "Escena #$sid"
            }
        }
        val k = state.tempKelvin
        if (k != null && k > 0) {
            return if (k in 4800..5500) "Luz día ${k}K" else "${k}K"
        }
        if (state.r != null && state.g != null && state.b != null) {
            return "RGB"
        }
        return "Luz diurna"
    }
}

class SmartHomeViewModel(
    private val context: Context,
    private val repository: SmartHomeRepository,
    private val signalProvider: DeviceSignalProvider,
) {
    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.Main.immediate)
    private val throttler = CoalescingCommandThrottler(
        scope = CoroutineScope(SupervisorJob() + Dispatchers.IO),
        minIntervalMs = 50L,
    )

    private val _uiState = MutableStateFlow(SmartHomeUiState(scenes = loadScenesCatalog()))
    val uiState: StateFlow<SmartHomeUiState> = _uiState.asStateFlow()

    companion object {
        val PINNED_ALL_OFF = SmartSceneFavorite(
            id = "builtin_all_off",
            name = "Apagar todo",
            actionType = "off",
        )
    }

    init {
        scope.launch {
            repository.storeFlow.collect { store ->
                _uiState.value = _uiState.value.copy(
                    devices = store.devices,
                    favorites = store.favorites.filterNot { it.actionType == "off" && it.deviceId.isBlank() },
                    rooms = store.rooms,
                    homeAssistant = store.homeAssistant,
                )
                syncPrimaryDeviceSignal()
            }
        }
        scope.launch {
            var firstSync = true
            while (true) {
                if (firstSync || signalProvider.hasActiveConsumers()) {
                    firstSync = false
                    refreshAllStates()
                    delay(8_000L)
                } else {
                    delay(15_000L)
                }
            }
        }
    }

    private fun loadScenesCatalog(): List<WizSceneInfoDto> {
        return runCatching {
            AppJson.decodeFromString<List<WizSceneInfoDto>>(wizScenesCatalogJson())
        }.getOrElse { emptyList() }
    }

    fun refreshAllStates() {
        val list = _uiState.value.devices
        val ha = _uiState.value.homeAssistant
        scope.launch {
            if (ha.enabled && ha.serverUrl.isNotBlank()) {
                syncHomeAssistantEntities(ha.serverUrl, ha.accessToken, silent = true)
            }
            for (dev in list.filter { it.driver != "home_assistant" }) {
                refreshSingleDevice(dev)
            }
            syncPrimaryDeviceSignal()
        }
    }

    private suspend fun refreshSingleDevice(dev: WizDeviceConfig) {
        val state = withContext(Dispatchers.IO) {
            when (dev.driver) {
                "wled" -> MultiBrandDrivers.probeWled(dev.ip).getOrNull()?.second
                "shelly", "tasmota" -> MultiBrandDrivers.probeShellyOrTasmota(
                    dev.ip, dev.driver, dev.name, dev.category
                ).getOrNull()?.second
                "yeelight" -> MultiBrandDrivers.probeYeelight(
                    dev.ip, dev.name, dev.room
                ).getOrNull()?.second
                "wiz" -> runCatching {
                    AppJson.decodeFromString<SmartDeviceStateDto>(
                        wizGetStateJson(dev.ip, dev.port.toUShort())
                    )
                }.getOrNull()
                else -> _uiState.value.statesByDeviceId[dev.id]
            }
        }
        val nextStates = _uiState.value.statesByDeviceId.toMutableMap()
        val nextReach = _uiState.value.reachableByDeviceId.toMutableMap()
        if (state != null) {
            nextStates[dev.id] = state
            nextReach[dev.id] = true
        } else if (dev.driver in listOf("wiz", "wled", "shelly", "tasmota", "yeelight")) {
            nextReach[dev.id] = false
        }
        _uiState.value = _uiState.value.copy(
            statesByDeviceId = nextStates,
            reachableByDeviceId = nextReach,
        )
    }

    fun discoverDevices(sweepSubnet: Boolean) {
        if (_uiState.value.discovering) return
        scope.launch {
            _uiState.value = _uiState.value.copy(
                discovering = true,
                statusMessage = "Buscando dispositivos WiZ, WLED, Shelly, Tasmota, Yeelight y Home Assistant...",
                errorMessage = null,
            )
            val prefix = extractWifiSubnetPrefix()

            val result = withContext(Dispatchers.IO) {
                val wifi = context.applicationContext.getSystemService(Context.WIFI_SERVICE) as? WifiManager
                val lock = wifi?.createMulticastLock("standby:wiz_discover")?.apply {
                    setReferenceCounted(false)
                    runCatching { acquire() }
                }
                try {
                    val wizList = runCatching {
                        val raw = wizDiscoverJson(
                            subnetPrefix = prefix,
                            timeoutMs = if (sweepSubnet) 1200u else 900u,
                            sweepSubnet = sweepSubnet,
                        )
                        AppJson.decodeFromString<List<SmartDeviceInfoDto>>(raw)
                    }.getOrDefault(emptyList())

                    val yeelightList = MultiBrandDrivers.discoverYeelightSsdp()
                    val httpList = if (sweepSubnet) {
                        MultiBrandDrivers.discoverHttpSmartDevicesOnSubnet(prefix)
                    } else {
                        emptyList()
                    }
                    val detectedHa = MultiBrandDrivers.discoverHomeAssistantUrl(prefix)
                    Triple(wizList, yeelightList + httpList, detectedHa)
                } finally {
                    runCatching {
                        if (lock?.isHeld == true) lock.release()
                    }
                }
            }

            val (foundWiz, foundOthers, detectedHa) = result
            val nextStates = _uiState.value.statesByDeviceId.toMutableMap()
            val nextReach = _uiState.value.reachableByDeviceId.toMutableMap()
            for (item in foundWiz) {
                val existing = _uiState.value.devices.firstOrNull {
                    (it.mac.isNotBlank() && it.mac == item.mac) || it.ip == item.ip
                }
                val cfg = WizDeviceConfig(
                    id = existing?.id ?: item.id.ifBlank { item.ip },
                    name = existing?.name ?: item.name.ifBlank { "WiZ ${item.ip}" },
                    ip = item.ip,
                    port = item.port,
                    mac = item.mac,
                    moduleName = item.moduleName,
                    room = existing?.room ?: "Escritorio",
                    supportsRgb = item.supportsRgb,
                    supportsTemp = item.supportsTemp,
                    isFavorite = existing?.isFavorite ?: true,
                    driver = "wiz",
                    category = "light",
                )
                repository.upsertDevice(cfg)
                nextStates[cfg.id] = item.state
                nextReach[cfg.id] = true
            }
            for ((otherCfg, otherSt) in foundOthers) {
                val existing = _uiState.value.devices.firstOrNull {
                    it.id == otherCfg.id || it.ip == otherCfg.ip
                }
                val merged = if (existing != null) {
                    otherCfg.copy(
                        id = existing.id,
                        name = existing.name,
                        room = existing.room,
                        isFavorite = existing.isFavorite,
                    )
                } else {
                    otherCfg
                }
                repository.upsertDevice(merged)
                nextStates[merged.id] = otherSt
                nextReach[merged.id] = true
            }

            val totalFound = foundWiz.size + foundOthers.size
            val haNote = if (detectedHa != null) " · Home Assistant detectado en $detectedHa" else ""
            _uiState.value = _uiState.value.copy(
                discovering = false,
                detectedHaUrl = detectedHa ?: _uiState.value.detectedHaUrl,
                statesByDeviceId = nextStates,
                reachableByDeviceId = nextReach,
                statusMessage = "$totalFound dispositivo(s) encontrados en red local$haNote",
            )
            syncPrimaryDeviceSignal()
        }
    }

    fun connectHomeAssistant(serverUrl: String, accessToken: String) {
        val cleanUrl = serverUrl.trim().trimEnd('/')
        if (cleanUrl.isBlank()) return
        scope.launch {
            _uiState.value = _uiState.value.copy(
                discovering = true,
                statusMessage = "Conectando con Home Assistant en $cleanUrl...",
                errorMessage = null,
            )
            val cfg = HomeAssistantConfig(
                enabled = true,
                serverUrl = cleanUrl,
                accessToken = accessToken.trim(),
            )
            repository.updateHomeAssistant(cfg)
            syncHomeAssistantEntities(cleanUrl, accessToken.trim(), silent = false)
        }
    }

    private suspend fun syncHomeAssistantEntities(
        serverUrl: String,
        token: String,
        silent: Boolean,
    ) {
        val res = withContext(Dispatchers.IO) {
            MultiBrandDrivers.fetchHomeAssistantEntities(serverUrl, token)
        }
        res.onSuccess { pairs ->
            val nextStates = _uiState.value.statesByDeviceId.toMutableMap()
            val nextReach = _uiState.value.reachableByDeviceId.toMutableMap()
            for ((cfg, st) in pairs) {
                repository.upsertDevice(cfg)
                nextStates[cfg.id] = st
                nextReach[cfg.id] = true
            }
            _uiState.value = _uiState.value.copy(
                discovering = false,
                statesByDeviceId = nextStates,
                reachableByDeviceId = nextReach,
                statusMessage = if (silent) {
                    _uiState.value.statusMessage
                } else {
                    "Home Assistant sincronizado: ${pairs.size} dispositivos (luces, TVs, aires, switches)"
                },
            )
            syncPrimaryDeviceSignal()
        }.onFailure { err ->
            if (!silent) {
                _uiState.value = _uiState.value.copy(
                    discovering = false,
                    errorMessage = err.message ?: "Error al sincronizar Home Assistant",
                )
            }
        }
    }

    fun probeAndAddDevice(
        ip: String,
        customName: String?,
        room: String,
        driver: String = "wiz",
        category: String = "light",
    ) {
        val cleanIp = ip.trim()
        if (cleanIp.isBlank()) return
        scope.launch {
            _uiState.value = _uiState.value.copy(
                discovering = true,
                errorMessage = null,
                statusMessage = "Conectando con $driver ($cleanIp)...",
            )
            val result = withContext(Dispatchers.IO) {
                runCatching {
                    when (driver) {
                        "wled" -> MultiBrandDrivers.probeWled(cleanIp).getOrThrow()
                        "shelly", "tasmota" -> MultiBrandDrivers.probeShellyOrTasmota(
                            cleanIp, driver, customName, category
                        ).getOrThrow()
                        "yeelight" -> {
                            MultiBrandDrivers.sendYeelightCommand(cleanIp, "set_power", """["on","smooth",200]""")
                            val cfg = WizDeviceConfig(
                                id = "yeelight_$cleanIp",
                                name = customName?.takeIf { it.isNotBlank() } ?: "Yeelight $cleanIp",
                                ip = cleanIp,
                                port = 55443,
                                room = room.ifBlank { "Escritorio" },
                                driver = "yeelight",
                                category = "light",
                            )
                            cfg to SmartDeviceStateDto(isOn = true, brightness = 100)
                        }
                        else -> {
                            val info = AppJson.decodeFromString<SmartDeviceInfoDto>(wizProbeJson(cleanIp, 38899u))
                            val cfg = WizDeviceConfig(
                                id = info.id.ifBlank { cleanIp },
                                name = customName?.trim()?.takeIf { it.isNotEmpty() } ?: info.name,
                                ip = info.ip,
                                port = info.port,
                                mac = info.mac,
                                moduleName = info.moduleName,
                                room = room.trim().ifEmpty { "Escritorio" },
                                supportsRgb = info.supportsRgb,
                                supportsTemp = info.supportsTemp,
                                isFavorite = true,
                                driver = "wiz",
                                category = category,
                            )
                            cfg to info.state
                        }
                    }
                }
            }
            result.onSuccess { (cfg, state) ->
                val finalCfg = cfg.copy(
                    name = customName?.trim()?.takeIf { it.isNotEmpty() } ?: cfg.name,
                    room = room.trim().ifEmpty { cfg.room },
                    category = category,
                )
                repository.upsertDevice(finalCfg)
                val nextStates = _uiState.value.statesByDeviceId.toMutableMap()
                val nextReach = _uiState.value.reachableByDeviceId.toMutableMap()
                nextStates[finalCfg.id] = state
                nextReach[finalCfg.id] = true
                _uiState.value = _uiState.value.copy(
                    discovering = false,
                    statesByDeviceId = nextStates,
                    reachableByDeviceId = nextReach,
                    statusMessage = "Agregado: ${finalCfg.name} (${finalCfg.driver.uppercase()})",
                )
                syncPrimaryDeviceSignal()
            }.onFailure { err ->
                _uiState.value = _uiState.value.copy(
                    discovering = false,
                    errorMessage = err.message ?: "No hubo respuesta de $cleanIp",
                )
            }
        }
    }

    fun probeAndAddIp(ip: String, customName: String?, room: String) {
        probeAndAddDevice(ip, customName, room, driver = "wiz", category = "light")
    }

    fun renameDevice(deviceId: String, newName: String, newRoom: String) {
        val dev = _uiState.value.devices.firstOrNull { it.id == deviceId } ?: return
        scope.launch {
            repository.upsertDevice(
                dev.copy(
                    name = newName.trim().ifEmpty { dev.name },
                    room = newRoom.trim().ifEmpty { dev.room },
                )
            )
        }
    }

    fun deleteDevice(deviceId: String) {
        scope.launch {
            repository.removeDevice(deviceId)
        }
    }

    fun addFavorite(fav: SmartSceneFavorite) {
        scope.launch {
            repository.addFavorite(fav)
            _uiState.value = _uiState.value.copy(statusMessage = "Favorito '${fav.name}' guardado")
        }
    }

    fun removeFavorite(favId: String) {
        scope.launch { repository.removeFavorite(favId) }
    }

    fun setPower(deviceId: String, on: Boolean) {
        val dev = _uiState.value.devices.firstOrNull { it.id == deviceId } ?: return
        updateOptimisticState(deviceId) {
            it.copy(
                isOn = on,
                hvacMode = if (dev.category == "ac") (if (on) "cool" else "off") else it.hvacMode,
            )
        }
        throttler.submit("power:$deviceId") {
            runCatching {
                when (dev.driver) {
                    "home_assistant" -> {
                        val ha = _uiState.value.homeAssistant
                        val domain = dev.entityId.substringBefore('.', "light")
                        val service = if (on) "turn_on" else "turn_off"
                        val payload = JSONObject().put("entity_id", dev.entityId)
                        MultiBrandDrivers.callHomeAssistantService(
                            ha.serverUrl.ifBlank { dev.ip }, ha.accessToken, domain, service, payload
                        ).getOrThrow()
                    }
                    "wled" -> MultiBrandDrivers.sendWledState(
                        dev.ip, JSONObject().put("on", on)
                    ).getOrThrow()
                    "shelly" -> MultiBrandDrivers.setShellyPower(dev.ip, on).getOrThrow()
                    "tasmota" -> MultiBrandDrivers.setTasmotaPower(dev.ip, on).getOrThrow()
                    "yeelight" -> MultiBrandDrivers.sendYeelightCommand(
                        dev.ip, "set_power", """["${if (on) "on" else "off"}","smooth",200]"""
                    ).getOrThrow()
                    else -> wizSetPowerFfi(dev.ip, dev.port.toUShort(), on)
                }
            }
                .onSuccess { schedulePostCommandSync(dev) }
                .onFailure { reportCmdError(deviceId, it) }
        }
    }

    /**
     * Universal primary slider value:
     * - For Lights / Fans: Brightness / Speed 10%..100%
     * - For Air Conditioners (ac): Target Temperature 16°C..30°C
     * - For Smart TVs (tv): Volume 0%..100%
     */
    fun setBrightness(deviceId: String, valuePct: Int) {
        val dev = _uiState.value.devices.firstOrNull { it.id == deviceId } ?: return
        when (dev.category) {
            "ac" -> {
                val tempC = (16 + ((valuePct.coerceIn(0, 100) / 100f) * 14f)).roundToInt().coerceIn(16, 30)
                setAcTemperature(deviceId, tempC)
                return
            }
            "tv" -> {
                val vol = valuePct.coerceIn(0, 100)
                setTvVolume(deviceId, vol)
                return
            }
        }

        val clamped = valuePct.coerceIn(10, 100)
        updateOptimisticState(deviceId) { it.copy(isOn = true, brightness = clamped) }
        throttler.submit("dim:$deviceId") {
            runCatching {
                when (dev.driver) {
                    "home_assistant" -> {
                        val ha = _uiState.value.homeAssistant
                        val domain = dev.entityId.substringBefore('.', "light")
                        val payload = JSONObject().put("entity_id", dev.entityId)
                        if (domain == "fan") {
                            payload.put("percentage", clamped)
                            MultiBrandDrivers.callHomeAssistantService(
                                ha.serverUrl.ifBlank { dev.ip }, ha.accessToken, "fan", "set_percentage", payload
                            ).getOrThrow()
                        } else {
                            payload.put("brightness_pct", clamped)
                            MultiBrandDrivers.callHomeAssistantService(
                                ha.serverUrl.ifBlank { dev.ip }, ha.accessToken, "light", "turn_on", payload
                            ).getOrThrow()
                        }
                    }
                    "wled" -> {
                        val bri255 = ((clamped / 100f) * 255f).roundToInt().coerceIn(5, 255)
                        MultiBrandDrivers.sendWledState(
                            dev.ip, JSONObject().put("on", true).put("bri", bri255)
                        ).getOrThrow()
                    }
                    "yeelight" -> MultiBrandDrivers.sendYeelightCommand(
                        dev.ip, "set_bright", """[$clamped,"smooth",150]"""
                    ).getOrThrow()
                    else -> wizSetBrightnessFfi(dev.ip, dev.port.toUShort(), clamped.toUByte())
                }
            }
                .onSuccess { schedulePostCommandSync(dev) }
                .onFailure { reportCmdError(deviceId, it) }
        }
    }

    fun setAcTemperature(deviceId: String, tempC: Int, hvacMode: String? = null) {
        val dev = _uiState.value.devices.firstOrNull { it.id == deviceId } ?: return
        val clamped = tempC.coerceIn(16, 30)
        updateOptimisticState(deviceId) {
            it.copy(
                isOn = hvacMode != "off",
                targetTempC = clamped,
                brightness = clamped,
                hvacMode = hvacMode ?: it.hvacMode ?: "cool",
            )
        }
        throttler.submit("ac:$deviceId") {
            if (dev.driver == "home_assistant") {
                val ha = _uiState.value.homeAssistant
                if (hvacMode != null) {
                    MultiBrandDrivers.callHomeAssistantService(
                        ha.serverUrl.ifBlank { dev.ip },
                        ha.accessToken,
                        "climate",
                        "set_hvac_mode",
                        JSONObject().put("entity_id", dev.entityId).put("hvac_mode", hvacMode),
                    )
                }
                MultiBrandDrivers.callHomeAssistantService(
                    ha.serverUrl.ifBlank { dev.ip },
                    ha.accessToken,
                    "climate",
                    "set_temperature",
                    JSONObject().put("entity_id", dev.entityId).put("temperature", clamped),
                )
            }
        }
    }

    fun setTvVolume(deviceId: String, volumePct: Int, toggleMute: Boolean = false) {
        val dev = _uiState.value.devices.firstOrNull { it.id == deviceId } ?: return
        val clamped = volumePct.coerceIn(0, 100)
        val cur = _uiState.value.statesByDeviceId[deviceId] ?: SmartDeviceStateDto()
        val nextMuted = if (toggleMute) !cur.isMuted else cur.isMuted
        updateOptimisticState(deviceId) {
            it.copy(
                isOn = true,
                volumePct = clamped,
                brightness = clamped.coerceIn(10, 100),
                isMuted = nextMuted,
            )
        }
        throttler.submit("tv:$deviceId") {
            if (dev.driver == "home_assistant") {
                val ha = _uiState.value.homeAssistant
                if (toggleMute) {
                    MultiBrandDrivers.callHomeAssistantService(
                        ha.serverUrl.ifBlank { dev.ip },
                        ha.accessToken,
                        "media_player",
                        "volume_mute",
                        JSONObject().put("entity_id", dev.entityId).put("is_volume_muted", nextMuted),
                    )
                } else {
                    MultiBrandDrivers.callHomeAssistantService(
                        ha.serverUrl.ifBlank { dev.ip },
                        ha.accessToken,
                        "media_player",
                        "volume_set",
                        JSONObject().put("entity_id", dev.entityId).put("volume_level", clamped / 100.0),
                    )
                }
            }
        }
    }

    fun triggerTvMediaPlayPause(deviceId: String) {
        val dev = _uiState.value.devices.firstOrNull { it.id == deviceId } ?: return
        if (dev.driver == "home_assistant") {
            val ha = _uiState.value.homeAssistant
            throttler.submit("tv_play:$deviceId") {
                MultiBrandDrivers.callHomeAssistantService(
                    ha.serverUrl.ifBlank { dev.ip },
                    ha.accessToken,
                    "media_player",
                    "media_play_pause",
                    JSONObject().put("entity_id", dev.entityId),
                )
            }
        }
    }

    fun setRgb(deviceId: String, r: Int, g: Int, b: Int, dimming: Int? = null) {
        val dev = _uiState.value.devices.firstOrNull { it.id == deviceId } ?: return
        val rr = r.coerceIn(0, 255)
        val gg = g.coerceIn(0, 255)
        val bb = b.coerceIn(0, 255)
        val dim = dimming?.coerceIn(10, 100)
        updateOptimisticState(deviceId) {
            it.copy(
                isOn = true,
                r = rr,
                g = gg,
                b = bb,
                tempKelvin = null,
                sceneId = null,
                brightness = dim ?: it.brightness,
            )
        }
        throttler.submit("rgb:$deviceId") {
            runCatching {
                when (dev.driver) {
                    "home_assistant" -> {
                        val ha = _uiState.value.homeAssistant
                        val payload = JSONObject()
                            .put("entity_id", dev.entityId)
                            .put("rgb_color", JSONArray().put(rr).put(gg).put(bb))
                        if (dim != null) payload.put("brightness_pct", dim)
                        MultiBrandDrivers.callHomeAssistantService(
                            ha.serverUrl.ifBlank { dev.ip }, ha.accessToken, "light", "turn_on", payload
                        ).getOrThrow()
                    }
                    "wled" -> {
                        val seg = JSONArray().put(
                            JSONObject().put("col", JSONArray().put(JSONArray().put(rr).put(gg).put(bb)))
                        )
                        MultiBrandDrivers.sendWledState(
                            dev.ip, JSONObject().put("on", true).put("seg", seg)
                        ).getOrThrow()
                    }
                    "yeelight" -> {
                        val rgbInt = (rr shl 16) or (gg shl 8) or bb
                        MultiBrandDrivers.sendYeelightCommand(
                            dev.ip, "set_rgb", """[$rgbInt,"smooth",150]"""
                        ).getOrThrow()
                    }
                    else -> wizSetRgbFfi(
                        dev.ip,
                        dev.port.toUShort(),
                        rr.toUByte(),
                        gg.toUByte(),
                        bb.toUByte(),
                        dim?.toUByte(),
                    )
                }
            }
                .onSuccess { schedulePostCommandSync(dev) }
                .onFailure { reportCmdError(deviceId, it) }
        }
    }

    fun setTempKelvin(deviceId: String, kelvin: Int, dimming: Int? = null) {
        val dev = _uiState.value.devices.firstOrNull { it.id == deviceId } ?: return
        val k = kelvin.coerceIn(2200, 6500)
        val dim = dimming?.coerceIn(10, 100)
        updateOptimisticState(deviceId) {
            it.copy(
                isOn = true,
                tempKelvin = k,
                r = null,
                g = null,
                b = null,
                sceneId = null,
                brightness = dim ?: it.brightness,
            )
        }
        throttler.submit("temp:$deviceId") {
            runCatching {
                when (dev.driver) {
                    "home_assistant" -> {
                        val ha = _uiState.value.homeAssistant
                        val payload = JSONObject()
                            .put("entity_id", dev.entityId)
                            .put("color_temp_kelvin", k)
                        if (dim != null) payload.put("brightness_pct", dim)
                        MultiBrandDrivers.callHomeAssistantService(
                            ha.serverUrl.ifBlank { dev.ip }, ha.accessToken, "light", "turn_on", payload
                        ).getOrThrow()
                    }
                    "yeelight" -> MultiBrandDrivers.sendYeelightCommand(
                        dev.ip, "set_ct_abx", """[$k,"smooth",150]"""
                    ).getOrThrow()
                    else -> wizSetTempFfi(dev.ip, dev.port.toUShort(), k.toUShort(), dim?.toUByte())
                }
            }
                .onSuccess { schedulePostCommandSync(dev) }
                .onFailure { reportCmdError(deviceId, it) }
        }
    }

    fun setScene(deviceId: String, sceneId: Int, speed: Int? = null, dimming: Int? = null) {
        val dev = _uiState.value.devices.firstOrNull { it.id == deviceId } ?: return
        val spd = speed?.coerceIn(10, 200)
        val dim = dimming?.coerceIn(10, 100)
        updateOptimisticState(deviceId) {
            it.copy(
                isOn = true,
                sceneId = sceneId,
                tempKelvin = null,
                r = null,
                g = null,
                b = null,
                speed = spd ?: it.speed,
                brightness = dim ?: it.brightness,
            )
        }
        throttler.submit("scene:$deviceId") {
            runCatching {
                wizSetSceneFfi(
                    dev.ip,
                    dev.port.toUShort(),
                    sceneId.toUShort(),
                    spd?.toUByte(),
                    dim?.toUByte(),
                )
            }
                .onSuccess { schedulePostCommandSync(dev) }
                .onFailure { reportCmdError(deviceId, it) }
        }
    }

    private fun schedulePostCommandSync(dev: WizDeviceConfig) {
        scope.launch {
            delay(450L)
            refreshSingleDevice(dev)
            syncPrimaryDeviceSignal()
        }
    }

    fun applyFavorite(fav: SmartSceneFavorite) {
        val targets = if (fav.deviceId.isNotBlank()) {
            _uiState.value.devices.filter { it.id == fav.deviceId }
        } else {
            _uiState.value.devices
        }
        for (dev in targets) {
            when (fav.actionType) {
                "off" -> setPower(dev.id, false)
                "on" -> setPower(dev.id, true)
                "rgb" -> setRgb(dev.id, fav.r, fav.g, fav.b, fav.brightness)
                "temp" -> setTempKelvin(dev.id, fav.kelvin, fav.brightness)
                else -> setScene(dev.id, fav.sceneId, fav.speed, fav.brightness)
            }
        }
    }

    fun executeSceneWizAction(command: String, deviceId: String, arg: String?) {
        val all = _uiState.value.devices
        if (all.isEmpty()) return
        val targets = when {
            deviceId == "all" -> all
            deviceId == "primary" || deviceId.isBlank() -> listOf(all.first())
            else -> all.filter {
                it.id == deviceId || it.name == deviceId || it.ip == deviceId || it.entityId == deviceId
            }.ifEmpty { listOf(all.first()) }
        }
        val cmd = command.trim().lowercase()
        for (target in targets) {
            val current = _uiState.value.statesByDeviceId[target.id] ?: SmartDeviceStateDto()
            when (cmd) {
                "toggle" -> setPower(target.id, !current.isOn)
                "on" -> setPower(target.id, true)
                "off" -> setPower(target.id, false)
                "brightness", "dim" -> {
                    val lvl = arg?.toIntOrNull() ?: 80
                    setBrightness(target.id, lvl)
                }
                "rgb", "color" -> {
                    val raw = arg?.trim().orEmpty()
                    val parsed = WizColorMath.parseHexRgb(raw) ?: run {
                        val parts = raw.split(',', ';', ' ').mapNotNull { it.trim().toIntOrNull() }
                        if (parts.size >= 3) Triple(parts[0], parts[1], parts[2]) else null
                    }
                    if (parsed != null) {
                        setRgb(target.id, parsed.first, parsed.second, parsed.third)
                    }
                }
                "temp", "kelvin" -> {
                    val k = arg?.toIntOrNull() ?: 3200
                    setTempKelvin(target.id, k)
                }
                "scene" -> {
                    val parts = arg.orEmpty().split(',', ':').mapNotNull { it.trim().toIntOrNull() }
                    val sId = parts.firstOrNull() ?: 12
                    val spd = parts.getOrNull(1)
                    setScene(target.id, sId, spd)
                }
            }
        }
    }

    private fun updateOptimisticState(
        deviceId: String,
        transform: (SmartDeviceStateDto) -> SmartDeviceStateDto,
    ) {
        val cur = _uiState.value.statesByDeviceId[deviceId] ?: SmartDeviceStateDto()
        val next = transform(cur)
        val map = _uiState.value.statesByDeviceId.toMutableMap()
        map[deviceId] = next
        val reach = _uiState.value.reachableByDeviceId.toMutableMap()
        reach[deviceId] = true
        _uiState.value = _uiState.value.copy(
            statesByDeviceId = map,
            reachableByDeviceId = reach,
        )
        syncPrimaryDeviceSignal()
    }

    private fun reportCmdError(deviceId: String, err: Throwable) {
        val reach = _uiState.value.reachableByDeviceId.toMutableMap()
        reach[deviceId] = false
        _uiState.value = _uiState.value.copy(
            reachableByDeviceId = reach,
            errorMessage = err.message ?: "Error comunicando con dispositivo",
        )
    }

    private fun toStatusForSignal(dev: WizDeviceConfig, state: SmartDeviceStateDto): PrimaryWizStatus {
        val valLabel = if (!state.isOn) {
            "OFF"
        } else {
            when (dev.category) {
                "ac" -> "${state.targetTempC ?: state.brightness}°C"
                "tv" -> if (state.isMuted) "MUTE" else "%${state.volumePct ?: state.brightness}"
                "switch" -> "ON"
                else -> "%${state.brightness}"
            }
        }
        val modeLabel = when (dev.category) {
            "ac" -> state.hvacMode ?: "Clima"
            "tv" -> state.tvSource ?: "Smart TV"
            "switch" -> if (state.isOn) "Activo" else "Apagado"
            else -> WizColorMath.stateModeLabel(state)
        }
        val sliderDim = when (dev.category) {
            "ac" -> {
                val t = (state.targetTempC ?: state.brightness).coerceIn(16, 30)
                (((t - 16) / 14f) * 100f).roundToInt().coerceIn(10, 100)
            }
            "tv" -> (state.volumePct ?: state.brightness).coerceIn(10, 100)
            "switch" -> if (state.isOn) 100 else 10
            else -> state.brightness.coerceIn(10, 100)
        }
        return PrimaryWizStatus(
            isOn = state.isOn,
            dimming = sliderDim,
            name = dev.name,
            hex = WizColorMath.stateToHex(state),
            room = dev.room,
            category = dev.category,
            modeLabel = modeLabel,
            valueLabel = valLabel,
        )
    }

    private fun syncPrimaryDeviceSignal() {
        val allDevices = _uiState.value.devices
        val primary = allDevices.firstOrNull { it.isFavorite } ?: allDevices.firstOrNull()
        if (primary == null) {
            signalProvider.updateWizStatus(PrimaryWizStatus(), emptyMap())
            return
        }
        val byId = mutableMapOf<String, PrimaryWizStatus>()
        for (dev in allDevices) {
            val st = _uiState.value.statesByDeviceId[dev.id] ?: SmartDeviceStateDto()
            val status = toStatusForSignal(dev, st)
            byId[dev.id] = status
            byId[dev.name] = status
            if (dev.ip.isNotBlank()) byId[dev.ip] = status
            if (dev.entityId.isNotBlank()) byId[dev.entityId] = status
        }
        val primaryStatus = byId[primary.id] ?: toStatusForSignal(primary, SmartDeviceStateDto())
        byId["primary"] = primaryStatus
        signalProvider.updateWizStatus(primaryStatus, byId)
    }

    private fun extractWifiSubnetPrefix(): String? {
        return runCatching {
            val cm = context.getSystemService(Context.CONNECTIVITY_SERVICE) as? ConnectivityManager
                ?: return@runCatching null
            val net = cm.activeNetwork ?: return@runCatching null
            val lp = cm.getLinkProperties(net) ?: return@runCatching null
            val v4 = lp.linkAddresses
                .mapNotNull { it.address as? Inet4Address }
                .firstOrNull { !it.isLoopbackAddress && it.isSiteLocalAddress }
                ?: return@runCatching null
            val parts = v4.hostAddress?.split('.') ?: return@runCatching null
            if (parts.size == 4) "${parts[0]}.${parts[1]}.${parts[2]}" else null
        }.getOrNull()
    }
}
