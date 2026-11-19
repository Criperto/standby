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
    val discovering: Boolean = false,
    val statusMessage: String? = null,
    val errorMessage: String? = null,
)

/**
 * Pure Kotlin coalescing throttler (50ms window) so rapid slider / color-wheel drags
 * execute immediately once and coalesce trailing updates without flooding bulbs.
 */
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
        if (!state.isOn) return "#555555"
        val r = state.r
        val g = state.g
        val b = state.b
        if (r != null && g != null && b != null) {
            return rgbToHex(r, g, b)
        }
        val k = state.tempKelvin ?: 3500
        return when {
            k <= 3000 -> "#FFD29E"
            k >= 5200 -> "#DCEBFF"
            else -> "#FFF1DF"
        }
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

    init {
        scope.launch {
            repository.storeFlow.collect { store ->
                _uiState.value = _uiState.value.copy(
                    devices = store.devices,
                    favorites = store.favorites,
                    rooms = store.rooms,
                )
                syncPrimaryDeviceSignal()
            }
        }
        refreshAllStates()
    }

    private fun loadScenesCatalog(): List<WizSceneInfoDto> {
        return runCatching {
            AppJson.decodeFromString<List<WizSceneInfoDto>>(wizScenesCatalogJson())
        }.getOrElse { emptyList() }
    }

    fun clearMessages() {
        _uiState.value = _uiState.value.copy(statusMessage = null, errorMessage = null)
    }

    fun refreshAllStates() {
        val list = _uiState.value.devices
        if (list.isEmpty()) return
        scope.launch {
            for (dev in list) {
                refreshSingleDevice(dev)
            }
            syncPrimaryDeviceSignal()
        }
    }

    private suspend fun refreshSingleDevice(dev: WizDeviceConfig) {
        val state = withContext(Dispatchers.IO) {
            runCatching {
                AppJson.decodeFromString<SmartDeviceStateDto>(
                    wizGetStateJson(dev.ip, dev.port.toUShort())
                )
            }.getOrNull()
        }
        val nextStates = _uiState.value.statesByDeviceId.toMutableMap()
        val nextReach = _uiState.value.reachableByDeviceId.toMutableMap()
        if (state != null) {
            nextStates[dev.id] = state
            nextReach[dev.id] = true
        } else {
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
                statusMessage = if (sweepSubnet) "Escaneando red local..." else "Buscando por broadcast WiZ...",
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
                    runCatching {
                        val raw = wizDiscoverJson(
                            subnetPrefix = prefix,
                            timeoutMs = if (sweepSubnet) 1200u else 900u,
                            sweepSubnet = sweepSubnet,
                        )
                        AppJson.decodeFromString<List<SmartDeviceInfoDto>>(raw)
                    }
                } finally {
                    runCatching {
                        if (lock?.isHeld == true) lock.release()
                    }
                }
            }

            result.onSuccess { found ->
                val nextStates = _uiState.value.statesByDeviceId.toMutableMap()
                val nextReach = _uiState.value.reachableByDeviceId.toMutableMap()
                for (item in found) {
                    val existing = _uiState.value.devices.firstOrNull {
                        it.mac.isNotBlank() && it.mac == item.mac || it.ip == item.ip
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
                    )
                    repository.upsertDevice(cfg)
                    nextStates[cfg.id] = item.state
                    nextReach[cfg.id] = true
                }
                _uiState.value = _uiState.value.copy(
                    discovering = false,
                    statesByDeviceId = nextStates,
                    reachableByDeviceId = nextReach,
                    statusMessage = if (found.isEmpty()) {
                        if (!sweepSubnet) {
                            "No respondió por broadcast. Probá activar 'Barrido /24' o añadir por IP."
                        } else {
                            "No se encontraron lámparas WiZ en ${prefix ?: "la red"}. Verificá Wi-Fi."
                        }
                    } else {
                        "${found.size} lámpara(s) WiZ encontrada(s)"
                    },
                )
                syncPrimaryDeviceSignal()
            }.onFailure { err ->
                _uiState.value = _uiState.value.copy(
                    discovering = false,
                    errorMessage = err.message ?: "Error al buscar lámparas WiZ",
                )
            }
        }
    }

    fun probeAndAddIp(ip: String, customName: String?, room: String) {
        val cleanIp = ip.trim()
        if (cleanIp.isBlank()) return
        scope.launch {
            _uiState.value = _uiState.value.copy(
                discovering = true,
                errorMessage = null,
                statusMessage = "Probando $cleanIp:38899...",
            )
            val result = withContext(Dispatchers.IO) {
                runCatching {
                    AppJson.decodeFromString<SmartDeviceInfoDto>(wizProbeJson(cleanIp, 38899u))
                }
            }
            result.onSuccess { info ->
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
                )
                repository.upsertDevice(cfg)
                val nextStates = _uiState.value.statesByDeviceId.toMutableMap()
                val nextReach = _uiState.value.reachableByDeviceId.toMutableMap()
                nextStates[cfg.id] = info.state
                nextReach[cfg.id] = true
                _uiState.value = _uiState.value.copy(
                    discovering = false,
                    statesByDeviceId = nextStates,
                    reachableByDeviceId = nextReach,
                    statusMessage = "Agregada: ${cfg.name} (${cfg.ip})",
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
        updateOptimisticState(deviceId) { it.copy(isOn = on) }
        throttler.submit("power:$deviceId") {
            runCatching { wizSetPowerFfi(dev.ip, dev.port.toUShort(), on) }
                .onFailure { reportCmdError(deviceId, it) }
        }
    }

    fun setBrightness(deviceId: String, dimming: Int) {
        val dev = _uiState.value.devices.firstOrNull { it.id == deviceId } ?: return
        val clamped = dimming.coerceIn(10, 100)
        updateOptimisticState(deviceId) { it.copy(isOn = true, brightness = clamped) }
        throttler.submit("dim:$deviceId") {
            runCatching { wizSetBrightnessFfi(dev.ip, dev.port.toUShort(), clamped.toUByte()) }
                .onFailure { reportCmdError(deviceId, it) }
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
                wizSetRgbFfi(
                    dev.ip,
                    dev.port.toUShort(),
                    rr.toUByte(),
                    gg.toUByte(),
                    bb.toUByte(),
                    dim?.toUByte(),
                )
            }.onFailure { reportCmdError(deviceId, it) }
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
                wizSetTempFfi(dev.ip, dev.port.toUShort(), k.toUShort(), dim?.toUByte())
            }.onFailure { reportCmdError(deviceId, it) }
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
            }.onFailure { reportCmdError(deviceId, it) }
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
            else -> all.filter { it.id == deviceId || it.name == deviceId || it.ip == deviceId }
                .ifEmpty { listOf(all.first()) }
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
                    val sId = parts.firstOrNull() ?: 11
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
            errorMessage = err.message ?: "Error comunicando con WiZ",
        )
    }

    private fun syncPrimaryDeviceSignal() {
        val primary = _uiState.value.devices.firstOrNull { it.isFavorite } ?: _uiState.value.devices.firstOrNull()
        if (primary == null) {
            signalProvider.updateWizStatus(PrimaryWizStatus())
            return
        }
        val state = _uiState.value.statesByDeviceId[primary.id] ?: SmartDeviceStateDto()
        signalProvider.updateWizStatus(
            PrimaryWizStatus(
                isOn = state.isOn,
                dimming = state.brightness,
                name = primary.name,
                hex = WizColorMath.stateToHex(state),
            )
        )
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
