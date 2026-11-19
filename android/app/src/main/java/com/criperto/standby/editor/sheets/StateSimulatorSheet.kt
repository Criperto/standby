package com.criperto.standby.editor.sheets

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Slider
import androidx.compose.material3.Switch
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp
import com.criperto.standby.editor.EditorUiState
import com.criperto.standby.editor.EditorViewModel
import kotlin.math.roundToInt

@Composable
fun StateSimulatorSheet(
    uiState: EditorUiState,
    viewModel: EditorViewModel,
    modifier: Modifier = Modifier,
) {
    val sim = uiState.simulatorSignals

    Column(
        modifier = modifier
            .fillMaxSize()
            .verticalScroll(rememberScrollState())
            .padding(14.dp),
        verticalArrangement = Arrangement.spacedBy(10.dp),
    ) {
        Row(
            modifier = Modifier.fillMaxWidth(),
            horizontalArrangement = Arrangement.SpaceBetween,
            verticalAlignment = Alignment.CenterVertically,
        ) {
            Column(modifier = Modifier.weight(1f)) {
                Text("Simulador de señales en vivo", style = MaterialTheme.typography.titleMedium)
                Text(
                    "Permite probar expresiones, regex, batería y media sin esperar eventos reales",
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
            }
            Switch(
                checked = uiState.simulatorEnabled,
                onCheckedChange = { viewModel.setSimulatorEnabled(it) },
            )
        }

        Text("Hora simulada: %02d:%02d:%02d".format(sim.hour, sim.minute, sim.second.toInt()))
        Slider(
            value = sim.hour.toFloat(),
            onValueChange = { h ->
                viewModel.updateSimulatorSignals { it.copy(hour = h.roundToInt()) }
            },
            valueRange = 0f..23f,
        )
        Slider(
            value = sim.minute.toFloat(),
            onValueChange = { m ->
                viewModel.updateSimulatorSignals { it.copy(minute = m.roundToInt()) }
            },
            valueRange = 0f..59f,
        )

        Text("Batería simulada: ${sim.batteryLevel}%")
        Slider(
            value = sim.batteryLevel.toFloat(),
            onValueChange = { b ->
                viewModel.updateSimulatorSignals { it.copy(batteryLevel = b.roundToInt()) }
            },
            valueRange = 1f..100f,
        )

        Row(
            modifier = Modifier.fillMaxWidth(),
            horizontalArrangement = Arrangement.SpaceBetween,
            verticalAlignment = Alignment.CenterVertically,
        ) {
            Text("Cargando")
            Switch(
                checked = sim.isCharging,
                onCheckedChange = { c ->
                    viewModel.updateSimulatorSignals { it.copy(isCharging = c) }
                },
            )
        }

        Row(
            modifier = Modifier.fillMaxWidth(),
            horizontalArrangement = Arrangement.SpaceBetween,
            verticalAlignment = Alignment.CenterVertically,
        ) {
            Text("Reproduciendo Media (isPlaying)")
            Switch(
                checked = sim.media.isPlaying,
                onCheckedChange = { p ->
                    viewModel.updateSimulatorSignals {
                        it.copy(
                            media = it.media.copy(
                                hasMedia = true,
                                isPlaying = p,
                                title = it.media.title.ifBlank { "Daft Punk - Veridis Quo (Official Video)" },
                                artist = it.media.artist.ifBlank { "Discovery" },
                            )
                        )
                    }
                },
            )
        }

        OutlinedTextField(
            value = sim.media.title,
            onValueChange = { title ->
                viewModel.updateSimulatorSignals {
                    it.copy(media = it.media.copy(hasMedia = true, title = title))
                }
            },
            label = { Text("Título Media (para probar regexReplace/regexExtract)") },
            singleLine = true,
            modifier = Modifier.fillMaxWidth(),
        )

        OutlinedTextField(
            value = sim.media.artist,
            onValueChange = { artist ->
                viewModel.updateSimulatorSignals {
                    it.copy(media = it.media.copy(hasMedia = true, artist = artist))
                }
            },
            label = { Text("Artista Media") },
            singleLine = true,
            modifier = Modifier.fillMaxWidth(),
        )

        Row(
            modifier = Modifier.fillMaxWidth(),
            horizontalArrangement = Arrangement.SpaceBetween,
            verticalAlignment = Alignment.CenterVertically,
        ) {
            Text("Lámpara WiZ encendida (wizOn)")
            Switch(
                checked = sim.wizStatus.isOn,
                onCheckedChange = { on ->
                    viewModel.updateSimulatorSignals {
                        it.copy(wizStatus = it.wizStatus.copy(isOn = on))
                    }
                },
            )
        }

        Spacer(Modifier.height(16.dp))
    }
}
