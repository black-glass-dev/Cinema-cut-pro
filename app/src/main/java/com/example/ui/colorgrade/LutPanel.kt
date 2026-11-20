package com.example.ui.colorgrade

import android.net.Uri
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.grid.GridCells
import androidx.compose.foundation.lazy.grid.LazyVerticalGrid
import androidx.compose.foundation.lazy.grid.items
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Add
import androidx.compose.material.icons.filled.Delete
import androidx.compose.material.icons.filled.Check
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.example.model.colorgrade.lut.LutEntry
import com.example.model.colorgrade.lut.LutRepository
import kotlinx.coroutines.launch

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun LutPanel(
    selectedLutId: String?,
    intensity: Float,
    onLutSelected: (String?) -> Unit,
    onIntensityChanged: (Float) -> Unit,
    onIntensityChangeFinished: () -> Unit = {},
    modifier: Modifier = Modifier
) {
    val context = LocalContext.current
    val coroutineScope = rememberCoroutineScope()
    var luts by remember { mutableStateOf<List<LutEntry>>(emptyList()) }
    var isLoading by remember { mutableStateOf(true) }

    fun refreshLuts() {
        coroutineScope.launch {
            // Re-scan persisted files first so LUTs imported in earlier sessions reappear.
            LutRepository.initialize(context)
            luts = LutRepository.getLuts()
            isLoading = false
        }
    }

    LaunchedEffect(Unit) {
        refreshLuts()
    }

    val filePicker = rememberLauncherForActivityResult(ActivityResultContracts.GetContent()) { uri: Uri? ->
        if (uri != null) {
            coroutineScope.launch {
                val result = LutRepository.importLut(context, uri)
                if (result.isSuccess) {
                    refreshLuts()
                    onLutSelected(result.getOrNull()?.id)
                }
            }
        }
    }

    Column(modifier = modifier.heightIn(max = 240.dp).padding(16.dp)) {
        Row(
            modifier = Modifier.fillMaxWidth(),
            horizontalArrangement = Arrangement.SpaceBetween,
            verticalAlignment = Alignment.CenterVertically
        ) {
            Text("LUT Library", style = MaterialTheme.typography.titleMedium)
            Button(
                onClick = { filePicker.launch("*/*") },
                contentPadding = PaddingValues(horizontal = 8.dp, vertical = 0.dp),
                modifier = Modifier.height(32.dp)
            ) { 
                Icon(Icons.Default.Add, contentDescription = "Import LUT", modifier = Modifier.size(16.dp))
                Spacer(Modifier.width(4.dp))
                Text("Import LUT", fontSize = 12.sp)
            }
        }

        Spacer(Modifier.height(8.dp))

        if (selectedLutId != null) {
            Row(
                modifier = Modifier.fillMaxWidth().background(MaterialTheme.colorScheme.surfaceVariant, RoundedCornerShape(8.dp)).padding(horizontal = 8.dp),
                verticalAlignment = Alignment.CenterVertically
            ) {
                Text("Intensity: ${(intensity * 100).toInt()}%", style = MaterialTheme.typography.bodySmall, modifier = Modifier.weight(0.3f))
                Slider(
                    value = intensity,
                    onValueChange = onIntensityChanged,
                    onValueChangeFinished = onIntensityChangeFinished,
                    valueRange = 0f..1f,
                    modifier = Modifier.weight(0.7f)
                )
            }
            Spacer(Modifier.height(8.dp))
        }

        if (isLoading) {
            CircularProgressIndicator(modifier = Modifier.align(Alignment.CenterHorizontally))
        } else if (luts.isEmpty()) {
            Box(Modifier.fillMaxSize(), contentAlignment = Alignment.Center) {
                Text("No LUTs imported yet.", color = MaterialTheme.colorScheme.onSurfaceVariant)
            }
        } else {
            LazyVerticalGrid(
                columns = GridCells.Fixed(2),
                horizontalArrangement = Arrangement.spacedBy(8.dp),
                verticalArrangement = Arrangement.spacedBy(8.dp),
                modifier = Modifier.fillMaxSize()
            ) {
                item {
                    LutCard(
                        entry = null,
                        isSelected = selectedLutId == null,
                        onClick = { onLutSelected(null) },
                        onDelete = null
                    )
                }
                
                items(luts, key = { it.id }) { entry ->
                    LutCard(
                        entry = entry,
                        isSelected = selectedLutId == entry.id,
                        onClick = { onLutSelected(entry.id) },
                        onDelete = {
                            coroutineScope.launch {
                                val deleted = LutRepository.deleteLut(entry.id)
                                if (!deleted) return@launch
                                refreshLuts()
                                if (selectedLutId == entry.id) onLutSelected(null)
                            }
                        }
                    )
                }
            }
        }
    }
}

@Composable
fun LutCard(
    entry: LutEntry?,
    isSelected: Boolean,
    onClick: () -> Unit,
    onDelete: (() -> Unit)?
) {
    Card(
        modifier = Modifier
            .fillMaxWidth()
            .clickable(onClick = onClick),
        colors = CardDefaults.cardColors(
            containerColor = if (isSelected) MaterialTheme.colorScheme.primaryContainer else MaterialTheme.colorScheme.surfaceVariant
        ),
        border = if (isSelected) androidx.compose.foundation.BorderStroke(2.dp, MaterialTheme.colorScheme.primary) else null
    ) {
        Column(
            modifier = Modifier.padding(12.dp)
        ) {
            Row(
                horizontalArrangement = Arrangement.SpaceBetween,
                modifier = Modifier.fillMaxWidth()
            ) {
                Text(
                    text = entry?.name ?: "None",
                    style = MaterialTheme.typography.titleSmall,
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis,
                    color = if (isSelected) MaterialTheme.colorScheme.onPrimaryContainer else MaterialTheme.colorScheme.onSurface
                )
                if (isSelected) {
                    Icon(
                        Icons.Default.Check,
                        contentDescription = "Selected",
                        tint = MaterialTheme.colorScheme.primary,
                        modifier = Modifier.size(16.dp)
                    )
                }
            }
            if (entry != null) {
                Spacer(Modifier.height(4.dp))
                Text(
                    text = "${entry.size}x${entry.size}x${entry.size}",
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant
                )
            }
        }
    }
}
