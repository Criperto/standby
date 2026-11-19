package com.criperto.standby.renderer

import android.content.Context
import android.graphics.Bitmap
import android.graphics.BitmapFactory
import android.graphics.Canvas
import android.graphics.ColorMatrix
import android.graphics.ColorMatrixColorFilter
import android.graphics.LinearGradient
import android.graphics.Paint
import android.graphics.Shader
import android.graphics.Typeface
import android.net.Uri
import android.os.Build
import androidx.compose.material3.ColorScheme
import androidx.compose.ui.graphics.toArgb
import com.caverock.androidsvg.SVG
import com.criperto.standby.core.MaterialYouPaletteDto
import com.criperto.standby.core.SceneObject
import java.io.File
import kotlin.math.roundToInt

fun ColorScheme.toPaletteDto(): MaterialYouPaletteDto {
    return MaterialYouPaletteDto(
        primary = primary.toArgb().toLong() and 0xFFFFFFFFL,
        onPrimary = onPrimary.toArgb().toLong() and 0xFFFFFFFFL,
        primaryContainer = primaryContainer.toArgb().toLong() and 0xFFFFFFFFL,
        onPrimaryContainer = onPrimaryContainer.toArgb().toLong() and 0xFFFFFFFFL,
        secondary = secondary.toArgb().toLong() and 0xFFFFFFFFL,
        onSecondary = onSecondary.toArgb().toLong() and 0xFFFFFFFFL,
        secondaryContainer = secondaryContainer.toArgb().toLong() and 0xFFFFFFFFL,
        tertiary = tertiary.toArgb().toLong() and 0xFFFFFFFFL,
        tertiaryContainer = tertiaryContainer.toArgb().toLong() and 0xFFFFFFFFL,
        surface = surface.toArgb().toLong() and 0xFFFFFFFFL,
        surfaceVariant = surfaceVariant.toArgb().toLong() and 0xFFFFFFFFL,
        surfaceContainer = surfaceContainer.toArgb().toLong() and 0xFFFFFFFFL,
        onSurface = onSurface.toArgb().toLong() and 0xFFFFFFFFL,
        onSurfaceVariant = onSurfaceVariant.toArgb().toLong() and 0xFFFFFFFFL,
        background = background.toArgb().toLong() and 0xFFFFFFFFL,
        outline = outline.toArgb().toLong() and 0xFFFFFFFFL,
        error = error.toArgb().toLong() and 0xFFFFFFFFL,
    )
}

data class BackdropBitmaps(
    val sharp: Bitmap,
    val blurred: Bitmap,
)

class SceneAssetCache(private val context: Context) {
    private val svgCache = mutableMapOf<String, SVG?>()
    private val typefaceCache = mutableMapOf<String, Typeface>()
    private var cachedBackdropKey: String = ""
    private var cachedBackdrop: BackdropBitmaps? = null

    fun resolveBackdrop(
        mode: String,
        wallpaperUri: String?,
        brightness: Float,
        blurRadius: Float,
        widthPx: Int,
        heightPx: Int,
        palette: MaterialYouPaletteDto,
    ): BackdropBitmaps {
        val w = widthPx.coerceIn(64, 2400)
        val h = heightPx.coerceIn(64, 2400)
        val key = "$mode|$wallpaperUri|${(brightness * 100).roundToInt()}|${(blurRadius * 10).roundToInt()}|${w}x$h|${palette.primary}"
        cachedBackdrop?.let {
            if (cachedBackdropKey == key) return it
        }

        val sharp = Bitmap.createBitmap(w, h, Bitmap.Config.ARGB_8888)
        val canvas = Canvas(sharp)

        if (mode == "black") {
            canvas.drawColor(android.graphics.Color.BLACK)
        } else {
            val loadedWp = wallpaperUri?.let { loadScaledBitmap(it, w, h) }
            if (loadedWp != null) {
                val paint = Paint(Paint.ANTI_ALIAS_FLAG or Paint.FILTER_BITMAP_FLAG)
                val b = brightness.coerceIn(0.05f, 1.0f)
                paint.colorFilter = ColorMatrixColorFilter(
                    ColorMatrix().apply { setScale(b, b, b, 1f) }
                )
                drawCenterCrop(canvas, loadedWp, w, h, paint)
                loadedWp.recycle()
            } else {
                // Built-in rich ambient mesh fallback derived from Material You Monet palette
                val c1 = darkenColor(palette.primaryContainer.toInt(), brightness.coerceIn(0.15f, 1f))
                val c2 = darkenColor(palette.tertiaryContainer.toInt(), brightness.coerceIn(0.15f, 1f))
                val c3 = darkenColor(palette.surface.toInt(), brightness.coerceIn(0.15f, 1f))
                val p = Paint(Paint.ANTI_ALIAS_FLAG).apply {
                    shader = LinearGradient(
                        0f,
                        0f,
                        w.toFloat(),
                        h.toFloat(),
                        intArrayOf(c3, c1, c2, c3),
                        floatArrayOf(0f, 0.35f, 0.7f, 1f),
                        Shader.TileMode.CLAMP,
                    )
                }
                canvas.drawRect(0f, 0f, w.toFloat(), h.toFloat(), p)
                // Soft Monet orbs so Liquid Glass refraction looks vividly dimensional even without custom photo
                val orb1 = Paint(Paint.ANTI_ALIAS_FLAG).apply {
                    color = darkenColor(palette.primary.toInt(), brightness * 0.55f)
                }
                val orb2 = Paint(Paint.ANTI_ALIAS_FLAG).apply {
                    color = darkenColor(palette.tertiary.toInt(), brightness * 0.45f)
                }
                canvas.drawCircle(w * 0.28f, h * 0.36f, h * 0.34f, orb1)
                canvas.drawCircle(w * 0.72f, h * 0.62f, h * 0.38f, orb2)
            }
        }

        val effectiveSharp = if (blurRadius > 0.5f && mode == "wallpaper") {
            val factor = (blurRadius / 6f).roundToInt().coerceIn(2, 8)
            val blurredBg = downscaleAndBlurBitmap(sharp, scaleFactor = factor, blurPasses = 3)
            val scaledBack = Bitmap.createScaledBitmap(blurredBg, w, h, true)
            blurredBg.recycle()
            sharp.recycle()
            scaledBack
        } else {
            sharp
        }

        val blurredForGlass = downscaleAndBlurBitmap(effectiveSharp, scaleFactor = 4, blurPasses = 3)
        val res = BackdropBitmaps(effectiveSharp, blurredForGlass)
        cachedBackdropKey = key
        cachedBackdrop = res
        return res
    }

