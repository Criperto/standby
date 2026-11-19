package com.criperto.standby.ai

import ai.onnxruntime.OnnxTensor
import ai.onnxruntime.OrtEnvironment
import ai.onnxruntime.OrtSession
import android.content.Context
import android.graphics.Bitmap
import android.graphics.BitmapFactory
import android.os.Build
import com.criperto.standby.core.AppJson
import com.criperto.standby.renderer.CutoutCacheMeta
import com.criperto.standby.renderer.CutoutDiskCache
import com.criperto.standby.renderer.DepthMaskRuntimeData
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import uniffi.standby_core.computeMaskKey
import uniffi.standby_core.processMaskRgbaFfi
import java.io.File
import java.nio.ByteBuffer
import java.nio.FloatBuffer
import kotlin.math.exp
import kotlin.math.max
import kotlin.math.min
import kotlin.math.roundToInt

object OnnxSegmentationEngine {
    private val mean = floatArrayOf(0.485f, 0.456f, 0.406f)
    private val std = floatArrayOf(0.229f, 0.224f, 0.225f)

    suspend fun generateCutoutAndCache(
        context: Context,
        wallpaperPath: String,
        modelSpec: AiModelSpec,
        invert: Boolean,
        choke: Float,
        smoothRadius: Float,
        onStageUpdate: (String) -> Unit = {},
    ): Result<DepthMaskRuntimeData> = withContext(Dispatchers.Default) {
        runCatching {
            val wpFile = File(wallpaperPath)
            require(wpFile.exists()) { "No existe el archivo de Wallpaper: $wallpaperPath" }

            val modelFile = AiModelCatalog.modelFile(context, modelSpec)
            require(modelFile.exists() && modelFile.length() > 16_384L) {
                "El modelo '${modelSpec.displayName}' aún no está descargado."
            }

            onStageUpdate("Decodificando wallpaper...")
            val origBmp = decodeBoundedBitmap(wpFile, maxDim = 1280)
                ?: error("No se pudo decodificar la imagen del wallpaper")
            val outW = origBmp.width
            val outH = origBmp.height

            onStageUpdate("Ejecutando inferencia ONNX (${modelSpec.displayName})...")
            val rawAlpha = runOnnxAlphaMask(
                modelFile = modelFile,
                sourceBitmap = origBmp,
                inputDim = modelSpec.inputSize,
                outW = outW,
                outH = outH,
            )

            onStageUpdate("Refinando bordes y grilla Smart Depth en Rust...")
            val origRgba = bitmapToRgbaBytes(origBmp)
            val processed = processMaskRgbaFfi(
                alphaBytes = rawAlpha,
                origRgba = origRgba,
                width = outW.toUInt(),
                height = outH.toUInt(),
                invert = invert,
                choke = choke,
                smoothRadius = smoothRadius,
                isLowResModel = modelSpec.isLowRes,
            )

            val cutoutBmp = rgbaBytesToBitmap(processed.rgbaBytes, outW, outH)
            origBmp.recycle()

            onStageUpdate("Guardando caché WebP + grilla 128×72...")
            val key = computeMaskKey(wallpaperPath, modelSpec.id, invert, choke)
            val dir = CutoutDiskCache.cutoutsDir(context)
            val webpFile = File(dir, "$key.webp")
            val metaFile = File(dir, "$key.meta.json")

            webpFile.outputStream().use { out ->
                val format = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.R) {
                    Bitmap.CompressFormat.WEBP_LOSSLESS
                } else {
                    @Suppress("DEPRECATION")
                    Bitmap.CompressFormat.WEBP
                }
                cutoutBmp.compress(format, 95, out)
            }

            val meta = CutoutCacheMeta(
                key = key,
                wallpaperUri = wallpaperPath,
                modelName = modelSpec.id,
                invert = invert,
                choke = choke,
                width = outW,
                height = outH,
                occupancyGrid = processed.occupancyGrid,
            )
            metaFile.writeText(AppJson.encodeToString(meta))
            CutoutDiskCache.writePointerForUri(context, wallpaperPath, key)

            DepthMaskRuntimeData(
                cutoutBitmap = cutoutBmp,
                occupancyGrid = processed.occupancyGrid,
            )
        }
    }

    private fun runOnnxAlphaMask(
        modelFile: File,
        sourceBitmap: Bitmap,
        inputDim: Int,
        outW: Int,
        outH: Int,
    ): ByteArray {
        val env = OrtEnvironment.getEnvironment()
        val opts = OrtSession.SessionOptions().apply {
            setIntraOpNumThreads(2)
            runCatching { addNnapi() }
        }
        return env.createSession(modelFile.absolutePath, opts).use { session ->
            val resized = Bitmap.createScaledBitmap(sourceBitmap, inputDim, inputDim, true)
            val pixels = IntArray(inputDim * inputDim)
            resized.getPixels(pixels, 0, inputDim, 0, 0, inputDim, inputDim)
            if (resized !== sourceBitmap) resized.recycle()

            val plane = inputDim * inputDim
            val chw = FloatArray(3 * plane)
            for (i in 0 until plane) {
                val p = pixels[i]
                val r = ((p ushr 16) and 0xFF) / 255f
                val g = ((p ushr 8) and 0xFF) / 255f
                val b = (p and 0xFF) / 255f
                chw[i] = (r - mean[0]) / std[0]
                chw[plane + i] = (g - mean[1]) / std[1]
                chw[2 * plane + i] = (b - mean[2]) / std[2]
            }

            val inputName = session.inputNames.first()
            val shape = longArrayOf(1L, 3L, inputDim.toLong(), inputDim.toLong())
            val alphaGrid = OnnxTensor.createTensor(env, FloatBuffer.wrap(chw), shape).use { tensor ->
                session.run(mapOf(inputName to tensor)).use { res ->
                    val firstVal = res.first().value
                    extract2dMaskFloats(firstVal.value, inputDim)
                }
            }

            // Min-max normalize (and apply sigmoid if logits outside [0..1])
            var minV = Float.POSITIVE_INFINITY
            var maxV = Float.NEGATIVE_INFINITY
            for (v in alphaGrid) {
                if (v < minV) minV = v
                if (v > maxV) maxV = v
            }
            val needsSigmoid = minV < -0.05f || maxV > 1.05f
            if (needsSigmoid) {
                minV = Float.POSITIVE_INFINITY
                maxV = Float.NEGATIVE_INFINITY
                for (i in alphaGrid.indices) {
                    val s = 1f / (1f + exp(-alphaGrid[i]))
                    alphaGrid[i] = s
                    if (s < minV) minV = s
                    if (s > maxV) maxV = s
                }
            }
            val span = (maxV - minV).coerceAtLeast(1e-5f)
            for (i in alphaGrid.indices) {
                alphaGrid[i] = ((alphaGrid[i] - minV) / span).coerceIn(0f, 1f)
            }

            // Bilinear upsample from inputDim x inputDim -> outW x outH
            val outBytes = ByteArray(outW * outH)
            for (y in 0 until outH) {
                val fy = (y.toFloat() / max(outH - 1, 1)) * (inputDim - 1)
                val y0 = fy.toInt().coerceIn(0, inputDim - 1)
                val y1 = min(y0 + 1, inputDim - 1)
                val wy = fy - y0
                for (x in 0 until outW) {
                    val fx = (x.toFloat() / max(outW - 1, 1)) * (inputDim - 1)
                    val x0 = fx.toInt().coerceIn(0, inputDim - 1)
                    val x1 = min(x0 + 1, inputDim - 1)
                    val wx = fx - x0

                    val v00 = alphaGrid[y0 * inputDim + x0]
                    val v10 = alphaGrid[y0 * inputDim + x1]
                    val v01 = alphaGrid[y1 * inputDim + x0]
                    val v11 = alphaGrid[y1 * inputDim + x1]
                    val top = v00 * (1f - wx) + v10 * wx
                    val bot = v01 * (1f - wx) + v11 * wx
                    val v = top * (1f - wy) + bot * wy
                    outBytes[y * outW + x] = (v * 255f).roundToInt().coerceIn(0, 255).toByte()
                }
            }
            outBytes
        }
    }

    private fun extract2dMaskFloats(rawValue: Any?, dim: Int): FloatArray {
        val out = FloatArray(dim * dim)
        var cursor = 0
        fun flatten(node: Any?) {
            if (cursor >= out.size || node == null) return
            when (node) {
                is FloatArray -> {
                    val len = min(node.size, out.size - cursor)
                    System.arraycopy(node, 0, out, cursor, len)
                    cursor += len
                }
                is Array<*> -> {
                    for (child in node) {
                        if (cursor >= out.size) break
                        flatten(child)
                    }
                }
            }
        }
        flatten(rawValue)
        return out
    }

    private fun decodeBoundedBitmap(file: File, maxDim: Int): Bitmap? {
        val bounds = BitmapFactory.Options().apply { inJustDecodeBounds = true }
        BitmapFactory.decodeFile(file.absolutePath, bounds)
        if (bounds.outWidth <= 0 || bounds.outHeight <= 0) return null
        var sample = 1
        while (bounds.outWidth / sample > maxDim || bounds.outHeight / sample > maxDim) {
            sample *= 2
        }
        val opts = BitmapFactory.Options().apply {
            inSampleSize = sample
            inPreferredConfig = Bitmap.Config.ARGB_8888
        }
        return BitmapFactory.decodeFile(file.absolutePath, opts)
    }

    private fun bitmapToRgbaBytes(bmp: Bitmap): ByteArray {
        val w = bmp.width
        val h = bmp.height
        val pixels = IntArray(w * h)
        bmp.getPixels(pixels, 0, w, 0, 0, w, h)
        val out = ByteArray(w * h * 4)
        for (i in pixels.indices) {
            val p = pixels[i]
            val base = i * 4
            out[base] = ((p ushr 16) and 0xFF).toByte()
            out[base + 1] = ((p ushr 8) and 0xFF).toByte()
            out[base + 2] = (p and 0xFF).toByte()
            out[base + 3] = ((p ushr 24) and 0xFF).toByte()
        }
        return out
    }

    private fun rgbaBytesToBitmap(rgba: ByteArray, w: Int, h: Int): Bitmap {
        val bmp = Bitmap.createBitmap(w, h, Bitmap.Config.ARGB_8888)
        bmp.copyPixelsFromBuffer(ByteBuffer.wrap(rgba))
        return bmp
    }
}
