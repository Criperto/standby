package com.criperto.standby.smarthome

import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ExperimentalLayoutApi
import androidx.compose.foundation.layout.FlowRow
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.rememberScrollState
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.rounded.Add
import androidx.compose.material.icons.rounded.Close
import androidx.compose.material.icons.rounded.Refresh
import androidx.compose.material.icons.rounded.Search
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.AssistChip
import androidx.compose.material3.Button
import androidx.compose.material3.ElevatedCard
import androidx.compose.material3.FilterChip
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.InputChip
import androidx.compose.material3.LinearProgressIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Switch
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp
import com.criperto.standby.StandByApplication

@OptIn(ExperimentalLayoutApi::class)
@Composable
fun SmartHomeScreen(
    app: StandByApplication,
    modifier: Modifier = Modifier,
) {
    val controller = app.smartHomeController
    val ui by controller.uiState.collectAsState()

    var sweepSubnet by remember { mutableStateOf(true) }
    var selectedRoom by remember { mutableStateOf<String?>(null) }
    var showAddIp by remember { mutableStateOf(false) }
    var editingDeviceId by remember { mutableStateOf<String?>(null) }
    var renamingDevice by remember { mutableStateOf<WizDeviceConfig?>(null) }

    val filteredDevices = remember(ui.devices, selectedRoom) {
        if (selectedRoom == null) ui.devices else ui.devices.filter { it.room == selectedRoom }
    }

    LazyColumn(
        modifier = modifier
            .fillMaxSize()
            .padding(horizontal = 16.dp),
        verticalArrangement = Arrangement.spacedBy(12.dp),
    ) {
        item { Spacer(Modifier.height(4.dp)) }

        item {
            ElevatedCard(modifier = Modifier.fillMaxWidth()) {
                Column(
                    modifier = Modifier.padding(16.dp),
                    verticalArrangement = Arrangement.spacedBy(10.dp),
                ) {
                    Row(
                        modifier = Modifier.fillMaxWidth(),
                        horizontalArrangement = Arrangement.SpaceBetween,
                        verticalAlignment = Alignment.CenterVertically,
                    ) {
                        Column(modifier = Modifier.weight(1f)) {
                            Text("Hogar Local (WiZ UDP)", style = MaterialTheme.typography.titleLarge)
                            Text(
                                "Control directo LAN por puerto 38899, sin servidor ni nube.",
                                style = MaterialTheme.typography.bodySmall,
                                color = MaterialTheme.colorScheme.onSurfaceVariant,
                            )
                        }
                        IconButton(onClick = { controller.refreshAllStates() }) {
                            Icon(Icons.Rounded.Refresh, contentDescription = "Actualizar estados")
                        }
                    }

                    Row(
                        modifier = Modifier.fillMaxWidth(),
                        horizontalArrangement = Arrangement.SpaceBetween,
                        verticalAlignment = Alignment.CenterVertically,
                    ) {
                        Text(
                            "Barrido completo /24 (útil si el router bloquea broadcast)",
                            style = MaterialTheme.typography.bodySmall,
                            modifier = Modifier.weight(1f).padding(end = 8.dp),
                        )
                        Switch(checked = sweepSubnet, onCheckedChange = { sweepSubnet = it })
                    }

                    FlowRow(
                        horizontalArrangement = Arrangement.spacedBy(8.dp),
                        verticalArrangement = Arrangement.spacedBy(6.dp),
                    ) {
                        Button(
                            enabled = !ui.discovering,
                            onClick = { controller.discoverDevices(sweepSubnet = sweepSubnet) },
                        ) {
                            Icon(Icons.Rounded.Search, contentDescription = null)
                            Spacer(Modifier.width(6.dp))
                            Text("Buscar lámparas WiZ")
                        }
                        OutlinedButton(onClick = { showAddIp = true }) {
                            Icon(Icons.Rounded.Add, contentDescription = null)
                            Spacer(Modifier.width(6.dp))
                            Text("Añadir IP")
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
                }
            }
        }

        if (ui.favorites.isNotEmpty()) {
            item {
                Column(verticalArrangement = Arrangement.spacedBy(6.dp)) {
                    Text(
                        "Accesos rápidos (1 toque)",
                        style = MaterialTheme.typography.titleSmall,
                        color = MaterialTheme.colorScheme.primary,
                    )
                    Row(
                        modifier = Modifier.horizontalScroll(rememberScrollState()),
                        horizontalArrangement = Arrangement.spacedBy(8.dp),
                    ) {
                        ui.favorites.forEach { fav ->
                            InputChip(
                                selected = false,
                                onClick = { controller.applyFavorite(fav) },
                                label = { Text(fav.name) },
                                trailingIcon = {
                                    IconButton(
                                        onClick = { controller.removeFavorite(fav.id) },
                                    ) {
                                        Icon(Icons.Rounded.Close, contentDescription = "Eliminar favorito")
                                    }
                                },
                            )
                        }
                    }
                }
            }
        }

        if (ui.rooms.isNotEmpty() && ui.devices.isNotEmpty()) {
            item {
                Row(
                    modifier = Modifier.horizontalScroll(rememberScrollState()),
                    horizontalArrangement = Arrangement.spacedBy(8.dp),
                ) {
                    FilterChip(
                        selected = selectedRoom == null,
                        onClick = { selectedRoom = null },
                        label = { Text("Todas (${ui.devices.size})") },
                    )
                    ui.rooms.forEach { room ->
                        val count = ui.devices.count { it.room == room }
                        if (count > 0) {
                            FilterChip(
                                selected = selectedRoom == room,
                                onClick = { selectedRoom = if (selectedRoom == room) null else room },
                                label = { Text("$room ($count)") },
                            )
                        }
                    }
                }
            }
        }

        if (filteredDevices.isEmpty()) {
            item {
                ElevatedCard(modifier = Modifier.fillMaxWidth()) {
                    Column(
                        modifier = Modifier.padding(20.dp),
                        verticalArrangement = Arrangement.spacedBy(6.dp),
                    ) {
                        Text("Sin dispositivos configurados", style = MaterialTheme.typography.titleMedium)
                        Text(
                            "Conectate al mismo Wi-Fi de tu lámpara WiZ RGB y tocá 'Buscar lámparas WiZ' o ingresá su IP local.",
                            style = MaterialTheme.typography.bodyMedium,
                            color = MaterialTheme.colorScheme.onSurfaceVariant,
                        )
                    }
                }
            }
        } else {
            items(filteredDevices, key = { it.id }) { dev ->
                WizDeviceCard(
                    device = dev,
                    state = ui.statesByDeviceId[dev.id] ?: SmartDeviceStateDto(),
                    reachable = ui.reachableByDeviceId[dev.id] ?: true,
                    onTogglePower = { controller.setPower(dev.id, it) },
                    onBrightnessChange = { controller.setBrightness(dev.id, it) },
                    onOpenControls = { editingDeviceId = dev.id },
                    onRenameRequest = { renamingDevice = dev },
                    onDeleteRequest = { controller.deleteDevice(dev.id) },
                )
            }
        }

        item { Spacer(Modifier.height(24.dp)) }
    }

    if (showAddIp) {
        AddByIpDialog(
            onDismiss = { showAddIp = false },
            onAdd = { ip, name, room ->
                controller.probeAndAddIp(ip, name, room)
                showAddIp = false
            },
        )
    }

    val editingDev = ui.devices.firstOrNull { it.id == editingDeviceId }
    if (editingDev != null) {
        WizControlDialog(
            device = editingDev,
            state = ui.statesByDeviceId[editingDev.id] ?: SmartDeviceStateDto(),
            scenes = ui.scenes,
            onDismiss = { editingDeviceId = null },
            onTogglePower = { controller.setPower(editingDev.id, it) },
            onBrightnessChange = { controller.setBrightness(editingDev.id, it) },
            onSetRgb = { r, g, b, d -> controller.setRgb(editingDev.id, r, g, b, d) },
            onSetTemp = { k, d -> controller.setTempKelvin(editingDev.id, k, d) },
            onSetScene = { s, spd, d -> controller.setScene(editingDev.id, s, spd, d) },
            onSaveFavorite = { controller.addFavorite(it) },
        )
    }

    renamingDevice?.let { dev ->
        RenameDeviceDialog(
            device = dev,
            onDismiss = { renamingDevice = null },
            onConfirm = { newName, newRoom ->
                controller.renameDevice(dev.id, newName, newRoom)
                renamingDevice = null
            },
        )
    }
}

@Composable
private fun RenameDeviceDialog(
    device: WizDeviceConfig,
    onDismiss: () -> Unit,
    onConfirm: (String, String) -> Unit,
) {
    var name by remember(device) { mutableStateOf(device.name) }
    var room by remember(device) { mutableStateOf(device.room) }
    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text("Editar dispositivo") },
        text = {
            Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
                OutlinedTextField(
                    value = name,
                    onValueChange = { name = it },
                    label = { Text("Nombre") },
                    singleLine = true,
                    modifier = Modifier.fillMaxWidth(),
                )
                OutlinedTextField(
                    value = room,
                    onValueChange = { room = it },
                    label = { Text("Ambiente") },
                    singleLine = true,
                    modifier = Modifier.fillMaxWidth(),
                )
            }
        },
        confirmButton = {
            Button(onClick = { onConfirm(name, room) }) { Text("Guardar") }
        },
        dismissButton = {
            TextButton(onClick = onDismiss) { Text("Cancelar") }
        },
    )
}
