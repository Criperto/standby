package com.criperto.standby.core

import android.content.Context
import androidx.datastore.core.DataStore
import androidx.datastore.preferences.core.Preferences
import androidx.datastore.preferences.core.booleanPreferencesKey
import androidx.datastore.preferences.core.doublePreferencesKey
import androidx.datastore.preferences.core.edit
import androidx.datastore.preferences.core.floatPreferencesKey
import androidx.datastore.preferences.core.intPreferencesKey
import androidx.datastore.preferences.core.stringPreferencesKey
import androidx.datastore.preferences.preferencesDataStore
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.map

private val Context.settingsDataStore: DataStore<Preferences> by preferencesDataStore(name = "standby_settings")

data class AppSettings(
    val activePresetName: String = "iphone_1_v2",
    // Trigger & runtime
    val triggersEnabled: Boolean = true,
    val autoTriggerServiceEnabled: Boolean = true,
    val dreamServiceEnabled: Boolean = true,
    val chargingReq: String = "any_charging", // any_charging | ac_only | usb_only | wireless_only | always_ignore_charging
    val orientationReq: String = "landscape_only", // landscape_only | any_orientation
    val orientationMode: String = "sensor_landscape", // sensor_landscape | landscape | reverse_landscape | auto
    val timeWindowEnabled: Boolean = false,
    val timeWindowStartMin: Int = 8 * 60,
    val timeWindowEndMin: Int = 23 * 60,
    val weekdaysMask: Int = 0x7F,
    val dateExclusions: List<DateRangeDto> = emptyList(),
    // Night & Independent Brightness
    val nightModeEnabled: Boolean = true,
    val nightStartMin: Int = 23 * 60,
    val nightEndMin: Int = 7 * 60,
    val nightRedTintEnabled: Boolean = false,
    val useSystemBrightness: Boolean = false,
    val standbyBrightness: Float = 0.28f,
    val nightBrightness: Float = 0.06f,
    val hubBrightness: Float = 0.58f,
    // OLED Burn-in protection
    val pixelShiftEnabled: Boolean = true,
    val pixelShiftMaxPx: Float = 8f,
    val pixelShiftIntervalSec: Int = 60,
    // AI Presence & Ambient Light
    val presenceMode: String = "disabled", // disabled | light_only | face_only | face_or_light | face_and_light
    val darkThresholdLux: Float = 8f,
    val luxHysteresis: Float = 3f,
    val cameraPollIntervalSec: Int = 5,
    val sleepDelaySec: Int = 20,
    val nightBlackoutEnabled: Boolean = true,
    // Tap-to-Wake Smart Home Hub
    val tapToWakeEnabled: Boolean = true,
    val autoReturnSec: Int = 25,
    // Automatic & Live Open-Meteo Weather
    val weatherEnabled: Boolean = true,
    val weatherAutoLocation: Boolean = true,
    val weatherCity: String = "Rosario",
    val weatherLat: Double = -32.9468,
    val weatherLon: Double = -60.6393,
) {
    fun toTriggerRulesDto(): TriggerRulesDto {
        val windows = if (timeWindowEnabled) {
            listOf(TimeWindowDto(timeWindowStartMin, timeWindowEndMin))
        } else {
            emptyList()
        }
        val nightWin = if (nightModeEnabled) {
            TimeWindowDto(nightStartMin, nightEndMin)
        } else {
            null
        }
        return TriggerRulesDto(
            enabled = triggersEnabled,
            chargingReq = chargingReq,
            orientationReq = orientationReq,
            timeWindows = windows,
            weekdaysMask = weekdaysMask,
            dateRanges = dateExclusions,
            nightWindow = nightWin,
            useSystemBrightness = useSystemBrightness,
            standbyBrightness = standbyBrightness,
            nightBrightness = nightBrightness,
            hubBrightness = hubBrightness,
            presenceMode = presenceMode,
            darkThresholdLux = darkThresholdLux,
            luxHysteresis = luxHysteresis,
            sleepDelaySec = sleepDelaySec,
            cameraPollIntervalSec = cameraPollIntervalSec,
            nightBlackoutEnabled = nightBlackoutEnabled,
        )
    }
}

