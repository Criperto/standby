package com.criperto.standby.renderer

import android.content.Context
import android.graphics.Bitmap
import android.graphics.BitmapShader
import android.graphics.Canvas
import android.graphics.Paint
import android.graphics.RuntimeShader
import android.graphics.Shader
import android.os.Build
import androidx.annotation.RequiresApi
import com.criperto.standby.R
import com.criperto.standby.core.GradientSpec
import com.criperto.standby.core.MaterialYouPaletteDto
import com.criperto.standby.core.SceneObject
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import java.io.File
import kotlin.math.cos
import kotlin.math.sin

data class ShaderDescriptor(
    val id: String,
    val displayName: String,
    val isBuiltIn: Boolean,
    val source: String,
    val compileError: String? = null,
)

class ShaderRegistry(private val context: Context) {
    private val customDir = File(context.filesDir, "shaders").apply { mkdirs() }
    private val sources = mutableMapOf<String, ShaderDescriptor>()

    private val _shaders = MutableStateFlow<List<ShaderDescriptor>>(emptyList())
    val shadersFlow: StateFlow<List<ShaderDescriptor>> = _shaders.asStateFlow()

    init {
        reloadAll()
    }

    fun reloadAll() {
        sources.clear()
        registerBuiltIn("liquid_glass", "Liquid Glass (Refracción)", R.raw.liquid_glass)
        registerBuiltIn("gradient_fill", "Gradiente 4 Modos", R.raw.gradient_fill)
        registerBuiltIn("mask_feather", "Difuminado de Máscara", R.raw.mask_feather)

        customDir.listFiles { f -> f.isFile && (f.extension == "agsl" || f.extension == "sksl") }
            ?.sortedBy { it.nameWithoutExtension }
            ?.forEach { f ->
                val id = f.nameWithoutExtension
                val src = runCatching { f.readText() }.getOrDefault("")
                val err = validateSource(src)
                sources[id] = ShaderDescriptor(
                    id = id,
                    displayName = id.replace('_', ' ').replaceFirstChar { it.uppercase() },
                    isBuiltIn = false,
                    source = src,
                    compileError = err,
                )
            }
        _shaders.value = sources.values.toList()
    }

    private fun registerBuiltIn(id: String, displayName: String, rawResId: Int) {
        val src = runCatching {
            context.resources.openRawResource(rawResId).bufferedReader().use { it.readText() }
        }.getOrDefault("")
        val err = validateSource(src)
        sources[id] = ShaderDescriptor(
            id = id,
            displayName = displayName,
            isBuiltIn = true,
            source = src,
            compileError = err,
        )
    }

    fun importCustomShader(name: String, agslSource: String): Result<ShaderDescriptor> {
        val safeId = name.lowercase().replace(Regex("[^a-z0-9_]+"), "_").trim('_')
            .ifEmpty { "custom_shader_${System.currentTimeMillis() / 1000}" }
        val err = validateSource(agslSource)
        if (err != null) {
            return Result.failure(IllegalArgumentException("Error de compilación AGSL: $err"))
        }
        File(customDir, "$safeId.agsl").writeText(agslSource)
        reloadAll()
        return Result.success(sources[safeId]!!)
    }

    fun deleteCustomShader(id: String): Boolean {
        if (sources[id]?.isBuiltIn == true) return false
        val deleted = File(customDir, "$id.agsl").delete()
        reloadAll()
        return deleted
    }

    fun getSource(id: String): String? = sources[id]?.source

    fun validateSource(src: String): String? {
        if (src.isBlank()) return "Shader vacío"
        if (Build.VERSION.SDK_INT < Build.VERSION_CODES.TIRAMISU) return null
        return try {
            RuntimeShader(src)
            null
        } catch (t: Throwable) {
            t.message ?: "Error compilando AGSL"
        }
    }

