package com.example.timeline.ui

import androidx.compose.foundation.background
import androidx.compose.material.icons.automirrored.filled.*
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.*
import androidx.compose.material3.*
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.unit.dp

@Composable
fun AdvancedTimelineToolPalette(
    activeTool: TimelineTool,
    onToolSelect: (TimelineTool) -> Unit,
    multiSelectEnabled: Boolean = false,
    onToggleMultiSelect: () -> Unit = {}
) {
    Surface(
        color = Color(0xFF222222),
        modifier = Modifier.width(60.dp).fillMaxHeight()
    ) {
        Column(
            modifier = Modifier.fillMaxSize().padding(vertical = 8.dp).verticalScroll(rememberScrollState()),
            horizontalAlignment = Alignment.CenterHorizontally,
            verticalArrangement = Arrangement.spacedBy(8.dp)
        ) {
            ToolPaletteButton(
                icon = Icons.Default.PanToolAlt,
                label = "Select",
                isActive = activeTool == TimelineTool.SELECTION,
                onClick = { onToolSelect(TimelineTool.SELECTION) }
            )
            ToolPaletteButton(
                icon = Icons.Default.Crop,
                label = "Trim",
                isActive = activeTool == TimelineTool.TRIM,
                onClick = { onToolSelect(TimelineTool.TRIM) }
            )
            ToolPaletteButton(
                icon = Icons.Default.ContentCut, // Using as blade placeholder
                label = "Blade",
                isActive = activeTool == TimelineTool.BLADE,
                onClick = { onToolSelect(TimelineTool.BLADE) }
            )
            ToolPaletteButton(
                icon = Icons.Default.SwapHoriz,
                label = "Ripple",
                isActive = activeTool == TimelineTool.RIPPLE,
                onClick = { onToolSelect(TimelineTool.RIPPLE) }
            )
            ToolPaletteButton(
                icon = Icons.Default.SyncAlt,
                label = "Roll",
                isActive = activeTool == TimelineTool.ROLL,
                onClick = { onToolSelect(TimelineTool.ROLL) }
            )
            ToolPaletteButton(
                icon = Icons.AutoMirrored.Filled.CompareArrows,
                label = "Slip",
                isActive = activeTool == TimelineTool.SLIP,
                onClick = { onToolSelect(TimelineTool.SLIP) }
            )
            ToolPaletteButton(
                icon = Icons.Default.MultipleStop,
                label = "Slide",
                isActive = activeTool == TimelineTool.SLIDE,
                onClick = { onToolSelect(TimelineTool.SLIDE) }
            )
            ToolPaletteButton(
                icon = Icons.Default.PanTool,
                label = "Pan",
                isActive = activeTool == TimelineTool.HAND,
                onClick = { onToolSelect(TimelineTool.HAND) }
            )
            ToolPaletteButton(
                icon = Icons.Default.Place,
                label = "Marker",
                isActive = activeTool == TimelineTool.MARKER,
                onClick = { onToolSelect(TimelineTool.MARKER) }
            )
            ToolPaletteButton(
                icon = Icons.Default.Layers,
                label = "Multi",
                isActive = multiSelectEnabled,
                onClick = onToggleMultiSelect
            )
        }
    }
}

@Composable
private fun ToolPaletteButton(
    icon: ImageVector,
    label: String,
    isActive: Boolean,
    onClick: () -> Unit
) {
    IconButton(
        onClick = onClick,
        modifier = Modifier
            .size(48.dp)
            .background(
                color = if (isActive) MaterialTheme.colorScheme.primary.copy(alpha = 0.2f) else Color.Transparent,
                shape = MaterialTheme.shapes.small
            )
    ) {
        Icon(
            imageVector = icon,
            contentDescription = label,
            tint = if (isActive) MaterialTheme.colorScheme.primary else Color.LightGray
        )
    }
}
