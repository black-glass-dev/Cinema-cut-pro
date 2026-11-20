package com.example.ui.colorgrade

import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.itemsIndexed
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Add
import androidx.compose.material.icons.filled.Delete
import androidx.compose.material.icons.filled.Edit
import androidx.compose.material.icons.filled.KeyboardArrowDown
import androidx.compose.material.icons.filled.KeyboardArrowUp
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp

import androidx.compose.material.icons.filled.Edit
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.TextButton
import androidx.compose.material3.OutlinedTextField

import com.example.model.colorgrade.ColorGradeLayerStack
import com.example.model.colorgrade.ColorLayerType
import com.example.ui.ColorLayerViewModel

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun ColorLayerStackPanel(
    clipId: String,
    layerStack: ColorGradeLayerStack,
    viewModel: ColorLayerViewModel,
    modifier: Modifier = Modifier
) {
    val selectedLayerId by viewModel.selectedLayerId.collectAsState()
    var showAddMenu by remember { mutableStateOf(false) }

    var showRenameDialog by remember { mutableStateOf<String?>(null) }
    var renameText by remember { mutableStateOf("") }
    
    if (showRenameDialog != null) {
        AlertDialog(
            onDismissRequest = { showRenameDialog = null },
            title = { Text("Rename Layer") },
            text = {
                OutlinedTextField(
                    value = renameText,
                    onValueChange = { renameText = it },
                    singleLine = true
                )
            },
            confirmButton = {
                TextButton(onClick = {
                    viewModel.renameLayer(clipId, showRenameDialog!!, renameText)
                    showRenameDialog = null
                }) { Text("Save") }
            },
            dismissButton = {
                TextButton(onClick = { showRenameDialog = null }) { Text("Cancel") }
            }
        )
    }


    Column(modifier = modifier.fillMaxSize().background(Color(0xFF151515))) {
        Row(
            modifier = Modifier.fillMaxWidth().padding(8.dp),
            horizontalArrangement = Arrangement.SpaceBetween,
            verticalAlignment = Alignment.CenterVertically
        ) {
            Text("NODES / LAYERS", color = Color.White, fontSize = 12.sp, fontWeight = FontWeight.Bold, letterSpacing = 1.sp)
            Box {
                IconButton(onClick = { showAddMenu = true }, modifier = Modifier.size(24.dp)) {
                    Icon(Icons.Default.Add, contentDescription = "Add Layer", tint = Color.LightGray)
                }
                DropdownMenu(expanded = showAddMenu, onDismissRequest = { showAddMenu = false }) {
                    ColorLayerType.values().forEach { type ->
                        DropdownMenuItem(
                            text = { Text(type.name.replace("_", " ")) },
                            onClick = {
                                viewModel.addLayer(clipId, type, type.name.replace("_", " "))
                                showAddMenu = false
                            }
                        )
                    }
                }
            }
        }
        Divider(color = Color.DarkGray)
        
        LazyColumn(modifier = Modifier.fillMaxSize()) {
            itemsIndexed(layerStack.layers) { index, layer ->
                val isSelected = selectedLayerId == layer.id || (selectedLayerId == null && index == layerStack.layers.size - 1)
                
                // If nothing was selected explicitly but this is the top layer, maybe auto-select it.
                // In production, the VM would handle default selection, but this is a fallback.
                
                Row(
                    modifier = Modifier
                        .fillMaxWidth()
                        .background(if (isSelected) MaterialTheme.colorScheme.primaryContainer.copy(alpha = 0.5f) else Color.Transparent)
                        .clickable { viewModel.selectLayer(layer.id) }
                        .padding(horizontal = 8.dp, vertical = 8.dp),
                    verticalAlignment = Alignment.CenterVertically
                ) {
                    Checkbox(
                        checked = layer.enabled,
                        onCheckedChange = { viewModel.toggleLayerEnabled(clipId, layer.id, it) },
                        modifier = Modifier.size(24.dp)
                    )
                    Spacer(modifier = Modifier.width(8.dp))
                    Column(modifier = Modifier.weight(1f)) {
                        Text(layer.name, color = Color.White, fontSize = 12.sp, fontWeight = if (isSelected) FontWeight.Bold else FontWeight.Normal)
                        Text(layer.type.name.replace("_", " "), color = Color.Gray, fontSize = 10.sp)
                        if (isSelected) {
                            Slider(
                                value = layer.opacity,
                                onValueChange = { viewModel.updateLayerOpacity(clipId, layer.id, it) },
                                onValueChangeFinished = { viewModel.endLayerOpacityChange() },
                                valueRange = 0f..1f,
                                modifier = Modifier.height(24.dp).padding(top = 4.dp)
                            )
                        }

                    }
                    if (index > 0) {
                        IconButton(onClick = { viewModel.reorderLayers(clipId, index, index - 1) }, modifier = Modifier.size(24.dp)) {
                            Icon(Icons.Default.KeyboardArrowUp, contentDescription = "Move Up", tint = Color.Gray)
                        }
                    }
                    if (index < layerStack.layers.size - 1) {
                        IconButton(onClick = { viewModel.reorderLayers(clipId, index, index + 1) }, modifier = Modifier.size(24.dp)) {
                            Icon(Icons.Default.KeyboardArrowDown, contentDescription = "Move Down", tint = Color.Gray)
                        }
                    }
                    
                    IconButton(onClick = { 
                        renameText = layer.name
                        showRenameDialog = layer.id
                    }, modifier = Modifier.size(24.dp)) {
                        Icon(Icons.Default.Edit, contentDescription = "Rename", tint = Color.Gray)
                    }
                    IconButton(onClick = { viewModel.removeLayer(clipId, layer.id) }, modifier = Modifier.size(24.dp)) {
                        Icon(Icons.Default.Delete, contentDescription = "Delete", tint = Color.Gray)
                    }
                }
                Divider(color = Color.DarkGray, thickness = 0.5.dp)
            }
        }
    }
}
