package com.criperto.standby.smarthome

import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.lazy.LazyRow
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.rounded.ExitToApp
import androidx.compose.material.icons.rounded.Add
import androidx.compose.material.icons.rounded.Close
import androidx.compose.material.icons.rounded.Lightbulb
import androidx.compose.material.icons.rounded.PowerSettingsNew
import androidx.compose.material.icons.rounded.Search
import androidx.compose.material3.AssistChip
import androidx.compose.material3.AssistChipDefaults
import androidx.compose.material3.Button
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.FilledTonalButton
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.LinearProgressIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp
import com.criperto.standby.StandByApplication

@Composable
fun SmartHomeHubOverlay(
    app: StandByApplication,
    remainingSec: Int,
    onDismiss: () -> Unit,
    onInteractionPing: () -> Unit,
    onExitStandBy: () -> Unit,
) {
    val controller = app.smartHomeController
    val ui by controller.uiState.collectAsState()
    val signals by app.signalProvider.signals.collectAsState()

    var editingDeviceId by remember { mutableStateOf<String?>(null) }
    var showAddIp by remember { mutableStateOf(false) }

    LaunchedEffect(Unit) {
        controller.refreshAllStates()
    }

    Card(
        modifier = Modifier
            .widthIn(max = 820.dp)
            .fillMaxWidth(0.94f)
            .padding(12.dp),
        shape = RoundedCornerShape(28.dp),
        colors = CardDefaults.cardColors(
            containerColor = MaterialTheme.colorScheme.surfaceContainerHigh,
        ),
    ) {
        Column(
            modifier = Modifier
                .fillMaxWidth()
                .padding(18.dp),
            verticalArrangement = Arrangement.spacedBy(12.dp),
        ) {
            Row(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.SpaceBetween,
                verticalAlignment = Alignment.CenterVertically,
            ) {
                Row(
                    horizontalArrangement = Arrangement.spacedBy(10.dp),
                    verticalAlignment = Alignment.CenterVertically,
                ) {
                    Icon(
                        Icons.Rounded.Lightbulb,
                        contentDescription = null,
                        tint = MaterialTheme.colorScheme.primary,
                    )
                    Column {
                        Text(
                            text = "%02d:%02d · Hogar Local WiZ".format(signals.hour, signals.minute),
                            style = MaterialTheme.typography.titleMedium,
                        )
                        Text(
                            text = "Vuelve al reloj en ${remainingSec}s · ${signals.batteryLevel}% bat.",
                            style = MaterialTheme.typography.bodySmall,
                            color = MaterialTheme.colorScheme.onSurfaceVariant,
                        )
                    }
                }
                Row(horizontalArrangement = Arrangement.spacedBy(6.dp)) {
                    FilledTonalButton(
                        onClick = {
                            onInteractionPing()
                            controller.discoverDevices(sweepSubnet = false)
                        },
                    ) {
                        Icon(Icons.Rounded.Search, contentDescription = null)
                        Spacer(Modifier.width(4.dp))
                        Text("Buscar")
                    }
                    OutlinedButton(onClick = onExitStandBy) {
                        Icon(Icons.AutoMirrored.Rounded.ExitToApp, contentDescription = null)
                        Spacer(Modifier.width(4.dp))
                        Text("Salir")
                    }
                    IconButton(onClick = onDismiss) {
                        Icon(Icons.Rounded.Close, contentDescription = "Volver al reloj")
                    }
                }
            }

            if (ui.discovering) {
                LinearProgressIndicator(modifier = Modifier.fillMaxWidth())
            }

            ui.errorMessage?.let { msg ->
                Text(msg, style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.error)
            } ?: ui.statusMessage?.let { msg ->
                Text(msg, style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.primary)
            }

            if (ui.devices.isNotEmpty()) {
                Row(
                    modifier = Modifier.horizontalScroll(rememberScrollState()),
                    horizontalArrangement = Arrangement.spacedBy(8.dp),
                ) {
                    // Permanent 'Apagar todo' chip ALWAYS first
                    AssistChip(
                        onClick = {
                            onInteractionPing()
                            controller.applyFavorite(SmartHomeViewModel.PINNED_ALL_OFF)
                        },
                        leadingIcon = {
                            Icon(
                                Icons.Rounded.PowerSettingsNew,
                                contentDescription = null,
                                tint = MaterialTheme.colorScheme.error,
                            )
                        },
                        label = { Text("Apagar todo") },
                        colors = AssistChipDefaults.assistChipColors(
                            containerColor = MaterialTheme.colorScheme.errorContainer.copy(alpha = 0.45f),
                        ),
                    )
                    ui.favorites.forEach { fav ->
                        AssistChip(
                            onClick = {
                                onInteractionPing()
                                controller.applyFavorite(fav)
                            },
                            label = { Text(fav.name) },
                        )
                    }
                }
            }

            if (ui.devices.isEmpty()) {
                Column(
                    modifier = Modifier
                        .fillMaxWidth()
                        .padding(vertical = 12.dp),
                    horizontalAlignment = Alignment.CenterHorizontally,
                    verticalArrangement = Arrangement.spacedBy(8.dp),
                ) {
                    Text(
                        "Aún no hay lámparas WiZ guardadas.",
                        style = MaterialTheme.typography.bodyLarge,
                    )
                    Row(horizontalArrangement = Arrangement.spacedBy(10.dp)) {
                        Button(onClick = {
                            onInteractionPing()
                            controller.discoverDevices(sweepSubnet = true)
                        }) {
                            Icon(Icons.Rounded.Search, contentDescription = null)
                            Spacer(Modifier.width(6.dp))
                            Text("Escanear Wi-Fi /24")
                        }
                        OutlinedButton(onClick = {
                            onInteractionPing()
                            showAddIp = true
                        }) {
                            Icon(Icons.Rounded.Add, contentDescription = null)
                            Spacer(Modifier.width(6.dp))
                            Text("Añadir por IP")
                        }
                    }
                }
            } else {
                LazyRow(
                    horizontalArrangement = Arrangement.spacedBy(14.dp),
                    modifier = Modifier.fillMaxWidth(),
                ) {
                    items(ui.devices, key = { it.id }) { dev ->
                        val st = ui.statesByDeviceId[dev.id] ?: SmartDeviceStateDto()
                        val reach = ui.reachableByDeviceId[dev.id] ?: true
                        WizDeviceCard(
                            device = dev,
                            state = st,
                            reachable = reach,
                            scenes = ui.scenes,
                            compact = true,
                            onTogglePower = {
                                onInteractionPing()
                                controller.setPower(dev.id, it)
                            },
                            onBrightnessChange = {
                                onInteractionPing()
                                controller.setBrightness(dev.id, it)
                            },
                            onOpenControls = {
                                onInteractionPing()
                                editingDeviceId = dev.id
                            },
                            modifier = Modifier.width(310.dp),
                        )
                    }
                }
            }
        }
    }

    val editingDev = ui.devices.firstOrNull { it.id == editingDeviceId }
    if (editingDev != null) {
        WizControlDialog(
            device = editingDev,
            state = ui.statesByDeviceId[editingDev.id] ?: SmartDeviceStateDto(),
            scenes = ui.scenes,
            onDismiss = { editingDeviceId = null },
            onInteractionPing = onInteractionPing,
            onTogglePower = { controller.setPower(editingDev.id, it) },
            onBrightnessChange = { controller.setBrightness(editingDev.id, it) },
            onSetRgb = { r, g, b, d -> controller.setRgb(editingDev.id, r, g, b, d) },
            onSetTemp = { k, d -> controller.setTempKelvin(editingDev.id, k, d) },
            onSetScene = { s, spd, d -> controller.setScene(editingDev.id, s, spd, d) },
            onSaveFavorite = { controller.addFavorite(it) },
        )
    }

    if (showAddIp) {
        AddByIpDialog(
            onDismiss = { showAddIp = false },
            onAdd = { ip, name, room ->
                onInteractionPing()
                controller.probeAndAddIp(ip, name, room)
                showAddIp = false
            },
        )
    }
}
