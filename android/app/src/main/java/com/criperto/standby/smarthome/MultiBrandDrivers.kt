package com.criperto.standby.smarthome

import com.criperto.standby.core.AppJson
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.booleanOrNull
import kotlinx.serialization.json.contentOrNull
import kotlinx.serialization.json.doubleOrNull
import kotlinx.serialization.json.intOrNull
import kotlinx.serialization.json.jsonArray
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import okhttp3.MediaType.Companion.toMediaType
import okhttp3.OkHttpClient
import okhttp3.Request
import okhttp3.RequestBody.Companion.toRequestBody
import org.json.JSONArray
import org.json.JSONObject
import java.io.BufferedReader
import java.io.InputStreamReader
import java.net.DatagramPacket
import java.net.DatagramSocket
import java.net.InetAddress
import java.net.InetSocketAddress
import java.net.Socket
import java.util.concurrent.Callable
import java.util.concurrent.Executors
import java.util.concurrent.TimeUnit
import kotlin.math.roundToInt

/**
 * Multi-brand & Multi-category Smart Home drivers:
 * 1. Home Assistant Server (auto-discovery on LAN port 8123 + REST API for Lights, Smart TVs, ACs, Switches, Fans)
 * 2. WLED HTTP JSON (/json/state)
 * 3. Shelly (Gen1/Gen2) & Tasmota local HTTP
 * 4. Yeelight local TCP (:55443)
 */
object MultiBrandDrivers {
    private val jsonMediaType = "application/json; charset=utf-8".toMediaType()

    val httpClient: OkHttpClient = OkHttpClient.Builder()
        .connectTimeout(4, TimeUnit.SECONDS)
        .readTimeout(6, TimeUnit.SECONDS)
        .build()

    // =========================================================================
    // 1. HOME ASSISTANT SERVER (Auto-Discovery + REST API)
    // =========================================================================

    fun discoverHomeAssistantUrl(subnetPrefix: String?): String? {
        val candidates = mutableListOf(
            "http://homeassistant.local:8123",
            "http://homeassistant:8123",
        )
        for (url in candidates) {
            if (isHomeAssistantAlive(url)) return url
        }
        val prefix = subnetPrefix?.takeIf { it.count { c -> c == '.' } == 2 } ?: return null
        val pool = Executors.newFixedThreadPool(24)
        return try {
            val tasks = (1..254).map { host ->
                Callable {
                    val ip = "$prefix.$host"
                    val sock = Socket()
                    try {
                        sock.connect(InetSocketAddress(ip, 8123), 180)
                        "http://$ip:8123"
                    } catch (_: Throwable) {
                        null
                    } finally {
                        runCatching { sock.close() }
                    }
                }
            }
            val openUrls = pool.invokeAll(tasks, 4, TimeUnit.SECONDS)
                .mapNotNull { runCatching { it.get() }.getOrNull() }
            openUrls.firstOrNull { isHomeAssistantAlive(it) } ?: openUrls.firstOrNull()
        } finally {
            pool.shutdownNow()
        }
    }

    private fun isHomeAssistantAlive(baseUrl: String): Boolean {
        return runCatching {
            val clean = baseUrl.trimEnd('/')
            val req = Request.Builder().url("$clean/manifest.json").build()
            httpClient.newCall(req).execute().use { resp ->
                resp.isSuccessful || resp.code == 401
            }
        }.getOrDefault(false)
    }

    fun fetchHomeAssistantEntities(
        serverUrl: String,
        token: String,
    ): Result<List<Pair<WizDeviceConfig, SmartDeviceStateDto>>> = runCatching {
        val cleanUrl = serverUrl.trim().trimEnd('/')
        require(cleanUrl.startsWith("http")) { "URL de Home Assistant inválida" }
        val reqBuilder = Request.Builder().url("$cleanUrl/api/states")
        if (token.isNotBlank()) {
            reqBuilder.header("Authorization", "Bearer ${token.trim()}")
        }
        httpClient.newCall(reqBuilder.build()).execute().use { resp ->
            if (resp.code == 401) error("Token de Home Assistant inválido o faltante (401)")
            if (!resp.isSuccessful) error("Error HTTP ${resp.code} conectando a Home Assistant")
            val body = resp.body?.string().orEmpty()
            parseHomeAssistantStatesJson(body, cleanUrl)
        }
    }

