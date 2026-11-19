package com.criperto.standby

import com.criperto.standby.core.AppJson
import com.criperto.standby.core.AppSettings
import com.criperto.standby.core.DateRangeDto
import com.criperto.standby.core.GradientSpec
import com.criperto.standby.core.SceneObject
import com.criperto.standby.core.StandByDecisionDto
import com.criperto.standby.core.StandByPreset
import com.criperto.standby.core.TriggerSnapshotDto
import com.criperto.standby.renderer.CutoutCacheMeta
import com.criperto.standby.standby.BurnInController
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test
import kotlin.math.abs

class StandByPhase2Test {

    @Test
    fun triggerSnapshotSerializesLocalYyyymmddMatchingRust() {
        val snap = TriggerSnapshotDto(
            isCharging = true,
            chargeSource = "ac",
            isLandscape = true,
            nowEpochMs = 1_760_000_000_000L,
            localMinuteOfDay = 1380,
            localWeekdayMon0 = 2,
            localYyyyMmDd = 20261118L,
            ambientLux = 4.5f,
            faceDetected = false,
            lastPresenceEpochMs = 1_759_999_990_000L,
            hubOpen = false,
            manualLaunch = false,
            prevDarkRoom = true,
        )
        val encoded = AppJson.encodeToString(snap)
        assertTrue(
            "Expected Rust camelCase key 'localYyyymmdd' in: $encoded",
            encoded.contains("\"localYyyymmdd\": 20261118") || encoded.contains("\"localYyyymmdd\":20261118"),
        )
        val decoded = AppJson.decodeFromString<TriggerSnapshotDto>(encoded)
        assertEquals(20261118L, decoded.localYyyyMmDd)
        assertEquals("ac", decoded.chargeSource)
    }

    @Test
    fun settingsConvertsToTriggerRulesCleanly() {
        val settings = AppSettings(
            triggersEnabled = true,
            chargingReq = "wireless_only",
            orientationReq = "any_orientation",
            timeWindowEnabled = true,
            timeWindowStartMin = 600,
            timeWindowEndMin = 1200,
            weekdaysMask = 0x1F,
            dateExclusions = listOf(DateRangeDto(20261224L, 20261226L, true)),
            nightModeEnabled = true,
            nightStartMin = 1380,
            nightEndMin = 420,
            useSystemBrightness = false,
            standbyBrightness = 0.30f,
            nightBrightness = 0.05f,
            hubBrightness = 0.65f,
            presenceMode = "face_or_light",
            darkThresholdLux = 10f,
            luxHysteresis = 4f,
            sleepDelaySec = 30,
            cameraPollIntervalSec = 6,
            nightBlackoutEnabled = true,
        )
        val rules = settings.toTriggerRulesDto()
        assertEquals("wireless_only", rules.chargingReq)
        assertEquals("any_orientation", rules.orientationReq)
        assertEquals(1, rules.timeWindows.size)
        assertEquals(600, rules.timeWindows[0].startMinuteOfDay)
        assertEquals(1200, rules.timeWindows[0].endMinuteOfDay)
        assertEquals(0x1F, rules.weekdaysMask)
        assertEquals(1, rules.dateRanges.size)
        assertEquals("face_or_light", rules.presenceMode)

        val encoded = AppJson.encodeToString(rules)
        assertTrue(encoded.contains("\"startYyyymmdd\""))
        assertTrue(encoded.contains("\"endYyyymmdd\""))
    }

    @Test
    fun burnInControllerRespectsBoundsAndDisabledState() {
        val off = BurnInController.computeShift(stepIndex = 42L, enabled = false, maxPx = 10f)
        assertEquals(0f, off.xPx, 0.0001f)
        assertEquals(0f, off.yPx, 0.0001f)

        val maxPx = 8f
        for (step in 0L..200L) {
            val shift = BurnInController.computeShift(stepIndex = step, enabled = true, maxPx = maxPx)
            assertTrue("xPx=${shift.xPx} exceeded maxPx", abs(shift.xPx) <= maxPx + 0.001f)
            assertTrue("yPx=${shift.yPx} exceeded maxPx", abs(shift.yPx) <= maxPx + 0.001f)
        }
    }

    @Test
    fun presetAndDecisionRoundTrip() {
        val obj = SceneObject(
            id = "clock_1",
            objType = "clock_hours",
            liquidGlassEnabled = true,
            shaderId = "liquid_glass",
            gradient = GradientSpec(enabled = true, gradType = "conic"),
        )
        val preset = StandByPreset(name = "Test", objects = listOf(obj))
        val encoded = AppJson.encodeToString(preset)
        val decoded = AppJson.decodeFromString<StandByPreset>(encoded)
        assertEquals("Test", decoded.name)
        assertEquals(1, decoded.objects.size)
        assertTrue(decoded.objects[0].liquidGlassEnabled)
        assertEquals("liquid_glass", decoded.objects[0].shaderId)

        val decision = AppJson.decodeFromString<StandByDecisionDto>(
            """{"shouldBeActive":true,"displayMode":"night_active","shouldPollCamera":false,"targetBrightness":0.06,"extraDimAlpha":0.2,"isDarkRoom":true,"reason":"ok"}"""
        )
        assertTrue(decision.shouldBeActive)
        assertEquals("night_active", decision.displayMode)
        assertFalse(decision.shouldPollCamera)

        val meta = CutoutCacheMeta(
            key = "abc123",
            wallpaperUri = "content://wp/1",
            modelName = "birefnet-general-lite",
            width = 1080,
            height = 720,
            occupancyGrid = listOf(1u, 2u, 3u),
        )
        val metaDecoded = AppJson.decodeFromString<CutoutCacheMeta>(AppJson.encodeToString(meta))
        assertEquals("abc123", metaDecoded.key)
        assertEquals(listOf(1u, 2u, 3u), metaDecoded.occupancyGrid)
    }
}