    @RequiresApi(Build.VERSION_CODES.TIRAMISU)
    fun buildLiquidGlassShader(
        obj: SceneObject,
        itemW: Float,
        itemH: Float,
        centerX: Float,
        centerY: Float,
        rotDeg: Float,
        backdropSharp: Bitmap,
        backdropBlur: Bitmap,
        maskBitmap: Bitmap?,
        maskSoftBitmap: Bitmap?,
        tintBitmap: Bitmap?,
        tintArgb: Int,
        shapeMode: Float,
        opacity: Float,
    ): RuntimeShader? {
        val src = getSource("liquid_glass") ?: return null
        return runCatching {
            val shader = RuntimeShader(src)
            val sharpShader = BitmapShader(backdropSharp, Shader.TileMode.CLAMP, Shader.TileMode.CLAMP)
            val blurShader = BitmapShader(backdropBlur, Shader.TileMode.CLAMP, Shader.TileMode.CLAMP)
            val fallback1px = getTransparent1PxShader()
            val mSrc = maskBitmap?.let { BitmapShader(it, Shader.TileMode.CLAMP, Shader.TileMode.CLAMP) } ?: fallback1px
            val mSoft = maskSoftBitmap?.let { BitmapShader(it, Shader.TileMode.CLAMP, Shader.TileMode.CLAMP) } ?: mSrc
            val tSrc = tintBitmap?.let { BitmapShader(it, Shader.TileMode.CLAMP, Shader.TileMode.CLAMP) } ?: fallback1px

            shader.setInputShader("src", sharpShader)
            shader.setInputShader("srcBlur", blurShader)
            shader.setInputShader("maskSrc", mSrc)
            shader.setInputShader("maskSoft", mSoft)
            shader.setInputShader("tintSrc", tSrc)

            val safeW = itemW.coerceAtLeast(2f)
            val safeH = itemH.coerceAtLeast(2f)
            shader.setFloatUniform("shapeMode", shapeMode)
            shader.setFloatUniform("rotationRad", Math.toRadians(rotDeg.toDouble()).toFloat())
            shader.setFloatUniform("bezel", obj.liquidBezel.toFloat().coerceIn(1f, 80f))
            shader.setFloatUniform("itemSize", safeW, safeH)
            shader.setFloatUniform("screenSize", backdropSharp.width.toFloat(), backdropSharp.height.toFloat())
            shader.setFloatUniform("centerPos", centerX, centerY)

            val lightRad = Math.toRadians(obj.liquidLightAngle)
            shader.setFloatUniform("lightDir", cos(lightRad).toFloat(), sin(lightRad).toFloat())

            val maxRad = minOf(safeW, safeH) * 0.5f
            val rTL = (if (obj.radiusIndependent) obj.radiusTopLeft else obj.radiusUniform).toFloat().coerceIn(0f, maxRad)
            val rTR = (if (obj.radiusIndependent) obj.radiusTopRight else obj.radiusUniform).toFloat().coerceIn(0f, maxRad)
            val rBR = (if (obj.radiusIndependent) obj.radiusBottomRight else obj.radiusUniform).toFloat().coerceIn(0f, maxRad)
            val rBL = (if (obj.radiusIndependent) obj.radiusBottomLeft else obj.radiusUniform).toFloat().coerceIn(0f, maxRad)
            shader.setFloatUniform("cornerRadii", rTL, rTR, rBR, rBL)

            val tr = ((tintArgb ushr 16) and 0xFF) / 255f
            val tg = ((tintArgb ushr 8) and 0xFF) / 255f
            val tb = (tintArgb and 0xFF) / 255f
            val ta = ((tintArgb ushr 24) and 0xFF) / 255f
            shader.setFloatUniform("tintColor", tr, tg, tb, ta)

            shader.setFloatUniform("ior", obj.liquidIor.toFloat().coerceIn(1.0f, 3.0f))
            shader.setFloatUniform("thickness", obj.liquidThickness.toFloat().coerceIn(0f, 100f))
            shader.setFloatUniform("dispersion", obj.liquidDispersion.toFloat().coerceIn(0f, 0.25f))
            shader.setFloatUniform("frost", obj.liquidFrost.toFloat().coerceIn(0f, 20f))
            shader.setFloatUniform("saturation", obj.liquidSaturation.toFloat().coerceIn(0f, 2.5f))
            shader.setFloatUniform("tintAmount", obj.liquidTintAmount.toFloat().coerceIn(0f, 1f))
            shader.setFloatUniform("rim", obj.liquidRim.toFloat().coerceIn(0f, 1.5f))
            shader.setFloatUniform("useTintGradient", if (tintBitmap != null) 1f else 0f)

            when (shapeMode.toInt()) {
                2 -> shader.setFloatUniform(
                    "shapeParams",
                    obj.circleStart.toFloat(),
                    obj.circleSweep.toFloat(),
                    obj.circleRatio.toFloat(),
                    0f
                )
                3 -> shader.setFloatUniform(
                    "shapeParams",
                    obj.polygonSides.toFloat(),
                    obj.polygonRadius.toFloat(),
                    0f,
                    0f
                )
                4 -> shader.setFloatUniform(
                    "shapeParams",
                    obj.starPoints.toFloat(),
                    obj.starRatio.toFloat(),
                    obj.starRadius.toFloat(),
                    0f
                )
                else -> shader.setFloatUniform("shapeParams", 0f, 360f, 0f, 0f)
            }
            shader.setFloatUniform("opacity", opacity.coerceIn(0f, 1f))
            shader
        }.getOrNull()
    }

