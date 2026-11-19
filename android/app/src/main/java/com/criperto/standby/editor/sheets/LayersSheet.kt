package com.criperto.standby.editor.sheets

import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.rounded.ContentCopy
import androidx.compose.material.icons.rounded.Delete
import androidx.compose.material.icons.rounded.KeyboardArrowDown
import androidx.compose.material.icons.rounded.KeyboardArrowUp
import androidx.compose.material.icons.rounded.Layers
import androidx.compose.material.icons.rounded.Lock
import androidx.compose.material.icons.rounded.LockOpen
import androidx.compose.material.icons.rounded.Visibility
import androidx.compose.material.icons.rounded.VisibilityOff
import androidx.compose.material3.Button
import androidx.compose.material3.FilterChip
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import com.criperto.standby.core.SceneObject
import com.criperto.standby.editor.EditorUiState
import com.criperto.standby.editor.EditorViewModel

@Composable
fun LayersSheet(
    uiState: EditorUiState,
    viewModel: EditorViewModel,
    modifier: Modifier = Modifier,
) {
    val sorted = uiState.preset.objects.sortedByDescending { it.zIndex }

    Column(
        modifier = modifier
            .fillMaxSize()
            .padding(12.dp),
        verticalArrangement = Arrangement.spacedBy(8.dp),
    ) {
        Row(
            modifier = Modifier.fillMaxWidth(),
            horizontalArrangement = Arrangement.SpaceBetween,
            verticalAlignment = Alignment.CenterVertically,
        ) {
            FilterChip(
                selected = uiState.multiSelectMode,
                onClick = { viewModel.setMultiSelectMode(!uiState.multiSelectMode) },
                label = { Text("Multi-selección (${uiState.selectedIds.size})") },
            )
            Row(horizontalArrangement = Arrangement.spacedBy(6.dp)) {
                if (uiState.selectedIds.size >= 2) {
                    Button(onClick = { viewModel.groupSelected() }) {
                        Text("Agrupar")
                    }
                }
                val primary = uiState.primarySelectedObject
                if (primary != null && (primary.objType == "group" || primary.groupId != null)) {
                    OutlinedButton(onClick = { viewModel.ungroupSelected() }) {
                        Text("Desagrupar")
                    }
                }
                IconButton(
                    enabled = uiState.selectedIds.isNotEmpty(),
                    onClick = { viewModel.duplicateSelected() },
                ) {
                    Icon(Icons.Rounded.ContentCopy, contentDescription = "Duplicar")
                }
                IconButton(
                    enabled = uiState.selectedIds.isNotEmpty(),
                    onClick = { viewModel.deleteSelected() },
                ) {
                    Icon(
                        Icons.Rounded.Delete,
                        contentDescription = "Eliminar",
                        tint = MaterialTheme.colorScheme.error,
                    )
                }
            }
        }

        LazyColumn(
            modifier = Modifier.fillMaxSize(),
            verticalArrangement = Arrangement.spacedBy(6.dp),
        ) {
            items(sorted, key = { it.id }) { obj ->
                val isSelected = obj.id in uiState.selectedIds
                LayerRowItem(
                    obj = obj,
                    selected = isSelected,
                    onClick = { viewModel.selectObject(obj.id) },
                    onMoveUp = { viewModel.moveZOrder(obj.id, +1) },
                    onMoveDown = { viewModel.moveZOrder(obj.id, -1) },
                    onToggleVisible = {
                        viewModel.updateObjectImmediate(obj.id) { it.copy(visible = !it.visible) }
                    },
                    onToggleLocked = {
                        viewModel.updateObjectImmediate(obj.id) { it.copy(locked = !it.locked) }
                    },
                    onToggleMask = {
                        viewModel.updateObjectImmediate(obj.id) { it.copy(isMask = !it.isMask) }
                    },
                )
            }
            item { Spacer(Modifier.height(16.dp)) }
        }
    }
}

@Composable
private fun LayerRowItem(
    obj: SceneObject,
    selected: Boolean,
    onClick: () -> Unit,
    onMoveUp: () -> Unit,
    onMoveDown: () -> Unit,
    onToggleVisible: () -> Unit,
    onToggleLocked: () -> Unit,
    onToggleMask: () -> Unit,
) {
    val bgColor = if (selected) {
        MaterialTheme.colorScheme.secondaryContainer
    } else {
        MaterialTheme.colorScheme.surfaceContainerHigh
    }

    Row(
        modifier = Modifier
            .fillMaxWidth()
            .padding(start = if (obj.groupId != null) 18.dp else 0.dp)
            .clip(RoundedCornerShape(14.dp))
            .background(bgColor)
            .clickable(onClick = onClick)
            .padding(horizontal = 12.dp, vertical = 6.dp),
        horizontalArrangement = Arrangement.SpaceBetween,
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Column(modifier = Modifier.weight(1f)) {
            Text(
                text = obj.name.ifBlank { obj.id },
                style = MaterialTheme.typography.bodyMedium,
                maxLines = 1,
                overflow = TextOverflow.Ellipsis,
            )
            Text(
                text = "z:${obj.zIndex} · ${obj.objType}" +
                    (if (obj.groupId != null) " · en grupo" else "") +
                    (if (obj.isMask) " · MÁSCARA" else "") +
                    (if (obj.liquidGlassEnabled || obj.shaderId == "liquid_glass") " · LiquidGlass" else ""),
                style = MaterialTheme.typography.labelSmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
                maxLines = 1,
                overflow = TextOverflow.Ellipsis,
            )
        }

        Row(verticalAlignment = Alignment.CenterVertically) {
            IconButton(onClick = onMoveUp) {
                Icon(Icons.Rounded.KeyboardArrowUp, contentDescription = "Subir capa")
            }
            IconButton(onClick = onMoveDown) {
                Icon(Icons.Rounded.KeyboardArrowDown, contentDescription = "Bajar capa")
            }
            IconButton(onClick = onToggleMask) {
                Icon(
                    Icons.Rounded.Layers,
                    contentDescription = "Máscara",
                    tint = if (obj.isMask) {
                        MaterialTheme.colorScheme.primary
                    } else {
                        MaterialTheme.colorScheme.onSurfaceVariant
                    },
                )
            }
            IconButton(onClick = onToggleVisible) {
                Icon(
                    if (obj.visible) Icons.Rounded.Visibility else Icons.Rounded.VisibilityOff,
                    contentDescription = "Visibilidad",
                )
            }
            IconButton(onClick = onToggleLocked) {
                Icon(
                    if (obj.locked) Icons.Rounded.Lock else Icons.Rounded.LockOpen,
                    contentDescription = "Bloquear",
                )
            }
        }
    }
}
