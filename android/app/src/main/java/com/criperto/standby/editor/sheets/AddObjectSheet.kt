package com.criperto.standby.editor.sheets

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ExperimentalLayoutApi
import androidx.compose.foundation.layout.FlowRow
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.FilledTonalButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp

private data class ObjectGalleryCategory(
    val title: String,
    val items: List<Pair<String, String>>,
)

private val objectCategories = listOf(
    ObjectGalleryCategory(
        title = "Relojes enteros y componentes",
        items = listOf(
            "clock" to "Reloj Completo",
            "clock_hours" to "Horas (HH)",
            "clock_minutes" to "Minutos (MM)",
            "clock_seconds" to "Segundos (SS)",
            "clock_separator" to "Separador (:)",
            "clock_ampm" to "AM / PM",
        ),
    ),
    ObjectGalleryCategory(
        title = "Dígitos individuales (Compuesto 2×2 / 4 dígitos)",
        items = listOf(
            "clock_h1" to "Hora 1 (Decena)",
            "clock_h2" to "Hora 2 (Unidad)",
            "clock_m1" to "Minuto 1 (Decena)",
            "clock_m2" to "Minuto 2 (Unidad)",
            "clock_s1" to "Segundo 1 (Decena)",
            "clock_s2" to "Segundo 2 (Unidad)",
        ),
    ),
    ObjectGalleryCategory(
        title = "Fecha, Texto, Figuras e Iconos SVG",
        items = listOf(
            "date" to "Fecha",
            "datetime" to "Fecha y Hora",
            "custom_text" to "Texto / Macro",
            "shape" to "Figura Paramétrica",
            "icon_svg" to "Icono Material / .SVG",
            "group" to "Grupo / Contenedor",
        ),
    ),
    ObjectGalleryCategory(
        title = "Batería, Clima y Hogar Inteligente WiZ",
        items = listOf(
            "battery" to "Batería Completa",
            "battery_icon" to "Icono Batería",
            "battery_percent" to "% Batería",
            "battery_bar" to "Barra Batería",
            "weather" to "Clima Completo",
            "weather_icon" to "Icono Clima",
            "weather_temp" to "Temperatura",
            "wiz_device" to "Botón Lámpara WiZ",
        ),
    ),
    ObjectGalleryCategory(
        title = "Reproductor Multimedia",
        items = listOf(
            "media" to "Tarjeta Media",
            "media_cover" to "Carátula Álbum",
            "media_title" to "Título Canción",
            "media_artist" to "Artista",
            "media_play_pause" to "Play / Pausa",
            "media_prev" to "Anterior",
            "media_next" to "Siguiente",
            "media_progress" to "Barra Progreso",
        ),
    ),
)

@OptIn(ExperimentalLayoutApi::class)
@Composable
fun AddObjectSheet(
    onAddObject: (String) -> Unit,
    modifier: Modifier = Modifier,
) {
    Column(
        modifier = modifier
            .fillMaxSize()
            .verticalScroll(rememberScrollState())
            .padding(14.dp),
        verticalArrangement = Arrangement.spacedBy(14.dp),
    ) {
        objectCategories.forEach { cat ->
            Column(verticalArrangement = Arrangement.spacedBy(6.dp)) {
                Text(
                    text = cat.title,
                    style = MaterialTheme.typography.labelLarge,
                    color = MaterialTheme.colorScheme.primary,
                )
                FlowRow(
                    horizontalArrangement = Arrangement.spacedBy(8.dp),
                    verticalArrangement = Arrangement.spacedBy(8.dp),
                    modifier = Modifier.fillMaxWidth(),
                ) {
                    cat.items.forEach { (typeId, label) ->
                        FilledTonalButton(onClick = { onAddObject(typeId) }) {
                            Text(label)
                        }
                    }
                }
            }
        }
        Spacer(Modifier.height(16.dp))
    }
}