    private fun loadScaledBitmap(uriOrPath: String, targetW: Int, targetH: Int): Bitmap? {
        return runCatching {
            val opts = BitmapFactory.Options().apply { inJustDecodeBounds = true }
            if (uriOrPath.startsWith("content://") || uriOrPath.startsWith("file://")) {
                val uri = Uri.parse(uriOrPath)
                context.contentResolver.openInputStream(uri)?.use {
                    BitmapFactory.decodeStream(it, null, opts)
                }
                opts.inSampleSize = calcInSampleSize(opts.outWidth, opts.outHeight, targetW, targetH)
                opts.inJustDecodeBounds = false
                context.contentResolver.openInputStream(uri)?.use {
                    BitmapFactory.decodeStream(it, null, opts)
                }
            } else {
                BitmapFactory.decodeFile(uriOrPath, opts)
                opts.inSampleSize = calcInSampleSize(opts.outWidth, opts.outHeight, targetW, targetH)
                opts.inJustDecodeBounds = false
                BitmapFactory.decodeFile(uriOrPath, opts)
            }
        }.getOrNull()
    }

    private fun calcInSampleSize(srcW: Int, srcH: Int, reqW: Int, reqH: Int): Int {
        var inSampleSize = 1
        if (srcH > reqH || srcW > reqW) {
            val halfH = srcH / 2
            val halfW = srcW / 2
            while ((halfH / inSampleSize) >= reqH && (halfW / inSampleSize) >= reqW) {
                inSampleSize *= 2
            }
        }
        return inSampleSize
    }

    private fun drawCenterCrop(canvas: Canvas, bmp: Bitmap, dstW: Int, dstH: Int, paint: Paint) {
        val srcW = bmp.width.toFloat()
        val srcH = bmp.height.toFloat()
        if (srcW <= 0f || srcH <= 0f) return
        val scale = maxOf(dstW / srcW, dstH / srcH)
        val scaledW = srcW * scale
        val scaledH = srcH * scale
        val dx = (dstW - scaledW) * 0.5f
        val dy = (dstH - scaledH) * 0.5f
        val matrix = android.graphics.Matrix().apply {
            postScale(scale, scale)
            postTranslate(dx, dy)
        }
        canvas.drawBitmap(bmp, matrix, paint)
    }

    private fun darkenColor(argb: Int, factor: Float): Int {
        val a = (argb ushr 24) and 0xFF
        val r = (((argb ushr 16) and 0xFF) * factor).roundToInt().coerceIn(0, 255)
        val g = (((argb ushr 8) and 0xFF) * factor).roundToInt().coerceIn(0, 255)
        val b = ((argb and 0xFF) * factor).roundToInt().coerceIn(0, 255)
        return (a shl 24) or (r shl 16) or (g shl 8) or b
    }

    fun resolveTypeface(obj: SceneObject): Typeface {
        val key = "${obj.customFontFamily}|${obj.fontFileName}|${obj.fontSourceId}|${obj.fontWeight}|${obj.fontItalic}"
        typefaceCache[key]?.let { return it }

        val customFile = obj.fontFileName?.takeIf { it.isNotBlank() }?.let { fn ->
            File(context.filesDir, "fonts/imported/$fn").takeIf { it.exists() }
                ?: File(context.filesDir, "fonts/downloaded/$fn").takeIf { it.exists() }
        } ?: obj.fontSourceId?.takeIf { it.isNotBlank() }?.let { fid ->
            File(context.filesDir, "fonts/downloaded/${fid}.ttf").takeIf { it.exists() }
        }

        val base = if (customFile != null) {
            runCatching { Typeface.createFromFile(customFile) }.getOrNull() ?: Typeface.DEFAULT
        } else {
            val fam = obj.customFontFamily.takeIf { it.isNotBlank() } ?: "sans-serif"
            val style = when {
                obj.fontWeight >= 600 && obj.fontItalic -> Typeface.BOLD_ITALIC
                obj.fontWeight >= 600 -> Typeface.BOLD
                obj.fontItalic -> Typeface.ITALIC
                else -> Typeface.NORMAL
            }
            Typeface.create(fam, style)
        }

        val finalTf = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.P) {
            Typeface.create(base, obj.fontWeight.coerceIn(100, 900), obj.fontItalic)
        } else {
            base
        }
        typefaceCache[key] = finalTf
        return finalTf
    }

    fun loadSvgAsset(assetName: String): SVG? {
        svgCache[assetName]?.let { return it }
        val file = File(context.filesDir, "svgs/$assetName")
        val svg = if (file.exists()) {
            runCatching { file.inputStream().use { SVG.getFromInputStream(it) } }.getOrNull()
        } else null
        svgCache[assetName] = svg
        return svg
    }
}
