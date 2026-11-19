package com.criperto.standby.renderer

import android.content.Context
import android.graphics.BitmapFactory
import com.criperto.standby.core.AppJson
import com.criperto.standby.core.StandByPreset
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import kotlinx.serialization.Serializable
import uniffi.standby_core.computeMaskKey
import java.io.File
import java.security.MessageDigest

@Serializable
data class CutoutCacheMeta(
    val key: String,
    val wallpaperUri: String = "",
    val modelName: String = "birefnet-general-lite",
    val invert: Boolean = false,
    val choke: Float = 1.5f,
    val width: Int = 0,
    val height: Int = 0,
    val occupancyGrid: List<UInt> = emptyList(),
)

object CutoutDiskCache {
    fun cutoutsDir(context: Context): File = File(context.filesDir, "cutouts").apply { mkdirs() }

    private fun uriPointerFile(context: Context, wallpaperUri: String): File {
        val digest = MessageDigest.getInstance("SHA-256")
            .digest(wallpaperUri.toByteArray())
            .take(12)
            .joinToString("") { "%02x".format(it) }
        return File(cutoutsDir(context), "uri_$digest.key")
    }

    fun writePointerForUri(context: Context, wallpaperUri: String, key: String) {
        if (wallpaperUri.isBlank() || key.isBlank()) return
        runCatching {
            uriPointerFile(context, wallpaperUri).writeText(key)
        }
    }

    suspend fun loadDepthMaskForPreset(
        context: Context,
        preset: StandByPreset,
    ): DepthMaskRuntimeData? = withContext(Dispatchers.IO) {
        if (preset.background.mode != "wallpaper") return@withContext null
        val uri = preset.background.wallpaperUri?.takeIf { it.isNotBlank() } ?: return@withContext null
        val hasMaskUser = preset.objects.any { it.isMask || it.depthCutoutMode != "none" }
        if (!hasMaskUser) return@withContext null

        val maskObj = preset.objects.firstOrNull { it.isMask }
        val modelName = maskObj?.maskModelName ?: "birefnet-general-lite"
        val invert = maskObj?.invertMask ?: false
        val choke = (maskObj?.maskChoke ?: 1.5).toFloat()

        val exactKey = runCatching { computeMaskKey(uri, modelName, invert, choke) }.getOrNull()
        val resolvedKey = when {
            exactKey != null && File(cutoutsDir(context), "$exactKey.meta.json").exists() -> exactKey
            else -> runCatching { uriPointerFile(context, uri).readText().trim() }.getOrNull()
                ?.takeIf { it.isNotBlank() && File(cutoutsDir(context), "$it.meta.json").exists() }
        } ?: return@withContext null

        val dir = cutoutsDir(context)
        val metaFile = File(dir, "$resolvedKey.meta.json")
        val webpFile = File(dir, "$resolvedKey.webp")
        if (!metaFile.exists() || !webpFile.exists()) return@withContext null

        val meta = runCatching {
            AppJson.decodeFromString<CutoutCacheMeta>(metaFile.readText())
        }.getOrNull() ?: return@withContext null

        val bitmap = runCatching {
            BitmapFactory.decodeFile(webpFile.absolutePath)
        }.getOrNull() ?: return@withContext null

        DepthMaskRuntimeData(
            cutoutBitmap = bitmap,
            occupancyGrid = meta.occupancyGrid,
        )
    }
}