    fun parseHomeAssistantStatesJson(
        rawJsonArray: String,
        serverUrl: String = "http://homeassistant.local:8123",
    ): List<Pair<WizDeviceConfig, SmartDeviceStateDto>> {
        val arr = AppJson.parseToJsonElement(rawJsonArray).jsonArray
        val out = mutableListOf<Pair<WizDeviceConfig, SmartDeviceStateDto>>()

        for (elem in arr) {
            val obj = runCatching { elem.jsonObject }.getOrNull() ?: continue
            val entityId = obj["entity_id"]?.jsonPrimitive?.contentOrNull.orEmpty()
            val domain = entityId.substringBefore('.', "")
            val category = when (domain) {
                "light" -> "light"
                "climate" -> "ac"
                "media_player" -> "tv"
                "switch", "input_boolean" -> "switch"
                "fan" -> "fan"
                else -> continue
            }
            val rawState = (obj["state"]?.jsonPrimitive?.contentOrNull ?: "off").lowercase()
            if (rawState == "unavailable" || rawState == "unknown") continue

            val attrs = runCatching { obj["attributes"]?.jsonObject }.getOrNull() ?: JsonObject(emptyMap())
            val friendlyName = attrs["friendly_name"]?.jsonPrimitive?.contentOrNull?.ifBlank { null } ?: entityId
            val area = attrs["area_id"]?.jsonPrimitive?.contentOrNull?.ifBlank { null } ?: when (category) {
                "ac" -> "Climatización"
                "tv" -> "Multimedia / TV"
                "light" -> "Iluminación"
                else -> "Hogar"
            }

            val isOn = rawState !in listOf("off", "standby", "idle")
            var brightness = 100
            var r: Int? = null
            var g: Int? = null
            var b: Int? = null
            var kelvin: Int? = null
            var targetTempC: Int? = null
            var currentTempC: Int? = null
            var hvacMode: String? = null
            var volumePct: Int? = null
            var isMuted: Boolean = false
            var tvSource: String? = null
            val tvSources = mutableListOf<String>()

            when (category) {
                "light" -> {
                    val bri255 = attrs["brightness"]?.jsonPrimitive?.doubleOrNull
                    if (bri255 != null) {
                        brightness = ((bri255 / 255.0) * 100.0).roundToInt().coerceIn(10, 100)
                    }
                    val rgbArr = runCatching { attrs["rgb_color"]?.jsonArray }.getOrNull()
                    if (rgbArr != null && rgbArr.size >= 3) {
                        r = rgbArr[0].jsonPrimitive.intOrNull
                        g = rgbArr[1].jsonPrimitive.intOrNull
                        b = rgbArr[2].jsonPrimitive.intOrNull
                    }
                    val k = attrs["color_temp_kelvin"]?.jsonPrimitive?.intOrNull ?: -1
                    if (k > 0) kelvin = k
                }
                "ac" -> {
                    hvacMode = rawState
                    val tgt = attrs["temperature"]?.jsonPrimitive?.doubleOrNull
                    if (tgt != null) targetTempC = tgt.roundToInt().coerceIn(16, 32)
                    val cur = attrs["current_temperature"]?.jsonPrimitive?.doubleOrNull
                    if (cur != null) currentTempC = cur.roundToInt()
                    brightness = targetTempC ?: 24
                }
                "tv" -> {
                    val vol = attrs["volume_level"]?.jsonPrimitive?.doubleOrNull
                    if (vol != null) {
                        volumePct = (vol * 100.0).roundToInt().coerceIn(0, 100)
                        brightness = volumePct.coerceIn(10, 100)
                    }
                    isMuted = attrs["is_volume_muted"]?.jsonPrimitive?.booleanOrNull ?: false
                    tvSource = attrs["source"]?.jsonPrimitive?.contentOrNull?.takeIf { it.isNotBlank() }
                        ?: attrs["media_title"]?.jsonPrimitive?.contentOrNull?.takeIf { it.isNotBlank() }
                    val srcList = runCatching { attrs["source_list"]?.jsonArray }.getOrNull()
                    if (srcList != null) {
                        for (sIdx in 0 until minOf(srcList.size, 10)) {
                            srcList[sIdx].jsonPrimitive.contentOrNull?.takeIf { it.isNotBlank() }?.let { tvSources.add(it) }
                        }
                    }
                }
                "fan" -> {
                    val pct = attrs["percentage"]?.jsonPrimitive?.intOrNull ?: 100
                    brightness = pct.coerceIn(10, 100)
                }
            }

            val cfg = WizDeviceConfig(
                id = "ha_$entityId",
                name = friendlyName,
                ip = serverUrl,
                port = 8123,
                room = area,
                supportsRgb = category == "light",
                supportsTemp = category == "light",
                driver = "home_assistant",
                category = category,
                entityId = entityId,
            )
            val st = SmartDeviceStateDto(
                isOn = isOn,
                brightness = brightness,
                r = r,
                g = g,
                b = b,
                tempKelvin = kelvin,
                targetTempC = targetTempC,
                currentTempC = currentTempC,
                hvacMode = hvacMode,
                volumePct = volumePct,
                isMuted = isMuted,
                tvSource = tvSource,
                tvSources = tvSources,
            )
            out.add(cfg to st)
        }
        return out
    }

