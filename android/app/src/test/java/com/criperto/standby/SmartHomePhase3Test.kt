package com.criperto.standby

import com.criperto.standby.core.AppJson
import com.criperto.standby.smarthome.CoalescingCommandThrottler
import com.criperto.standby.smarthome.SmartDeviceStateDto
import com.criperto.standby.smarthome.SmartHomeStore
import com.criperto.standby.smarthome.SmartSceneFavorite
import com.criperto.standby.smarthome.WizColorMath
import com.criperto.standby.smarthome.WizDeviceConfig
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.delay
import kotlinx.coroutines.runBlocking
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import java.util.concurrent.atomic.AtomicInteger

class SmartHomePhase3Test {

    @Test
    fun wizColorMathHsvAndHexRoundTrip() {
        val red = WizColorMath.hsvToRgb(0f, 1f, 1f)
        assertEquals(Triple(255, 0, 0), red)

        val green = WizColorMath.hsvToRgb(120f, 1f, 1f)
        assertEquals(Triple(0, 255, 0), green)

        val blue = WizColorMath.hsvToRgb(240f, 1f, 1f)
        assertEquals(Triple(0, 0, 255), blue)

        val white = WizColorMath.hsvToRgb(180f, 0f, 1f)
        assertEquals(Triple(255, 255, 255), white)

        val hex = WizColorMath.rgbToHex(18, 171, 239)
        assertEquals("#12abef", hex)
        assertEquals(Triple(18, 171, 239), WizColorMath.parseHexRgb(hex))
        assertEquals(Triple(18, 171, 239), WizColorMath.parseHexRgb("12ABEF"))
        assertNull(WizColorMath.parseHexRgb("#12"))
    }

    @Test
    fun smartHomeStoreSerializesDevicesAndFavorites() {
        val dev = WizDeviceConfig(
            id = "a8bb50010203",
            name = "Velador RGB",
            ip = "192.168.0.44",
            port = 38899,
            mac = "a8bb50010203",
            room = "Escritorio",
        )
        val fav = SmartSceneFavorite(
            id = "fav_custom",
            name = "Gamer Violeta",
            deviceId = dev.id,
            actionType = "rgb",
            r = 160,
            g = 32,
            b = 240,
            brightness = 90,
        )
        val store = SmartHomeStore(
            devices = listOf(dev),
            favorites = SmartHomeStore.defaultFavorites() + fav,
        )
        val json = AppJson.encodeToString(store)
        val decoded = AppJson.decodeFromString<SmartHomeStore>(json)
        assertEquals(1, decoded.devices.size)
        assertEquals("192.168.0.44", decoded.devices[0].ip)
        assertTrue(decoded.favorites.any { it.name == "Gamer Violeta" && it.r == 160 })

        val stateOn = SmartDeviceStateDto(isOn = true, r = 255, g = 128, b = 0)
        assertEquals("#ff8000", WizColorMath.stateToHex(stateOn))
        val stateOff = SmartDeviceStateDto(isOn = false, r = 255, g = 128, b = 0)
        assertEquals("#485262", WizColorMath.stateToHex(stateOff))

        // Firmware residual RGB must NOT override active 'Luz diurna' (sceneId = 12)
        val stateDaylightWithResidualPink = SmartDeviceStateDto(
            isOn = true,
            sceneId = 12,
            r = 255,
            g = 0,
            b = 180,
        )
        assertEquals("#EEF6FF", WizColorMath.stateToHex(stateDaylightWithResidualPink))
        assertEquals("Luz diurna", WizColorMath.stateModeLabel(stateDaylightWithResidualPink))
    }

    @Test
    fun coalescingCommandThrottlerCoalescesBurstUpdates() = runBlocking {
        val scope = CoroutineScope(SupervisorJob() + Dispatchers.Default)
        val throttler = CoalescingCommandThrottler(scope = scope, minIntervalMs = 60L)
        val callCount = AtomicInteger(0)
        val lastValue = AtomicInteger(-1)

        // Rapid burst of 10 slider steps within a few ms
        for (i in 1..10) {
            val v = i * 10
            throttler.submit("dim:bulb1") {
                callCount.incrementAndGet()
                lastValue.set(v)
            }
        }
        delay(160L)
        throttler.cancelAll()

        // First call fires immediately, intermediate 8 are coalesced into 1 trailing call (total 2)
        assertTrue("Expected <= 3 UDP calls for burst of 10, got ${callCount.get()}", callCount.get() in 1..3)
        assertEquals(100, lastValue.get())
        assertNotNull(throttler)
    }

    @Test
    fun homeAssistantStatesParserHandlesLightAcTvAndSwitch() {
        val sampleHaJson = """
            [
              {
                "entity_id": "light.tira_escritorio",
                "state": "on",
                "attributes": {
                  "friendly_name": "Tira LED Escritorio",
                  "brightness": 204,
                  "rgb_color": [64, 224, 208]
                }
              },
              {
                "entity_id": "climate.aire_living",
                "state": "cool",
                "attributes": {
                  "friendly_name": "Aire Living",
                  "temperature": 23,
                  "current_temperature": 26.5,
                  "hvac_mode": "cool"
                }
              },
              {
                "entity_id": "media_player.smart_tv_lg",
                "state": "playing",
                "attributes": {
                  "friendly_name": "Smart TV LG",
                  "volume_level": 0.45,
                  "is_volume_muted": false,
                  "source": "YouTube"
                }
              },
              {
                "entity_id": "switch.cafetera",
                "state": "off",
                "attributes": {
                  "friendly_name": "Cafetera"
                }
              }
            ]
        """.trimIndent()

        val parsed = com.criperto.standby.smarthome.MultiBrandDrivers.parseHomeAssistantStatesJson(
            rawJsonArray = sampleHaJson,
            serverUrl = "http://192.168.100.50:8123",
        )
        assertEquals(4, parsed.size)

        val (lightCfg, lightSt) = parsed.first { it.first.entityId == "light.tira_escritorio" }
        assertEquals("light", lightCfg.category)
        assertEquals("home_assistant", lightCfg.driver)
        assertTrue(lightSt.isOn)
        assertEquals(80, lightSt.brightness)
        assertEquals(64, lightSt.r)

        val (acCfg, acSt) = parsed.first { it.first.entityId == "climate.aire_living" }
        assertEquals("ac", acCfg.category)
        assertTrue(acSt.isOn)
        assertEquals(23, acSt.targetTempC)
        assertEquals("cool", acSt.hvacMode)

        val (tvCfg, tvSt) = parsed.first { it.first.entityId == "media_player.smart_tv_lg" }
        assertEquals("tv", tvCfg.category)
        assertTrue(tvSt.isOn)
        assertEquals(45, tvSt.volumePct)
        assertEquals("YouTube", tvSt.tvSource)

        val (swCfg, swSt) = parsed.first { it.first.entityId == "switch.cafetera" }
        assertEquals("switch", swCfg.category)
        assertEquals(false, swSt.isOn)
    }
}
