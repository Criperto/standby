package com.criperto.standby.fonts

import android.content.Context
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.withContext
import kotlinx.serialization.Serializable
import okhttp3.OkHttpClient
import okhttp3.Request
import org.json.JSONArray
import org.json.JSONObject
import java.io.File
import java.util.concurrent.TimeUnit

@Serializable
data class FontsourceFontItem(
    val id: String,
    val family: String,
    val category: String = "sans-serif",
    val weights: List<Int> = listOf(400, 700),
)

class FontsourceRepository(private val context: Context) {
    private val downloadedDir = File(context.filesDir, "fonts/downloaded").apply { mkdirs() }
    private val httpClient = OkHttpClient.Builder()
        .connectTimeout(12, TimeUnit.SECONDS)
        .readTimeout(25, TimeUnit.SECONDS)
        .build()

    private val _catalog = MutableStateFlow(featuredCatalog())
    val catalogFlow: StateFlow<List<FontsourceFontItem>> = _catalog.asStateFlow()

    private val _downloadedIds = MutableStateFlow(scanDownloadedIds())
    val downloadedIdsFlow: StateFlow<Set<String>> = _downloadedIds.asStateFlow()

    fun refreshDownloadedSet(): Set<String> {
        val ids = scanDownloadedIds()
        _downloadedIds.value = ids
        return ids
    }

    private fun scanDownloadedIds(): Set<String> {
        return downloadedDir.listFiles { f -> f.isFile && f.extension.equals("ttf", true) }
            ?.map { it.nameWithoutExtension }
            ?.toSet()
            ?: emptySet()
    }

    suspend fun fetchFullCatalog(): Result<List<FontsourceFontItem>> = withContext(Dispatchers.IO) {
        runCatching {
            val req = Request.Builder()
                .url("https://api.fontsource.org/v1/fonts")
                .build()
            httpClient.newCall(req).execute().use { resp ->
                if (!resp.isSuccessful) error("HTTP ${resp.code} desde api.fontsource.org")
                val body = resp.body?.string().orEmpty()
                val arr = JSONArray(body)
                val list = ArrayList<FontsourceFontItem>(minOf(arr.length(), 400))
                val featured = featuredCatalog().associateBy { it.id }
                list.addAll(featured.values)

                val limit = minOf(arr.length(), 350)
                for (i in 0 until limit) {
                    val obj = arr.optJSONObject(i) ?: continue
                    val id = obj.optString("id")
                    if (id.isBlank() || featured.containsKey(id)) continue
                    val family = obj.optString("family", id)
                    val category = obj.optString("category", "sans-serif")
                    list.add(
                        FontsourceFontItem(
                            id = id,
                            family = family,
                            category = category,
                        )
                    )
                }
                _catalog.value = list
                list
            }
        }
    }

    suspend fun downloadFont(fontId: String, preferredWeight: Int = 700): Result<File> =
        withContext(Dispatchers.IO) {
            runCatching {
                val safeId = fontId.lowercase().trim()
                val dstFile = File(downloadedDir, "$safeId.ttf")
                if (dstFile.exists() && dstFile.length() > 1024L) {
                    refreshDownloadedSet()
                    return@runCatching dstFile
                }

                // 1) Try Fontsource API metadata to resolve exact TTF URL
                val resolvedUrl = resolveTtfUrlFromApi(safeId, preferredWeight)
                    ?: "https://cdn.jsdelivr.net/fontsource/fonts/$safeId@latest/latin-$preferredWeight-normal.ttf"

                val tmpFile = File(downloadedDir, "$safeId.tmp.ttf")
                val downloaded = tryDownloadToFile(resolvedUrl, tmpFile) ||
                    tryDownloadToFile(
                        "https://cdn.jsdelivr.net/fontsource/fonts/$safeId@latest/latin-400-normal.ttf",
                        tmpFile,
                    )

                if (!downloaded || !tmpFile.exists() || tmpFile.length() < 512L) {
                    tmpFile.delete()
                    error("No se pudo descargar el archivo TTF para '$safeId'")
                }
                if (!tmpFile.renameTo(dstFile)) {
                    tmpFile.copyTo(dstFile, overwrite = true)
                    tmpFile.delete()
                }
                refreshDownloadedSet()
                dstFile
            }
        }

    private fun resolveTtfUrlFromApi(fontId: String, weight: Int): String? {
        return runCatching {
            val req = Request.Builder()
                .url("https://api.fontsource.org/v1/fonts/$fontId")
                .build()
            httpClient.newCall(req).execute().use { resp ->
                if (!resp.isSuccessful) return@runCatching null
                val json = JSONObject(resp.body?.string().orEmpty())
                val variants = json.optJSONObject("variants") ?: return@runCatching null
                val weightObj = variants.optJSONObject(weight.toString())
                    ?: variants.optJSONObject("700")
                    ?: variants.optJSONObject("400")
                    ?: variants.keys().asSequence().firstOrNull()?.let { variants.optJSONObject(it) }
                    ?: return@runCatching null
                val normal = weightObj.optJSONObject("normal") ?: return@runCatching null
                val subset = normal.optJSONObject("latin")
                    ?: normal.keys().asSequence().firstOrNull()?.let { normal.optJSONObject(it) }
                    ?: return@runCatching null
                val urls = subset.optJSONObject("url") ?: return@runCatching null
                urls.optString("ttf").takeIf { it.startsWith("http") }
            }
        }.getOrNull()
    }

    private fun tryDownloadToFile(url: String, dst: File): Boolean {
        return runCatching {
            val req = Request.Builder().url(url).build()
            httpClient.newCall(req).execute().use { resp ->
                if (!resp.isSuccessful) return false
                val stream = resp.body?.byteStream() ?: return false
                dst.outputStream().use { out -> stream.copyTo(out) }
                true
            }
        }.getOrDefault(false)
    }

    companion object {
        fun featuredCatalog(): List<FontsourceFontItem> = listOf(
            FontsourceFontItem("inter", "Inter", "sans-serif"),
            FontsourceFontItem("space-grotesk", "Space Grotesk", "display"),
            FontsourceFontItem("jetbrains-mono", "JetBrains Mono", "monospace"),
            FontsourceFontItem("outfit", "Outfit", "sans-serif"),
            FontsourceFontItem("bebas-neue", "Bebas Neue", "display"),
            FontsourceFontItem("orbitron", "Orbitron", "display"),
            FontsourceFontItem("syne", "Syne", "display"),
            FontsourceFontItem("chakra-petch", "Chakra Petch", "display"),
            FontsourceFontItem("rajdhani", "Rajdhani", "sans-serif"),
            FontsourceFontItem("oswald", "Oswald", "sans-serif"),
            FontsourceFontItem("plus-jakarta-sans", "Plus Jakarta Sans", "sans-serif"),
            FontsourceFontItem("dm-sans", "DM Sans", "sans-serif"),
            FontsourceFontItem("montserrat", "Montserrat", "sans-serif"),
            FontsourceFontItem("rubik", "Rubik", "sans-serif"),
            FontsourceFontItem("ibm-plex-mono", "IBM Plex Mono", "monospace"),
            FontsourceFontItem("manrope", "Manrope", "sans-serif"),
            FontsourceFontItem("poppins", "Poppins", "sans-serif"),
            FontsourceFontItem("lexend", "Lexend", "sans-serif"),
            FontsourceFontItem("cinzel", "Cinzel", "serif"),
            FontsourceFontItem("playfair-display", "Playfair Display", "serif"),
        )
    }
}