    @RequiresApi(Build.VERSION_CODES.TIRAMISU)
    fun buildGradientShader(
        spec: GradientSpec,
        itemW: Float,
        itemH: Float,
        palette: MaterialYouPaletteDto,
        opacity: Float = 1f,
        sourceMask: Bitmap? = null,
    ): RuntimeShader? {
        val src = getSource("gradient_fill") ?: return null
        val sorted = spec.stops.sortedBy { it.pos }.take(8)
        if (sorted.size < 2) return null

        return runCatching {
            val shader = RuntimeShader(src)
            val maskShader = sourceMask?.let {
                BitmapShader(it, Shader.TileMode.CLAMP, Shader.TileMode.CLAMP)
            } ?: getTransparent1PxShader()
            shader.setInputShader("src", maskShader)
            shader.setFloatUniform("useSourceAlpha", if (sourceMask != null) 1f else 0f)

            val gType = when (spec.gradType.lowercase()) {
                "radial" -> 1f
                "angular" -> 2f
                "diamond" -> 3f
                else -> 0f
            }
            shader.setFloatUniform("gradType", gType)
            shader.setFloatUniform("stopCount", sorted.size.toFloat())
            shader.setFloatUniform("itemSize", itemW.coerceAtLeast(1f), itemH.coerceAtLeast(1f))
            shader.setFloatUniform("p0", spec.p0x.toFloat(), spec.p0y.toFloat())
            shader.setFloatUniform("p1", spec.p1x.toFloat(), spec.p1y.toFloat())
            shader.setFloatUniform("p2", spec.p2x.toFloat(), spec.p2y.toFloat())

            val pos = FloatArray(8) { 1f }
            val cols = Array(8) { floatArrayOf(1f, 1f, 1f, 1f) }
            for (i in sorted.indices) {
                val st = sorted[i]
                pos[i] = st.pos.toFloat().coerceIn(0f, 1f)
                val argb = if (st.isCustom) {
                    parseHexColorInt(st.hex)
                } else {
                    resolvePaletteTokenInt(st.colorChoice, palette)
                }
                cols[i] = floatArrayOf(
                    ((argb ushr 16) and 0xFF) / 255f,
                    ((argb ushr 8) and 0xFF) / 255f,
                    (argb and 0xFF) / 255f,
                    st.alpha.toFloat().coerceIn(0f, 1f) * (((argb ushr 24) and 0xFF) / 255f)
                )
            }
            shader.setFloatUniform("pos03", pos[0], pos[1], pos[2], pos[3])
            shader.setFloatUniform("pos47", pos[4], pos[5], pos[6], pos[7])
            for (i in 0..7) {
                shader.setFloatUniform("c$i", cols[i][0], cols[i][1], cols[i][2], cols[i][3])
            }
            shader.setFloatUniform("opacity", opacity.coerceIn(0f, 1f))
            shader
        }.getOrNull()
    }

