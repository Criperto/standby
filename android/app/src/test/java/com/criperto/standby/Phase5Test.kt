package com.criperto.standby

import com.criperto.standby.ai.AiModelCatalog
import com.criperto.standby.fonts.FontsourceRepository
import com.criperto.standby.fonts.SystemFontsScanner
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertTrue
import org.junit.Test

class Phase5Test {

    @Test
    fun systemFontScannerNormalizesWeightAndStyleSuffixes() {
        assertEquals("Roboto", SystemFontsScanner.normalizeFamilyName("Roboto-Regular.ttf"))
        assertEquals("Roboto Condensed", SystemFontsScanner.normalizeFamilyName("RobotoCondensed-BoldItalic.ttf"))
        assertEquals("Jet Brains Mono", SystemFontsScanner.normalizeFamilyName("JetBrainsMono-Medium.otf"))
        assertEquals("Space Grotesk", SystemFontsScanner.normalizeFamilyName("Space_Grotesk-SemiBold.ttf"))
    }

    @Test
    fun aiModelCatalogIncludesSegmentationAndYunetPresenceModels() {
        val all = AiModelCatalog.models
        assertTrue(all.size >= 6)

        val birefnet = AiModelCatalog.findById("birefnet-general-lite")
        assertEquals(1024, birefnet.inputSize)
        assertFalse(birefnet.isLowRes)
        assertTrue(birefnet.isSegmentation)

        val u2netp = AiModelCatalog.findById("u2netp")
        assertEquals(320, u2netp.inputSize)
        assertTrue(u2netp.isLowRes)

        val yunet = AiModelCatalog.findById("yunet")
        assertEquals("face_detection_yunet.onnx", yunet.fileName)
        assertFalse(yunet.isSegmentation)
    }

    @Test
    fun fontsourceFeaturedCatalogHasDisplayAndMonospaceClockFonts() {
        val featured = FontsourceRepository.featuredCatalog()
        assertTrue(featured.size >= 15)
        assertNotNull(featured.firstOrNull { it.id == "jetbrains-mono" })
        assertNotNull(featured.firstOrNull { it.id == "space-grotesk" })
        assertNotNull(featured.firstOrNull { it.id == "bebas-neue" })
        assertNotNull(featured.firstOrNull { it.id == "orbitron" })
    }
}
