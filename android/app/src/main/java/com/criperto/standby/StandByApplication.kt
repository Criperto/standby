package com.criperto.standby

import android.app.Application
import com.criperto.standby.ai.ModelDownloader
import com.criperto.standby.core.DeviceSignalProvider
import com.criperto.standby.core.PresetRepository
import com.criperto.standby.core.SettingsRepository
import com.criperto.standby.editor.EditorViewModel
import com.criperto.standby.fonts.FontsourceRepository
import com.criperto.standby.renderer.SceneAssetCache
import com.criperto.standby.renderer.ShaderRegistry
import com.criperto.standby.smarthome.SmartHomeRepository
import com.criperto.standby.smarthome.SmartHomeViewModel
import com.criperto.standby.standby.StandByMonitorService
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.launch

class StandByApplication : Application() {
    val applicationScope = CoroutineScope(SupervisorJob() + Dispatchers.Main.immediate)

    val settingsRepository: SettingsRepository by lazy { SettingsRepository(this) }
    val presetRepository: PresetRepository by lazy { PresetRepository(this, settingsRepository) }
    val signalProvider: DeviceSignalProvider by lazy {
        DeviceSignalProvider(this, settingsRepository, presetRepository)
    }
    val smartHomeRepository: SmartHomeRepository by lazy { SmartHomeRepository(this) }
    val smartHomeController: SmartHomeViewModel by lazy {
        SmartHomeViewModel(this, smartHomeRepository, signalProvider)
    }
    val editorController: EditorViewModel by lazy { EditorViewModel(this) }
    val fontsourceRepository: FontsourceRepository by lazy { FontsourceRepository(this) }
    val modelDownloader: ModelDownloader by lazy { ModelDownloader(this) }
    val shaderRegistry: ShaderRegistry by lazy { ShaderRegistry(this) }
    val assetCache: SceneAssetCache by lazy { SceneAssetCache(this) }

    override fun onCreate() {
        super.onCreate()
        applicationScope.launch {
            val initialSettings = runCatching { settingsRepository.settingsFlow.first() }.getOrNull()
            val initialPreset = initialSettings?.activePresetName ?: "standby_split"
            presetRepository.initialize(initialPreset)
            if (initialSettings?.autoTriggerServiceEnabled == true) {
                StandByMonitorService.syncState(this@StandByApplication, enabled = true)
            }
        }
    }
}