class SettingsRepository(private val context: Context) {
    private object Keys {
        val ACTIVE_PRESET = stringPreferencesKey("active_preset")
        val TRIGGERS_ENABLED = booleanPreferencesKey("triggers_enabled")
        val AUTO_TRIGGER_SERVICE = booleanPreferencesKey("auto_trigger_service")
        val DREAM_SERVICE = booleanPreferencesKey("dream_service")
        val CHARGING_REQ = stringPreferencesKey("charging_req")
        val ORIENTATION_REQ = stringPreferencesKey("orientation_req")
        val ORIENTATION_MODE = stringPreferencesKey("orientation_mode")
        val TIME_WIN_ENABLED = booleanPreferencesKey("time_win_enabled")
        val TIME_WIN_START = intPreferencesKey("time_win_start")
        val TIME_WIN_END = intPreferencesKey("time_win_end")
        val WEEKDAYS_MASK = intPreferencesKey("weekdays_mask")
        val DATE_EXCLUSIONS = stringPreferencesKey("date_exclusions_json")

        val NIGHT_ENABLED = booleanPreferencesKey("night_enabled")
        val NIGHT_START = intPreferencesKey("night_start")
        val NIGHT_END = intPreferencesKey("night_end")
        val NIGHT_RED_TINT = booleanPreferencesKey("night_red_tint")
        val USE_SYSTEM_BRIGHTNESS = booleanPreferencesKey("use_system_brightness")
        val STANDBY_BRIGHTNESS = floatPreferencesKey("standby_brightness")
        val NIGHT_BRIGHTNESS = floatPreferencesKey("night_brightness")
        val HUB_BRIGHTNESS = floatPreferencesKey("hub_brightness")

        val PIXEL_SHIFT_ENABLED = booleanPreferencesKey("pixel_shift_enabled")
        val PIXEL_SHIFT_MAX_PX = floatPreferencesKey("pixel_shift_max_px")
        val PIXEL_SHIFT_INTERVAL = intPreferencesKey("pixel_shift_interval_sec")

        val PRESENCE_MODE = stringPreferencesKey("presence_mode")
        val DARK_LUX = floatPreferencesKey("dark_lux")
        val LUX_HYST = floatPreferencesKey("lux_hyst")
        val CAMERA_POLL_SEC = intPreferencesKey("camera_poll_sec")
        val SLEEP_DELAY_SEC = intPreferencesKey("sleep_delay_sec")
        val NIGHT_BLACKOUT = booleanPreferencesKey("night_blackout")

        val TAP_TO_WAKE = booleanPreferencesKey("tap_to_wake")
        val AUTO_RETURN_SEC = intPreferencesKey("auto_return_sec")

        val WEATHER_ENABLED = booleanPreferencesKey("weather_enabled")
        val WEATHER_AUTO_LOC = booleanPreferencesKey("weather_auto_location")
        val WEATHER_CITY = stringPreferencesKey("weather_city")
        val WEATHER_LAT = doublePreferencesKey("weather_lat")
        val WEATHER_LON = doublePreferencesKey("weather_lon")
    }

    val settingsFlow: Flow<AppSettings> = context.settingsDataStore.data.map { p ->
        val exclusionsJson = p[Keys.DATE_EXCLUSIONS] ?: "[]"
        val exclusions = runCatching {
            AppJson.decodeFromString<List<DateRangeDto>>(exclusionsJson)
        }.getOrDefault(emptyList())

        AppSettings(
            activePresetName = p[Keys.ACTIVE_PRESET] ?: "iphone_1_v2",
            triggersEnabled = p[Keys.TRIGGERS_ENABLED] ?: true,
            autoTriggerServiceEnabled = p[Keys.AUTO_TRIGGER_SERVICE] ?: true,
            dreamServiceEnabled = p[Keys.DREAM_SERVICE] ?: true,
            chargingReq = p[Keys.CHARGING_REQ] ?: "any_charging",
            orientationReq = p[Keys.ORIENTATION_REQ] ?: "landscape_only",
            orientationMode = p[Keys.ORIENTATION_MODE] ?: "sensor_landscape",
            timeWindowEnabled = p[Keys.TIME_WIN_ENABLED] ?: false,
            timeWindowStartMin = p[Keys.TIME_WIN_START] ?: (8 * 60),
            timeWindowEndMin = p[Keys.TIME_WIN_END] ?: (23 * 60),
            weekdaysMask = p[Keys.WEEKDAYS_MASK] ?: 0x7F,
            dateExclusions = exclusions,
            nightModeEnabled = p[Keys.NIGHT_ENABLED] ?: true,
            nightStartMin = p[Keys.NIGHT_START] ?: (23 * 60),
            nightEndMin = p[Keys.NIGHT_END] ?: (7 * 60),
            nightRedTintEnabled = p[Keys.NIGHT_RED_TINT] ?: false,
            useSystemBrightness = p[Keys.USE_SYSTEM_BRIGHTNESS] ?: false,
            standbyBrightness = p[Keys.STANDBY_BRIGHTNESS] ?: 0.28f,
            nightBrightness = p[Keys.NIGHT_BRIGHTNESS] ?: 0.06f,
            hubBrightness = p[Keys.HUB_BRIGHTNESS] ?: 0.58f,
            pixelShiftEnabled = p[Keys.PIXEL_SHIFT_ENABLED] ?: true,
            pixelShiftMaxPx = p[Keys.PIXEL_SHIFT_MAX_PX] ?: 8f,
            pixelShiftIntervalSec = p[Keys.PIXEL_SHIFT_INTERVAL] ?: 60,
            presenceMode = p[Keys.PRESENCE_MODE] ?: "disabled",
            darkThresholdLux = p[Keys.DARK_LUX] ?: 8f,
            luxHysteresis = p[Keys.LUX_HYST] ?: 3f,
            cameraPollIntervalSec = p[Keys.CAMERA_POLL_SEC] ?: 5,
            sleepDelaySec = p[Keys.SLEEP_DELAY_SEC] ?: 20,
            nightBlackoutEnabled = p[Keys.NIGHT_BLACKOUT] ?: true,
            tapToWakeEnabled = p[Keys.TAP_TO_WAKE] ?: true,
            autoReturnSec = p[Keys.AUTO_RETURN_SEC] ?: 25,
            weatherEnabled = p[Keys.WEATHER_ENABLED] ?: true,
            weatherAutoLocation = p[Keys.WEATHER_AUTO_LOC] ?: true,
            weatherCity = p[Keys.WEATHER_CITY] ?: "Rosario",
            weatherLat = p[Keys.WEATHER_LAT] ?: -32.9468,
            weatherLon = p[Keys.WEATHER_LON] ?: -60.6393,
        )
    }