    fun callHomeAssistantService(
        serverUrl: String,
        token: String,
        domain: String,
        service: String,
        payload: JSONObject,
    ): Result<Unit> = runCatching {
        val cleanUrl = serverUrl.trim().trimEnd('/')
        val reqBuilder = Request.Builder()
            .url("$cleanUrl/api/services/$domain/$service")
            .post(payload.toString().toRequestBody(jsonMediaType))
        if (token.isNotBlank()) {
            reqBuilder.header("Authorization", "Bearer ${token.trim()}")
        }
        httpClient.newCall(reqBuilder.build()).execute().use { resp ->
            if (!resp.isSuccessful) error("Error Home Assistant ($domain.$service): HTTP ${resp.code}")
        }
    }

    // =========================================================================
    // 2. WLED LOCAL HTTP DRIVER (/json/state)
    // =========================================================================

    fun probeWled(ip: String): Result<Pair<WizDeviceConfig, SmartDeviceStateDto>> = runCatching {
        val cleanIp = ip.trim().removePrefix("http://").removeSuffix("/")
        val req = Request.Builder().url("http://$cleanIp/json").build()
        httpClient.newCall(req).execute().use { resp ->
            if (!resp.isSuccessful) error("WLED no respondió en $cleanIp")
            val root = JSONObject(resp.body?.string().orEmpty())
            val stateObj = root.optJSONObject("state") ?: error("JSON WLED sin 'state'")
            val infoObj = root.optJSONObject("info")
            val name = infoObj?.optString("name")?.ifBlank { "WLED $cleanIp" } ?: "WLED $cleanIp"
            val mac = infoObj?.optString("mac").orEmpty()
            val isOn = stateObj.optBoolean("on", false)
            val bri = ((stateObj.optInt("bri", 255) / 255f) * 100f).roundToInt().coerceIn(10, 100)
            var r: Int? = null
            var g: Int? = null
            var b: Int? = null
            val seg0 = stateObj.optJSONArray("seg")?.optJSONObject(0)
            val col0 = seg0?.optJSONArray("col")?.optJSONArray(0)
            if (col0 != null && col0.length() >= 3) {
                r = col0.optInt(0)
                g = col0.optInt(1)
                b = col0.optInt(2)
            }
            val cfg = WizDeviceConfig(
                id = "wled_${mac.ifBlank { cleanIp }}",
                name = name,
                ip = cleanIp,
                port = 80,
                mac = mac,
                driver = "wled",
                category = "light",
            )
            val st = SmartDeviceStateDto(isOn = isOn, brightness = bri, r = r, g = g, b = b)
            cfg to st
        }
    }

    fun sendWledState(ip: String, payload: JSONObject): Result<Unit> = runCatching {
        val req = Request.Builder()
            .url("http://${ip.trim()}/json/state")
            .post(payload.toString().toRequestBody(jsonMediaType))
            .build()
        httpClient.newCall(req).execute().use { resp ->
            if (!resp.isSuccessful) error("WLED HTTP ${resp.code}")
        }
    }

    // =========================================================================
    // 3. SHELLY / TASMOTA LOCAL HTTP DRIVER
    // =========================================================================

    fun probeShellyOrTasmota(
        ip: String,
        driverHint: String,
        customName: String?,
        category: String,
    ): Result<Pair<WizDeviceConfig, SmartDeviceStateDto>> = runCatching {
        val cleanIp = ip.trim().removePrefix("http://").removeSuffix("/")
        if (driverHint == "tasmota") {
            val req = Request.Builder().url("http://$cleanIp/cm?cmnd=Power").build()
            httpClient.newCall(req).execute().use { resp ->
                if (!resp.isSuccessful) error("Tasmota no respondió en $cleanIp")
                val body = resp.body?.string().orEmpty()
                val isOn = body.contains("\"ON\"", ignoreCase = true)
                val cfg = WizDeviceConfig(
                    id = "tasmota_$cleanIp",
                    name = customName?.takeIf { it.isNotBlank() } ?: "Tasmota $cleanIp",
                    ip = cleanIp,
                    port = 80,
                    driver = "tasmota",
                    category = category,
                )
                return@runCatching cfg to SmartDeviceStateDto(isOn = isOn, brightness = 100)
            }
        } else {
            val req = Request.Builder().url("http://$cleanIp/rpc/Switch.GetStatus?id=0").build()
            httpClient.newCall(req).execute().use { resp ->
                val isOn = if (resp.isSuccessful) {
                    JSONObject(resp.body?.string().orEmpty()).optBoolean("output", false)
                } else {
                    false
                }
                val cfg = WizDeviceConfig(
                    id = "shelly_$cleanIp",
                    name = customName?.takeIf { it.isNotBlank() } ?: "Shelly $cleanIp",
                    ip = cleanIp,
                    port = 80,
                    driver = "shelly",
                    category = category,
                )
                cfg to SmartDeviceStateDto(isOn = isOn, brightness = 100)
            }
        }
    }

