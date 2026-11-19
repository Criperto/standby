package com.criperto.standby.core

import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.content.IntentFilter
import android.hardware.Sensor
import android.hardware.SensorEvent
import android.hardware.SensorEventListener
import android.hardware.SensorManager
import android.os.BatteryManager
import com.criperto.standby.standby.MediaBridge
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.collectLatest
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch
import okhttp3.OkHttpClient
import okhttp3.Request
import org.json.JSONObject
import uniffi.standby_core.TickMode
import uniffi.standby_core.analyzePresetTickMode
import java.text.SimpleDateFormat
import java.util.Calendar
import java.util.Date
import java.util.Locale
import java.util.concurrent.TimeUnit
import kotlin.math.abs

data class DeviceSignals(
    val batteryLevel: Int = 85,
    val hasBattery: Boolean = true,
    val isCharging: Boolean = false,
    val chargeSource: String = "none", // none | ac | usb | wireless
    val isLandscape: Boolean = true,
    val ambientLux: Float? = null,
    val nowEpochMs: Long = System.currentTimeMillis(),
    val tSeconds: Double = 0.0,
    val hour: Int = 12,
    val minute: Int = 0,
    val second: Double = 0.0,
    val millisecond: Double = 0.0,
    val localMinuteOfDay: Int = 720,
    val localWeekdayMon0: Int = 0,
    val localYyyyMmDd: Long = 20261118,
    val dateText: String = "",
    val datetimeText: String = "",
    val media: MediaSnapshot = MediaSnapshot(),
    val weatherTemp: String = "",
    val weatherCode: Int = 0,
    val wizStatus: PrimaryWizStatus = PrimaryWizStatus(),
) {
    fun toEvalContextJson(
        scopeId: String = "@",
        baseValue: Double = 0.0,
        screenW: Double = 2400.0,
        screenH: Double = 1080.0,
        stateValues: Map<String, Double> = emptyMap(),
        scopeAncestors: Map<String, String> = emptyMap(),
        palette: MaterialYouPaletteDto? = null,
        isHovered: Boolean = false,
        isPressed: Boolean = false,
    ): String {
        val obj = JSONObject()
        obj.put("scopeId", scopeId)
        obj.put("baseValue", baseValue)
        obj.put("t", tSeconds)
        obj.put("hour", hour)
        obj.put("minute", minute)
        obj.put("second", second)
        obj.put("millisecond", millisecond)
        obj.put("battery", batteryLevel.toDouble())
        obj.put("hasBattery", hasBattery)
        obj.put("isCharging", isCharging)
        obj.put("hasMedia", media.hasMedia)
        obj.put("isPlaying", media.isPlaying)
        obj.put("hasCover", media.hasCover)
        obj.put("mediaProgress", media.progress)
        obj.put("mediaPosition", media.positionSec)
        obj.put("mediaLength", media.durationSec)
        obj.put("isShuffle", media.isShuffle)
        obj.put("isLoop", media.isLoop)
        obj.put("canNext", media.canNext)
        obj.put("canPrev", media.canPrev)
        obj.put("mediaTitle", media.title)
        obj.put("mediaArtist", media.artist)
        obj.put("weatherTemp", weatherTemp)
        obj.put("dateText", dateText)
        obj.put("datetimeText", datetimeText)
        obj.put("screenW", screenW)
        obj.put("screenH", screenH)
        obj.put("lux", (ambientLux ?: 100f).toDouble())
        obj.put("wizOn", wizStatus.isOn)
        obj.put("wizDim", wizStatus.dimming.toDouble())
        obj.put("wizName", wizStatus.name)
        obj.put("wizHex", wizStatus.hex)
        obj.put("isHovered", isHovered)
        obj.put("isPressed", isPressed)

        if (stateValues.isNotEmpty()) {
            val st = JSONObject()
            for ((k, v) in stateValues) st.put(k, v)
            obj.put("stateValues", st)
        }
        if (scopeAncestors.isNotEmpty()) {
            val anc = JSONObject()
            for ((k, v) in scopeAncestors) anc.put(k, v)
            obj.put("scopeAncestors", anc)
        }
        if (palette != null) {
            obj.put("palette", JSONObject(AppJson.encodeToString(palette)))
        }
        return obj.toString()
    }
}

