package com.example.timeline.ui

import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.rememberScrollState
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material.icons.automirrored.filled.ArrowForward
import androidx.compose.material.icons.filled.Delete
import androidx.compose.material3.Button
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.VerticalDivider
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp

@Composable
fun TimelineToolbar(
    onImportClick: () -> Unit,
    canUndo: Boolean,
    canRedo: Boolean,
    onUndo: () -> Unit,
    onRedo: () -> Unit,
    onSplit: () -> Unit,
    onDelete: () -> Unit,
    onZoomIn: () -> Unit,
    onZoomOut: () -> Unit,
    hasSelection: Boolean
) {
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .height(48.dp)
            .horizontalScroll(rememberScrollState()),
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(4.dp)
    ) {
        IconButton(onClick = onUndo, enabled = canUndo) {
            Icon(Icons.AutoMirrored.Filled.ArrowBack, contentDescription = "Undo")
        }
        IconButton(onClick = onRedo, enabled = canRedo) {
            Icon(Icons.AutoMirrored.Filled.ArrowForward, contentDescription = "Redo")
        }
        VerticalDivider(modifier = Modifier.height(24.dp))
        Button(
            onClick = onImportClick,
            contentPadding = PaddingValues(horizontal = 12.dp, vertical = 0.dp),
            modifier = Modifier.height(32.dp)
        ) {
            Text("Import video")
        }
        TextButton(onClick = onSplit, enabled = hasSelection) { Text("Split") }
        IconButton(onClick = onDelete, enabled = hasSelection) {
            Icon(Icons.Default.Delete, contentDescription = "Delete clip")
        }
        Spacer(modifier = Modifier.width(8.dp))
        TextButton(onClick = onZoomOut) { Text("−", color = MaterialTheme.colorScheme.onSurface) }
        TextButton(onClick = onZoomIn) { Text("+", color = MaterialTheme.colorScheme.onSurface) }
    }
}
