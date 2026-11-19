package com.criperto.standby

import android.content.res.Configuration
import android.os.Bundle
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.activity.enableEdgeToEdge
import androidx.compose.foundation.background
import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.isSystemInDarkTheme
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ExperimentalLayoutApi
import androidx.compose.foundation.layout.FlowRow
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.aspectRatio
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.rounded.AutoFixHigh
import androidx.compose.material.icons.rounded.Bedtime
import androidx.compose.material.icons.rounded.Edit
import androidx.compose.material.icons.rounded.Home
import androidx.compose.material.icons.rounded.Lightbulb
import androidx.compose.material.icons.rounded.PlayArrow
import androidx.compose.material.icons.rounded.Settings
import androidx.compose.material3.AssistChip
import androidx.compose.material3.Button
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.ElevatedCard
import androidx.compose.material3.FilterChip
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.NavigationBar
import androidx.compose.material3.NavigationBarItem
import androidx.compose.material3.NavigationRail
import androidx.compose.material3.NavigationRailItem
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Text
import androidx.compose.material3.dynamicDarkColorScheme
import androidx.compose.material3.dynamicLightColorScheme
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.platform.LocalConfiguration
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.unit.dp
import com.criperto.standby.core.AppSettings
import com.criperto.standby.editor.EditorScreen
import com.criperto.standby.renderer.CutoutDiskCache
import com.criperto.standby.renderer.DepthMaskRuntimeData
import com.criperto.standby.renderer.SceneRenderer
import com.criperto.standby.settings.SettingsScreen
import com.criperto.standby.smarthome.SmartHomeScreen
import com.criperto.standby.standby.StandByActivity
import kotlinx.coroutines.launch
import uniffi.standby_core.coreVersion
import kotlin.math.roundToInt

enum class AppTab(val label: String, val icon: ImageVector) {
    HOME("Inicio", Icons.Rounded.Home),
    SMART_HOME("Hogar WiZ", Icons.Rounded.Lightbulb),
    EDITOR("Editor", Icons.Rounded.Edit),
    SETTINGS("Ajustes", Icons.Rounded.Settings),
}

class MainActivity : ComponentActivity() {
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        enableEdgeToEdge()
        setContent {
            StandByTheme {
                MainShell(app = application as StandByApplication)
            }
        }
    }
}

@Composable
fun StandByTheme(
    darkTheme: Boolean = isSystemInDarkTheme(),
    content: @Composable () -> Unit,
) {
    val context = LocalContext.current
    val colorScheme = if (darkTheme) {
        dynamicDarkColorScheme(context)
    } else {
        dynamicLightColorScheme(context)
    }
    MaterialTheme(
        colorScheme = colorScheme,
        content = content,
    )
}

@Composable
private fun MainShell(app: StandByApplication) {
    var currentTab by rememberSaveable { mutableStateOf(AppTab.HOME) }
    val isLandscape = LocalConfiguration.current.orientation == Configuration.ORIENTATION_LANDSCAPE

    Scaffold(
        modifier = Modifier.fillMaxSize(),
        bottomBar = {
            if (!isLandscape) {
                NavigationBar {
                    AppTab.entries.forEach { tab ->
                        NavigationBarItem(
                            selected = currentTab == tab,
                            onClick = { currentTab = tab },
                            icon = { Icon(tab.icon, contentDescription = tab.label) },
                            label = { Text(tab.label) },
                        )
                    }
                }
            }
        },
    ) { innerPadding ->
        Row(
            modifier = Modifier
                .fillMaxSize()
                .padding(innerPadding),
        ) {
            if (isLandscape) {
                NavigationRail(modifier = Modifier.fillMaxHeight()) {
                    Spacer(Modifier.weight(1f))
                    AppTab.entries.forEach { tab ->
                        NavigationRailItem(
                            selected = currentTab == tab,
                            onClick = { currentTab = tab },
                            icon = { Icon(tab.icon, contentDescription = tab.label) },
                            label = { Text(tab.label) },
                        )
                    }
                    Spacer(Modifier.weight(1f))
                }
            }

            Box(modifier = Modifier.weight(1f).fillMaxSize()) {
                when (currentTab) {
                    AppTab.HOME -> HomeDashboardScreen(
                        app = app,
                        onOpenTab = { currentTab = it },
                    )
                    AppTab.SMART_HOME -> SmartHomeScreen(app = app)
                    AppTab.EDITOR -> EditorScreen(app = app)
                    AppTab.SETTINGS -> SettingsScreen(app = app)
                }
            }
        }
    }
}