    fun setShellyPower(ip: String, on: Boolean): Result<Unit> = runCatching {
        val gen2Url = "http://${ip.trim()}/rpc/Switch.Set?id=0&on=$on"
        val req = Request.Builder().url(gen2Url).build()
        httpClient.newCall(req).execute().use { resp ->
            if (!resp.isSuccessful) {
                val gen1Url = "http://${ip.trim()}/relay/0?turn=${if (on) "on" else "off"}"
                httpClient.newCall(Request.Builder().url(gen1Url).build()).execute().close()
            }
        }
    }

    fun setTasmotaPower(ip: String, on: Boolean): Result<Unit> = runCatching {
        val url = "http://${ip.trim()}/cm?cmnd=Power%20${if (on) "On" else "Off"}"
        httpClient.newCall(Request.Builder().url(url).build()).execute().close()
    }

    // =========================================================================
    // 4. YEELIGHT LOCAL TCP (:55443) & SSDP (:1982) + LAN MULTI-BRAND SWEEP
    // =========================================================================

    fun sendYeelightCommand(ip: String, method: String, paramsJsonArray: String): Result<String> = runCatching {
        Socket().use { socket ->
            socket.connect(InetSocketAddress(ip.trim(), 55443), 1500)
            socket.soTimeout = 1500
            val cmd = """{"id":1,"method":"$method","params":$paramsJsonArray}""" + "\r\n"
            socket.getOutputStream().write(cmd.toByteArray())
            socket.getOutputStream().flush()
            BufferedReader(InputStreamReader(socket.getInputStream())).readLine().orEmpty()
        }
    }

    fun probeYeelight(
        ip: String,
        customName: String? = null,
        room: String = "Escritorio",
    ): Result<Pair<WizDeviceConfig, SmartDeviceStateDto>> = runCatching {
        val cleanIp = ip.trim()
        val line = sendYeelightCommand(
            cleanIp,
            "get_prop",
            """["power","bright","ct","rgb"]""",
        ).getOrThrow()
        val arr = runCatching {
            AppJson.parseToJsonElement(line).jsonObject["result"]?.jsonArray
        }.getOrNull()
        val isOn = (arr?.getOrNull(0)?.jsonPrimitive?.contentOrNull ?: "off").equals("on", ignoreCase = true)
        val bri = (arr?.getOrNull(1)?.jsonPrimitive?.intOrNull ?: 100).coerceIn(10, 100)
        val ct = arr?.getOrNull(2)?.jsonPrimitive?.intOrNull?.takeIf { it > 0 }
        val rgbInt = arr?.getOrNull(3)?.jsonPrimitive?.intOrNull?.takeIf { it > 0 }
        val r = rgbInt?.let { (it shr 16) and 0xFF }
        val g = rgbInt?.let { (it shr 8) and 0xFF }
        val b = rgbInt?.let { it and 0xFF }

        val cfg = WizDeviceConfig(
            id = "yeelight_$cleanIp",
            name = customName?.takeIf { it.isNotBlank() } ?: "Yeelight $cleanIp",
            ip = cleanIp,
            port = 55443,
            room = room,
            driver = "yeelight",
            category = "light",
        )
        val st = SmartDeviceStateDto(
            isOn = isOn,
            brightness = bri,
            tempKelvin = ct,
            r = r,
            g = g,
            b = b,
        )
        cfg to st
    }