    suspend fun update(transform: (AppSettings) -> AppSettings) {
        context.settingsDataStore.edit { prefs ->
            val current = AppSettings(
                activePresetName = prefs[Keys.ACTIVE_PRESET] ?: "iphone_1_v2",
                triggersEnabled = prefs[Keys.TRIGGERS_ENABLED] ?: true,
                autoTriggerServiceEnabled = prefs[Keys.AUTO_TRIGGER_SERVICE] ?: true,
                dreamServiceEnabled = prefs[Keys.DREAM_SERVICE] ?: true,
                chargingReq = prefs[Keys.CHARGING_REQ] ?: "any_charging",
                orientationReq = prefs[Keys.ORIENTATION_REQ] ?: "landscape_only",
                orientationMode = prefs[Keys.ORIENTATION_MODE] ?: "sensor_landscape",
                timeWindowEnabled = prefs[Keys.TIME_WIN_ENABLED] ?: false,
                timeWindowStartMin = prefs[Keys.TIME_WIN_START] ?: (8 * 60),
                timeWindowEndMin = prefs[Keys.TIME_WIN_END] ?: (23 * 60),
                weekdaysMask = prefs[Keys.WEEKDAYS_MASK] ?: 0x7F,
                dateExclusions = runCatching {
                    AppJson.decodeFromString<List<DateRangeDto>>(prefs[Keys.DATE_EXCLUSIONS] ?: "[]")
                }.getOrDefault(emptyList()),
                nightModeEnabled = prefs[Keys.NIGHT_ENABLED] ?: true,
                nightStartMin = prefs[Keys.NIGHT_START] ?: (23 * 60),
                nightEndMin = prefs[Keys.NIGHT_END] ?: (7 * 60),
                nightRedTintEnabled = prefs[Keys.NIGHT_RED_TINT] ?: false,
                useSystemBrightness = prefs[Keys.USE_SYSTEM_BRIGHTNESS] ?: false,
                standbyBrightness = prefs[Keys.STANDBY_BRIGHTNESS] ?: 0.28f,
                nightBrightness = prefs[Keys.NIGHT_BRIGHTNESS] ?: 0.06f,
                hubBrightness = prefs[Keys.HUB_BRIGHTNESS] ?: 0.58f,
                pixelShiftEnabled = prefs[Keys.PIXEL_SHIFT_ENABLED] ?: true,
                pixelShiftMaxPx = prefs[Keys.PIXEL_SHIFT_MAX_PX] ?: 8f,
                pixelShiftIntervalSec = prefs[Keys.PIXEL_SHIFT_INTERVAL] ?: 60,
                presenceMode = prefs[Keys.PRESENCE_MODE] ?: "disabled",
                darkThresholdLux = prefs[Keys.DARK_LUX] ?: 8f,
                luxHysteresis = prefs[Keys.LUX_HYST] ?: 3f,
                cameraPollIntervalSec = prefs[Keys.CAMERA_POLL_SEC] ?: 5,
                sleepDelaySec = prefs[Keys.SLEEP_DELAY_SEC] ?: 20,
                nightBlackoutEnabled = prefs[Keys.NIGHT_BLACKOUT] ?: true,
                tapToWakeEnabled = prefs[Keys.TAP_TO_WAKE] ?: true,
                autoReturnSec = prefs[Keys.AUTO_RETURN_SEC] ?: 25,
                weatherEnabled = prefs[Keys.WEATHER_ENABLED] ?: true,
                weatherAutoLocation = prefs[Keys.WEATHER_AUTO_LOC] ?: true,
                weatherCity = prefs[Keys.WEATHER_CITY] ?: "Rosario",
                weatherLat = prefs[Keys.WEATHER_LAT] ?: -32.9468,
                weatherLon = prefs[Keys.WEATHER_LON] ?: -60.6393,
            )
            val next = transform(current)
            prefs[Keys.ACTIVE_PRESET] = next.activePresetName
            prefs[Keys.TRIGGERS_ENABLED] = next.triggersEnabled
            prefs[Keys.AUTO_TRIGGER_SERVICE] = next.autoTriggerServiceEnabled
            prefs[Keys.DREAM_SERVICE] = next.dreamServiceEnabled
            prefs[Keys.CHARGING_REQ] = next.chargingReq
            prefs[Keys.ORIENTATION_REQ] = next.orientationReq
            prefs[Keys.ORIENTATION_MODE] = next.orientationMode
            prefs[Keys.TIME_WIN_ENABLED] = next.timeWindowEnabled
            prefs[Keys.TIME_WIN_START] = next.timeWindowStartMin
            prefs[Keys.TIME_WIN_END] = next.timeWindowEndMin
            prefs[Keys.WEEKDAYS_MASK] = next.weekdaysMask
            prefs[Keys.DATE_EXCLUSIONS] = AppJson.encodeToString(next.dateExclusions)
            prefs[Keys.NIGHT_ENABLED] = next.nightModeEnabled
            prefs[Keys.NIGHT_START] = next.nightStartMin
            prefs[Keys.NIGHT_END] = next.nightEndMin
            prefs[Keys.NIGHT_RED_TINT] = next.nightRedTintEnabled
            prefs[Keys.USE_SYSTEM_BRIGHTNESS] = next.useSystemBrightness
            prefs[Keys.STANDBY_BRIGHTNESS] = next.standbyBrightness.coerceIn(0.01f, 1.0f)
            prefs[Keys.NIGHT_BRIGHTNESS] = next.nightBrightness.coerceIn(0.01f, 1.0f)
            prefs[Keys.HUB_BRIGHTNESS] = next.hubBrightness.coerceIn(0.05f, 1.0f)
            prefs[Keys.PIXEL_SHIFT_ENABLED] = next.pixelShiftEnabled
            prefs[Keys.PIXEL_SHIFT_MAX_PX] = next.pixelShiftMaxPx.coerceIn(2f, 24f)
            prefs[Keys.PIXEL_SHIFT_INTERVAL] = next.pixelShiftIntervalSec.coerceIn(15, 300)
            prefs[Keys.PRESENCE_MODE] = next.presenceMode
            prefs[Keys.DARK_LUX] = next.darkThresholdLux.coerceIn(0.5f, 100f)
            prefs[Keys.LUX_HYST] = next.luxHysteresis.coerceIn(0.5f, 30f)
            prefs[Keys.CAMERA_POLL_SEC] = next.cameraPollIntervalSec.coerceIn(2, 60)
            prefs[Keys.SLEEP_DELAY_SEC] = next.sleepDelaySec.coerceIn(5, 300)
            prefs[Keys.NIGHT_BLACKOUT] = next.nightBlackoutEnabled
            prefs[Keys.TAP_TO_WAKE] = next.tapToWakeEnabled
            prefs[Keys.AUTO_RETURN_SEC] = next.autoReturnSec.coerceIn(5, 300)
            prefs[Keys.WEATHER_ENABLED] = next.weatherEnabled
            prefs[Keys.WEATHER_AUTO_LOC] = next.weatherAutoLocation
            prefs[Keys.WEATHER_CITY] = next.weatherCity
            prefs[Keys.WEATHER_LAT] = next.weatherLat
            prefs[Keys.WEATHER_LON] = next.weatherLon
        }
    }
}