    @RequiresApi(Build.VERSION_CODES.TIRAMISU)
    fun buildCustomShader(
        shaderId: String,
        itemW: Float,
        itemH: Float,
        tSeconds: Float,
        backdropSharp: Bitmap,
        params: Map<String, Double>,
    ): RuntimeShader? {
        val src = getSource(shaderId) ?: return null
        return runCatching {
            val shader = RuntimeShader(src)
            if (src.contains("uniform shader src")) {
                shader.setInputShader(
                    "src",
                    BitmapShader(backdropSharp, Shader.TileMode.CLAMP, Shader.TileMode.CLAMP)
                )
            }
            if (src.contains("uniform float2 itemSize")) {
                shader.setFloatUniform("itemSize", itemW.coerceAtLeast(1f), itemH.coerceAtLeast(1f))
            }
            if (src.contains("uniform float uTime")) {
                shader.setFloatUniform("uTime", tSeconds)
            }
            for ((k, v) in params) {
                if (src.contains("uniform float $k")) {
                    shader.setFloatUniform(k, v.toFloat())
                }
            }
            shader
        }.getOrNull()
    }

    private var cached1Px: BitmapShader? = null
    private fun getTransparent1PxShader(): BitmapShader {
        return cached1Px ?: run {
            val bmp = Bitmap.createBitmap(2, 2, Bitmap.Config.ARGB_8888)
            BitmapShader(bmp, Shader.TileMode.CLAMP, Shader.TileMode.CLAMP).also { cached1Px = it }
        }
    }
}

fun parseHexColorInt(hex: String): Int {
    val clean = hex.trim().removePrefix("#")
    return when (clean.length) {
        6 -> (0xFF000000L or (clean.toLongOrNull(16) ?: 0xFFFFFFL)).toInt()
        8 -> (clean.toLongOrNull(16) ?: 0xFFFFFFFFL).toInt()
        else -> 0xFFFFFFFF.toInt()
    }
}

fun resolvePaletteTokenInt(choice: String, p: MaterialYouPaletteDto): Int {
    return when (choice.trim().lowercase()) {
        "white" -> 0xFFFFFFFF.toInt()
        "black" -> 0xFF000000.toInt()
        "transparent" -> 0x00000000
        "primary", "accent", "color4", "color12" -> p.primary.toInt()
        "onprimary", "on_primary" -> p.onPrimary.toInt()
        "primarycontainer", "primary_container", "color5", "color13" -> p.primaryContainer.toInt()
        "onprimarycontainer", "on_primary_container" -> p.onPrimaryContainer.toInt()
        "secondary", "color3", "color11" -> p.secondary.toInt()
        "onsecondary", "on_secondary" -> p.onSecondary.toInt()
        "secondarycontainer", "secondary_container" -> p.secondaryContainer.toInt()
        "tertiary", "color2", "color10" -> p.tertiary.toInt()
        "tertiarycontainer", "tertiary_container", "color6", "color14" -> p.tertiaryContainer.toInt()
        "surface" -> p.surface.toInt()
        "surfacevariant", "surface_variant" -> p.surfaceVariant.toInt()
        "surfacecontainer", "surface_container" -> p.surfaceContainer.toInt()
        "onsurface", "on_surface", "text", "color7", "color15" -> p.onSurface.toInt()
        "onsurfacevariant", "on_surface_variant", "muted" -> p.onSurfaceVariant.toInt()
        "background" -> p.background.toInt()
        "outline", "border", "color8" -> p.outline.toInt()
        "error", "color1", "color9" -> p.error.toInt()
        else -> p.onSurface.toInt()
    }
}

fun downscaleAndBlurBitmap(src: Bitmap, scaleFactor: Int = 4, blurPasses: Int = 2): Bitmap {
    val w = (src.width / scaleFactor).coerceAtLeast(8)
    val h = (src.height / scaleFactor).coerceAtLeast(8)
    var cur = Bitmap.createScaledBitmap(src, w, h, true)
    // Fast separable box-like blur passes on downscaled bitmap (works cleanly on API 31+)
    val paint = Paint(Paint.FILTER_BITMAP_FLAG)
    for (pass in 0 until blurPasses) {
        val half = Bitmap.createScaledBitmap(cur, (w / 2).coerceAtLeast(4), (h / 2).coerceAtLeast(4), true)
        val next = Bitmap.createBitmap(w, h, Bitmap.Config.ARGB_8888)
        Canvas(next).drawBitmap(half, null, android.graphics.Rect(0, 0, w, h), paint)
        half.recycle()
        if (cur !== src) cur.recycle()
        cur = next
    }
    return cur
}