@OptIn(ExperimentalLayoutApi::class)
@Composable
private fun HomeDashboardScreen(
    app: StandByApplication,
    onOpenTab: (AppTab) -> Unit,
) {
    val context = LocalContext.current
    val scope = rememberCoroutineScope()

    val settings by app.settingsRepository.settingsFlow.collectAsState(initial = AppSettings())
    val presetIds by app.presetRepository.presetIdsFlow.collectAsState()
    val activePresetId by app.presetRepository.activePresetIdFlow.collectAsState()
    val activePreset by app.presetRepository.activePresetFlow.collectAsState()
    val signals by app.signalProvider.signals.collectAsState()
    val rustVersion = remember { coreVersion() }

    DisposableEffect(Unit) {
        app.signalProvider.acquireRenderConsumer()
        onDispose { app.signalProvider.releaseRenderConsumer() }
    }

    var depthMask by remember { mutableStateOf<DepthMaskRuntimeData?>(null) }
    LaunchedEffect(activePreset.name, activePreset.background.mode, activePreset.background.wallpaperUri) {
        depthMask = CutoutDiskCache.loadDepthMaskForPreset(context, activePreset)
    }

    Column(
        modifier = Modifier
            .fillMaxSize()
            .verticalScroll(rememberScrollState())
            .padding(16.dp),
        verticalArrangement = Arrangement.spacedBy(16.dp),
    ) {
        Row(
            modifier = Modifier.fillMaxWidth(),
            horizontalArrangement = Arrangement.SpaceBetween,
            verticalAlignment = Alignment.CenterVertically,
        ) {
            Column {
                Text("StandBy", style = MaterialTheme.typography.headlineMedium)
                Text(
                    "Core nativo $rustVersion",
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
            }
            Button(
                onClick = {
                    context.startActivity(StandByActivity.createIntent(context, manualLaunch = true))
                },
            ) {
                Icon(Icons.Rounded.PlayArrow, contentDescription = null)
                Spacer(Modifier.width(6.dp))
                Text("Lanzar StandBy")
            }
        }

        ElevatedCard(
            modifier = Modifier.fillMaxWidth(),
            colors = CardDefaults.elevatedCardColors(
                containerColor = MaterialTheme.colorScheme.surfaceContainer,
            ),
        ) {
            Column(modifier = Modifier.padding(14.dp), verticalArrangement = Arrangement.spacedBy(12.dp)) {
                Text(
                    "Vista previa: ${activePreset.name}",
                    style = MaterialTheme.typography.titleMedium,
                )
                Box(
                    modifier = Modifier
                        .fillMaxWidth()
                        .aspectRatio(16f / 9f)
                        .clip(RoundedCornerShape(16.dp))
                        .background(Color.Black),
                ) {
                    SceneRenderer(
                        preset = activePreset,
                        signals = signals,
                        shaderRegistry = app.shaderRegistry,
                        assetCache = app.assetCache,
                        depthMask = depthMask,
                        interactive = false,
                    )
                }

                if (presetIds.isNotEmpty()) {
                    Row(
                        modifier = Modifier.horizontalScroll(rememberScrollState()),
                        horizontalArrangement = Arrangement.spacedBy(8.dp),
                    ) {
                        presetIds.forEach { id ->
                            FilterChip(
                                selected = id == activePresetId,
                                onClick = {
                                    scope.launch { app.presetRepository.loadAndActivate(id) }
                                },
                                label = { Text(id) },
                            )
                        }
                    }
                }
            }
        }

        ElevatedCard(modifier = Modifier.fillMaxWidth()) {
            Column(modifier = Modifier.padding(16.dp), verticalArrangement = Arrangement.spacedBy(10.dp)) {
                Text("Estado actual del dispositivo", style = MaterialTheme.typography.titleMedium)
                FlowRow(
                    horizontalArrangement = Arrangement.spacedBy(8.dp),
                    verticalArrangement = Arrangement.spacedBy(6.dp),
                ) {
                    AssistChip(
                        onClick = {},
                        label = {
                            Text(
                                if (signals.isCharging) {
                                    "Cargando (${signals.chargeSource}) · ${signals.batteryLevel}%"
                                } else {
                                    "Batería ${signals.batteryLevel}%"
                                }
                            )
                        },
                    )
                    AssistChip(
                        onClick = {},
                        label = { Text(if (signals.isLandscape) "Horizontal" else "Vertical") },
                    )
                    AssistChip(
                        onClick = {},
                        label = {
                            Text(
                                signals.ambientLux?.let { "Luz: ${it.roundToInt()} lux" } ?: "Luz: N/D"
                            )
                        },
                    )
                    AssistChip(
                        onClick = { onOpenTab(AppTab.SETTINGS) },
                        label = {
                            Text(
                                if (settings.autoTriggerServiceEnabled) "Auto-monitor ON" else "Auto-monitor OFF"
                            )
                        },
                    )
                }
                Row(horizontalArrangement = Arrangement.spacedBy(10.dp)) {
                    OutlinedButton(onClick = { onOpenTab(AppTab.EDITOR) }) {
                        Icon(Icons.Rounded.Edit, contentDescription = null)
                        Spacer(Modifier.width(6.dp))
                        Text("Editar reloj")
                    }
                    OutlinedButton(onClick = { onOpenTab(AppTab.SMART_HOME) }) {
                        Icon(Icons.Rounded.Lightbulb, contentDescription = null)
                        Spacer(Modifier.width(6.dp))
                        Text("Lámparas WiZ")
                    }
                }
            }
        }
    }
}