    fun discoverYeelightSsdp(timeoutMs: Int = 850): List<Pair<WizDeviceConfig, SmartDeviceStateDto>> {
        val found = mutableMapOf<String, Pair<WizDeviceConfig, SmartDeviceStateDto>>()
        runCatching {
            DatagramSocket().use { sock ->
                sock.soTimeout = timeoutMs
                val msg = (
                    "M-SEARCH * HTTP/1.1\r\n" +
                        "HOST: 239.255.255.250:1982\r\n" +
                        "MAN: \"ssdp:discover\"\r\n" +
                        "ST: wifi_bulb\r\n\r\n"
                    ).toByteArray()
                val group = InetAddress.getByName("239.255.255.250")
                sock.send(DatagramPacket(msg, msg.size, group, 1982))
                val buf = ByteArray(2048)
                val endAt = System.currentTimeMillis() + timeoutMs
                while (System.currentTimeMillis() < endAt) {
                    val pkt = DatagramPacket(buf, buf.size)
                    try {
                        sock.receive(pkt)
                    } catch (_: Throwable) {
                        break
                    }
                    val txt = String(pkt.data, 0, pkt.length)
                    val headers = txt.lines()
                        .mapNotNull { line ->
                            val idx = line.indexOf(':')
                            if (idx > 0) line.substring(0, idx).trim().lowercase() to line.substring(idx + 1).trim() else null
                        }
                        .toMap()
                    val loc = headers["location"] ?: continue
                    val ip = loc.removePrefix("yeelight://").substringBefore(':').trim()
                    if (ip.isBlank()) continue
                    val id = headers["id"] ?: ip
                    val name = headers["name"]?.takeIf { it.isNotBlank() } ?: "Yeelight ${id.takeLast(4)}"
                    val isOn = headers["power"].equals("on", ignoreCase = true)
                    val bri = headers["bright"]?.toIntOrNull()?.coerceIn(10, 100) ?: 100
                    val cfg = WizDeviceConfig(
                        id = "yeelight_$id",
                        name = name,
                        ip = ip,
                        port = 55443,
                        driver = "yeelight",
                        category = "light",
                    )
                    found[ip] = cfg to SmartDeviceStateDto(isOn = isOn, brightness = bri)
                }
            }
        }
        return found.values.toList()
    }

    fun discoverHttpSmartDevicesOnSubnet(
        subnetPrefix: String?,
    ): List<Pair<WizDeviceConfig, SmartDeviceStateDto>> {
        val prefix = subnetPrefix?.takeIf { it.count { c -> c == '.' } == 2 } ?: return emptyList()
        val pool = Executors.newFixedThreadPool(24)
        return try {
            val port80Tasks = (1..254).map { host ->
                Callable {
                    val ip = "$prefix.$host"
                    val sock = Socket()
                    try {
                        sock.connect(InetSocketAddress(ip, 80), 140)
                        ip
                    } catch (_: Throwable) {
                        null
                    } finally {
                        runCatching { sock.close() }
                    }
                }
            }
            val openIps = pool.invokeAll(port80Tasks, 3, TimeUnit.SECONDS)
                .mapNotNull { runCatching { it.get() }.getOrNull() }

            val results = mutableListOf<Pair<WizDeviceConfig, SmartDeviceStateDto>>()
            for (ip in openIps) {
                // 1) Try WLED (/json)
                val wled = probeWled(ip).getOrNull()
                if (wled != null) {
                    results.add(wled)
                    continue
                }
                // 2) Try Shelly Gen2 (/rpc/Shelly.GetDeviceInfo)
                val shelly = runCatching {
                    val req = Request.Builder().url("http://$ip/rpc/Shelly.GetDeviceInfo").build()
                    httpClient.newCall(req).execute().use { resp ->
                        if (resp.isSuccessful) {
                            val obj = AppJson.parseToJsonElement(resp.body?.string().orEmpty()).jsonObject
                            if (obj.containsKey("id") && obj.containsKey("app")) {
                                probeShellyOrTasmota(ip, "shelly", obj["name"]?.jsonPrimitive?.contentOrNull, "switch").getOrNull()
                            } else null
                        } else null
                    }
                }.getOrNull()
                if (shelly != null) {
                    results.add(shelly)
                    continue
                }
                // 3) Try Tasmota (/cm?cmnd=Status)
                val tasmota = runCatching {
                    val req = Request.Builder().url("http://$ip/cm?cmnd=Status").build()
                    httpClient.newCall(req).execute().use { resp ->
                        val body = resp.body?.string().orEmpty()
                        if (resp.isSuccessful && body.contains("\"Status\"")) {
                            probeShellyOrTasmota(ip, "tasmota", null, "switch").getOrNull()
                        } else null
                    }
                }.getOrNull()
                if (tasmota != null) {
                    results.add(tasmota)
                }
            }
            results
        } finally {
            pool.shutdownNow()
        }
    }
}