class DeviceSignalProvider(
    private val context: Context,
    private val settingsRepository: SettingsRepository,
    private val presetRepository: PresetRepository,
) {
    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.Default)
    private val startNanos = System.nanoTime()
    private val sensorManager = context.getSystemService(Context.SENSOR_SERVICE) as SensorManager
    private val httpClient = OkHttpClient.Builder()
        .connectTimeout(6, TimeUnit.SECONDS)
        .readTimeout(6, TimeUnit.SECONDS)
        .build()

    private val dateFmt = SimpleDateFormat("EEEE, d 'de' MMMM", Locale("es", "AR"))
    private val datetimeFmt = SimpleDateFormat("dd/MM HH:mm", Locale("es", "AR"))

    private val _signals = MutableStateFlow(buildInitialSignals())
    val signals: StateFlow<DeviceSignals> = _signals.asStateFlow()

    private val _tickMode = MutableStateFlow(TickMode.MINUTE_ONLY)
    private val _activeConsumers = MutableStateFlow(0)
    private val _sensorsActive = MutableStateFlow(false)

    private val batteryReceiver = object : BroadcastReceiver() {
        override fun onReceive(ctx: Context?, intent: Intent?) {
            if (intent != null) updateBatteryFromIntent(intent)
        }
    }

    private val sensorListener = object : SensorEventListener {
        override fun onSensorChanged(event: SensorEvent) {
            when (event.sensor.type) {
                Sensor.TYPE_LIGHT -> {
                    val lux = event.values.firstOrNull() ?: return
                    _signals.value = _signals.value.copy(ambientLux = lux)
                }
                Sensor.TYPE_ACCELEROMETER -> {
                    val ax = abs(event.values[0])
                    val ay = abs(event.values[1])
                    val cur = _signals.value.isLandscape
                    // Hysteresis prevents flicker near 45 degrees
                    val next = when {
                        ax > 5.8f && ay < 4.5f -> true
                        ay > 5.8f && ax < 4.5f -> false
                        else -> cur
                    }
                    if (next != cur) {
                        _signals.value = _signals.value.copy(isLandscape = next)
                    }
                }
            }
        }

        override fun onAccuracyChanged(sensor: Sensor?, accuracy: Int) = Unit
    }

    init {
        registerBattery()
        observeMedia()
        observePresetTickMode()
        observeClockLoop()
        observeWeather()
    }

    private fun buildInitialSignals(): DeviceSignals {
        val cal = Calendar.getInstance()
        val now = Date(cal.timeInMillis)
        return updateClockFields(
            DeviceSignals(
                dateText = dateFmt.format(now).replaceFirstChar { it.uppercase() },
                datetimeText = datetimeFmt.format(now),
            ),
            cal,
            0.0,
        )
    }

    private fun registerBattery() {
        val filter = IntentFilter(Intent.ACTION_BATTERY_CHANGED)
        val sticky = context.registerReceiver(batteryReceiver, filter)
        if (sticky != null) updateBatteryFromIntent(sticky)
    }

    private fun updateBatteryFromIntent(intent: Intent) {
        val level = intent.getIntExtra(BatteryManager.EXTRA_LEVEL, -1)
        val scale = intent.getIntExtra(BatteryManager.EXTRA_SCALE, 100).coerceAtLeast(1)
        val pct = if (level >= 0) ((level * 100f) / scale).toInt().coerceIn(0, 100) else 85
        val status = intent.getIntExtra(BatteryManager.EXTRA_STATUS, -1)
        val plugged = intent.getIntExtra(BatteryManager.EXTRA_PLUGGED, 0)
        val charging = plugged != 0 || status == BatteryManager.BATTERY_STATUS_CHARGING ||
            status == BatteryManager.BATTERY_STATUS_FULL
        val source = when {
            (plugged and BatteryManager.BATTERY_PLUGGED_AC) != 0 -> "ac"
            (plugged and BatteryManager.BATTERY_PLUGGED_USB) != 0 -> "usb"
            (plugged and BatteryManager.BATTERY_PLUGGED_WIRELESS) != 0 -> "wireless"
            charging -> "ac"
            else -> "none"
        }
        _signals.value = _signals.value.copy(
            batteryLevel = pct,
            isCharging = charging,
            chargeSource = source,
        )
    }

    private fun observeMedia() {
        scope.launch {
            MediaBridge.mediaState.collect { m ->
                _signals.value = _signals.value.copy(media = m)
            }
        }
    }

    private fun observePresetTickMode() {
        scope.launch {
            presetRepository.activePresetFlow.collect { preset ->
                val raw = AppJson.encodeToString(preset)
                _tickMode.value = runCatching { analyzePresetTickMode(raw) }.getOrDefault(TickMode.MINUTE_ONLY)
            }
        }
    }

    private fun observeClockLoop() {
        scope.launch {
            kotlinx.coroutines.flow.combine(_tickMode, _activeConsumers) { mode, count ->
                mode to (count > 0)
            }.collectLatest { (mode, hasVisibleConsumer) ->
                while (isActive) {
                    val cal = Calendar.getInstance()
                    val tSec = (System.nanoTime() - startNanos) / 1_000_000_000.0
                    _signals.value = updateClockFields(_signals.value, cal, tSec)

                    val delayMs = when {
                        !hasVisibleConsumer -> 5_000L
                        mode == TickMode.FRAME60 -> 33L
                        mode == TickMode.SECOND -> (1000L - cal.get(Calendar.MILLISECOND)).coerceIn(50L, 1000L)
                        else -> {
                            val sec = cal.get(Calendar.SECOND)
                            val ms = cal.get(Calendar.MILLISECOND)
                            ((60 - sec) * 1000L - ms).coerceIn(250L, 60_000L)
                        }
                    }
                    delay(delayMs)
                }
            }
        }
    }

    private fun observeWeather() {
        scope.launch(Dispatchers.IO) {
            settingsRepository.settingsFlow.collectLatest { settings ->
                if (!settings.weatherEnabled) {
                    if (_signals.value.weatherTemp.isNotEmpty()) {
                        _signals.value = _signals.value.copy(weatherTemp = "")
                    }
                    return@collectLatest
                }
                while (isActive) {
                    fetchOpenMeteo(settings.weatherLat, settings.weatherLon)
                    delay(30 * 60_000L)
                }
            }
        }
    }

    private fun fetchOpenMeteo(lat: Double, lon: Double) {
        val url = "https://api.open-meteo.com/v1/forecast?latitude=$lat&longitude=$lon&current=temperature_2m,weather_code"
        runCatching {
            val req = Request.Builder().url(url).build()
            httpClient.newCall(req).execute().use { resp ->
                if (!resp.isSuccessful) return
                val body = resp.body?.string() ?: return
                val cur = JSONObject(body).optJSONObject("current") ?: return
                val temp = cur.optDouble("temperature_2m", Double.NaN)
                val code = cur.optInt("weather_code", 0)
                if (!temp.isNaN()) {
                    _signals.value = _signals.value.copy(
                        weatherTemp = "${temp.toInt()}°",
                        weatherCode = code,
                    )
                }
            }
        }
    }

    fun updateWizStatus(status: PrimaryWizStatus) {
        _signals.value = _signals.value.copy(wizStatus = status)
    }

    fun setSensorsActive(active: Boolean) {
        if (_sensorsActive.value == active) return
        _sensorsActive.value = active
        if (active) {
            sensorManager.getDefaultSensor(Sensor.TYPE_LIGHT)?.let {
                sensorManager.registerListener(sensorListener, it, SensorManager.SENSOR_DELAY_NORMAL)
            }
            sensorManager.getDefaultSensor(Sensor.TYPE_ACCELEROMETER)?.let {
                sensorManager.registerListener(sensorListener, it, SensorManager.SENSOR_DELAY_NORMAL)
            }
        } else {
            sensorManager.unregisterListener(sensorListener)
        }
    }

    fun acquireRenderConsumer() {
        _activeConsumers.value = (_activeConsumers.value + 1).coerceAtLeast(1)
    }

    fun releaseRenderConsumer() {
        _activeConsumers.value = (_activeConsumers.value - 1).coerceAtLeast(0)
    }

    private fun updateClockFields(
        prev: DeviceSignals,
        cal: Calendar,
        tSec: Double,
    ): DeviceSignals {
        val hour = cal.get(Calendar.HOUR_OF_DAY)
        val minute = cal.get(Calendar.MINUTE)
        val sec = cal.get(Calendar.SECOND)
        val ms = cal.get(Calendar.MILLISECOND)
        val year = cal.get(Calendar.YEAR)
        val month = cal.get(Calendar.MONTH) + 1
        val day = cal.get(Calendar.DAY_OF_MONTH)
        val dowCal = cal.get(Calendar.DAY_OF_WEEK) // Sun=1 .. Sat=7
        val weekdayMon0 = (dowCal + 5) % 7
        val yyyymmdd = year * 10000L + month * 100L + day
        val now = Date(cal.timeInMillis)
        val dateText = if (prev.localYyyyMmDd != yyyymmdd || prev.dateText.isEmpty()) {
            dateFmt.format(now).replaceFirstChar { it.uppercase() }
        } else {
            prev.dateText
        }
        return prev.copy(
            nowEpochMs = cal.timeInMillis,
            tSeconds = tSec,
            hour = hour,
            minute = minute,
            second = sec.toDouble(),
            millisecond = ms.toDouble(),
            localMinuteOfDay = hour * 60 + minute,
            localWeekdayMon0 = weekdayMon0,
            localYyyyMmDd = yyyymmdd,
            dateText = dateText,
            datetimeText = datetimeFmt.format(now),
        )
    }
}
