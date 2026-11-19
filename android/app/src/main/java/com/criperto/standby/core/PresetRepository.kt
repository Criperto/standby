package com.criperto.standby.core

import android.content.Context
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.withContext
import uniffi.standby_core.FfiBundleAsset
import uniffi.standby_core.importCripertoshellJson
import uniffi.standby_core.packStandbyZip
import uniffi.standby_core.unpackStandbyZip
import uniffi.standby_core.validatePresetJson
import java.io.File

class PresetRepository(
    private val context: Context,
    private val settingsRepository: SettingsRepository,
) {
    private val presetsDir = File(context.filesDir, "presets").apply { mkdirs() }
    private val fontsDir = File(context.filesDir, "fonts/imported").apply { mkdirs() }
    private val svgsDir = File(context.filesDir, "svgs").apply { mkdirs() }
    private val wallpapersDir = File(context.filesDir, "wallpapers").apply { mkdirs() }

    private val _presetIds = MutableStateFlow<List<String>>(emptyList())
    val presetIdsFlow: StateFlow<List<String>> = _presetIds.asStateFlow()

    private val _activePreset = MutableStateFlow(StandByPreset())
    val activePresetFlow: StateFlow<StandByPreset> = _activePreset.asStateFlow()

    private val _activePresetId = MutableStateFlow("iphone_1_v2")
    val activePresetIdFlow: StateFlow<String> = _activePresetId.asStateFlow()

    private val builtinFiles = listOf(
        "iphone_1_v2.json",
        "iphone_standby_pure.json",
        "apple_standby_duotone.json",
        "apple_weather_home.json",
        "amoled_minimal.json",
        "standby_split.json",
        "liquid_glass_showcase.json",
        "composite_2x2.json",
    )

    suspend fun initialize(initialActiveId: String) = withContext(Dispatchers.IO) {
        val migratedToV3 = seedBuiltinsIfMissing()
        refreshList()
        val chosen = when {
            migratedToV3 -> "iphone_1_v2"
            presetFile(initialActiveId).exists() -> initialActiveId
            else -> "iphone_1_v2"
        }
        loadAndActivate(chosen)
    }

    private fun seedBuiltinsIfMissing(): Boolean {
        val marker = File(presetsDir, ".builtin_seed_v3")
        val forceRefreshBuiltins = !marker.exists()
        for (fileName in builtinFiles) {
            val dst = File(presetsDir, fileName)
            if (!dst.exists() || forceRefreshBuiltins) {
                runCatching {
                    context.assets.open("presets/$fileName").use { input ->
                        dst.outputStream().use { output -> input.copyTo(output) }
                    }
                }
            }
        }
        if (forceRefreshBuiltins) {
            runCatching { marker.writeText("v3") }
        }
        return forceRefreshBuiltins
    }

    suspend fun restoreBuiltinPresets(): List<String> = withContext(Dispatchers.IO) {
        for (fileName in builtinFiles) {
            val dst = File(presetsDir, fileName)
            runCatching {
                context.assets.open("presets/$fileName").use { input ->
                    dst.outputStream().use { output -> input.copyTo(output) }
                }
            }
        }
        val list = refreshList()
        loadAndActivate(_activePresetId.value.ifBlank { "iphone_1_v2" })
        list
    }

    suspend fun cycleActivePreset(delta: Int): String = withContext(Dispatchers.IO) {
        val list = _presetIds.value.ifEmpty { refreshList() }
        if (list.isEmpty()) return@withContext _activePresetId.value
        val curIdx = list.indexOf(_activePresetId.value).takeIf { it >= 0 } ?: 0
        val nextIdx = ((curIdx + delta) % list.size + list.size) % list.size
        val nextId = list[nextIdx]
        loadAndActivate(nextId)
        nextId
    }

    private fun presetFile(id: String): File {
        val safe = id.replace(Regex("[^A-Za-z0-9_\\-]"), "_")
        return File(presetsDir, "$safe.json")
    }

    fun refreshList(): List<String> {
        val ids = presetsDir.listFiles { f -> f.isFile && f.extension == "json" }
            ?.map { it.nameWithoutExtension }
            ?.sorted()
            ?: emptyList()
        _presetIds.value = ids
        return ids
    }

    suspend fun loadPreset(id: String): StandByPreset? = withContext(Dispatchers.IO) {
        val file = presetFile(id)
        if (!file.exists()) return@withContext null
        val raw = runCatching { file.readText() }.getOrNull() ?: return@withContext null
        val report = runCatching {
            AppJson.decodeFromString<ValidationReport>(validatePresetJson(raw))
        }.getOrNull()
        val jsonToDecode = report?.sanitizedJson?.takeIf { it.isNotBlank() } ?: raw
        runCatching { AppJson.decodeFromString<StandByPreset>(jsonToDecode) }.getOrNull()
    }

    suspend fun loadAndActivate(id: String): StandByPreset {
        val preset = loadPreset(id) ?: run {
            seedBuiltinsIfMissing()
            loadPreset("amoled_minimal") ?: StandByPreset()
        }
        _activePresetId.value = id
        _activePreset.value = preset
        settingsRepository.update { it.copy(activePresetName = id) }
        return preset
    }

    suspend fun savePreset(id: String, preset: StandByPreset, activate: Boolean = true): ValidationReport =
        withContext(Dispatchers.IO) {
            val encoded = AppJson.encodeToString(preset)
            val report = AppJson.decodeFromString<ValidationReport>(validatePresetJson(encoded))
            val outJson = report.sanitizedJson.ifBlank { encoded }
            val dst = presetFile(id)
            val tmp = File(presetsDir, "$id.tmp.json")
            tmp.writeText(outJson)
            if (!tmp.renameTo(dst)) {
                dst.writeText(outJson)
                tmp.delete()
            }
            refreshList()
            if (activate || _activePresetId.value == id) {
                val parsed = AppJson.decodeFromString<StandByPreset>(outJson)
                _activePresetId.value = id
                _activePreset.value = parsed
                settingsRepository.update { it.copy(activePresetName = id) }
            }
            report
        }

    suspend fun deletePreset(id: String): Boolean = withContext(Dispatchers.IO) {
        if (id in listOf("amoled_minimal", "standby_split")) return@withContext false
        val deleted = presetFile(id).delete()
        val list = refreshList()
        if (_activePresetId.value == id && list.isNotEmpty()) {
            loadAndActivate(list.first())
        }
        deleted
    }

    suspend fun duplicatePreset(sourceId: String, newName: String): String =
        withContext(Dispatchers.IO) {
            val src = loadPreset(sourceId) ?: _activePreset.value
            val slug = newName.lowercase().replace(Regex("[^a-z0-9_]+"), "_").trim('_')
                .ifEmpty { "custom_${System.currentTimeMillis() / 1000}" }
            val copy = src.copy(name = newName)
            savePreset(slug, copy, activate = true)
            slug
        }

    suspend fun importJsonOrCripertoShell(rawJson: String, desiredName: String? = null): Result<Pair<String, ValidationReport>> =
        withContext(Dispatchers.IO) {
            runCatching {
                val directReport = AppJson.decodeFromString<ValidationReport>(validatePresetJson(rawJson))
                val isCriperto = !rawJson.contains("\"schemaVersion\"") &&
                    (rawJson.contains("password_input") || rawJson.contains("power_shutdown") || rawJson.contains("textColorChoice"))

                val finalPreset: StandByPreset = if (isCriperto || !directReport.valid) {
                    val migratedJson = importCripertoshellJson(rawJson)
                    AppJson.decodeFromString(migratedJson)
                } else {
                    AppJson.decodeFromString(directReport.sanitizedJson.ifBlank { rawJson })
                }

                val name = desiredName?.takeIf { it.isNotBlank() } ?: finalPreset.name
                val slug = name.lowercase().replace(Regex("[^a-z0-9_]+"), "_").trim('_')
                    .ifEmpty { "importado_${System.currentTimeMillis() / 1000}" }
                val report = savePreset(slug, finalPreset.copy(name = name), activate = true)
                slug to report
            }
        }

    suspend fun importStandbyZip(zipBytes: ByteArray): Result<Pair<String, ValidationReport>> =
        withContext(Dispatchers.IO) {
            runCatching {
                val extracted = unpackStandbyZip(zipBytes)
                for (asset in extracted.assets) {
                    val path = asset.archivePath.trimStart('/')
                    when {
                        path.startsWith("fonts/") -> {
                            val name = File(path).name
                            File(fontsDir, name).writeBytes(asset.bytes)
                        }
                        path.startsWith("svgs/") -> {
                            val name = File(path).name
                            File(svgsDir, name).writeBytes(asset.bytes)
                        }
                        path.startsWith("wallpaper") -> {
                            val name = File(path).name
                            File(wallpapersDir, name).writeBytes(asset.bytes)
                        }
                    }
                }
                val parsed = AppJson.decodeFromString<StandByPreset>(extracted.presetJson)
                val slug = parsed.name.lowercase().replace(Regex("[^a-z0-9_]+"), "_").trim('_')
                    .ifEmpty { "bundle_${System.currentTimeMillis() / 1000}" }
                val report = savePreset(slug, parsed, activate = true)
                slug to report
            }
        }

    suspend fun exportStandbyZip(id: String): Result<ByteArray> = withContext(Dispatchers.IO) {
        runCatching {
            val preset = loadPreset(id) ?: _activePreset.value
            val json = AppJson.encodeToString(preset)
            val assets = mutableListOf<FfiBundleAsset>()
            for (obj in preset.objects) {
                obj.fontFileName?.takeIf { it.isNotBlank() }?.let { fn ->
                    val f = File(fontsDir, fn)
                    if (f.exists()) assets.add(FfiBundleAsset("fonts/$fn", f.readBytes()))
                }
                obj.svgSource?.removePrefix("asset:")?.takeIf { obj.svgSource?.startsWith("asset:") == true }?.let { svg ->
                    val f = File(svgsDir, svg)
                    if (f.exists()) assets.add(FfiBundleAsset("svgs/$svg", f.readBytes()))
                }
            }
            preset.background.wallpaperUri?.let { wpPath ->
                val f = File(wpPath)
                if (f.exists() && f.length() <= 24 * 1024 * 1024) {
                    assets.add(FfiBundleAsset("wallpaper.${f.extension.ifBlank { "jpg" }}", f.readBytes()))
                }
            }
            packStandbyZip(json, assets)
        }
    }
}
