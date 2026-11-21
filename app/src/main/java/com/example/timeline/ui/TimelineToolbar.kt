package com.example.timeline.ui

import androidx.compose.foundation.background
import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material.icons.automirrored.filled.ArrowForward
import androidx.compose.material.icons.filled.ContentCut
import androidx.compose.material.icons.filled.Delete
import androidx.compose.material.icons.filled.Photo
import androidx.compose.material3.Button
import androidx.compose.material3.ButtonDefaults
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.IconButtonDefaults
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.material3.VerticalDivider
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp

@Composable
fun TimelineToolbar(
    canUndo: Boolean,
    canRedo: Boolean,
    onUndo: () -> Unit,
    onRedo: () -> Unit,
    onTrimLeft: () -> Unit,
    onTrimRight: () -> Unit,
    onSplit: () -> Unit,
    onSplitAndInsertFreezeFrame: () -> Unit,
    onDelete: () -> Unit,
    onZoomIn: () -> Unit,
    onZoomOut: () -> Unit,
    hasSelection: Boolean,
    canEditAtPlayhead: Boolean,
    canInsertFreezeFrame: Boolean,
    modifier: Modifier = Modifier
) {
    Box(
        modifier = modifier
            .fillMaxWidth()
            .height(52.dp)
            .background(TimelineDesignTokens.Toolbar, RoundedCornerShape(12.dp))
            .padding(horizontal = 6.dp, vertical = 5.dp)
    ) {
        Row(
            modifier = Modifier.fillMaxWidth().height(42.dp).horizontalScroll(rememberScrollState()),
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.spacedBy(4.dp)
        ) {
            IconButton(
                onClick = onUndo,
                enabled = canUndo,
                modifier = Modifier.size(40.dp),
                colors = toolbarIconColors()
            ) {
                Icon(Icons.AutoMirrored.Filled.ArrowBack, contentDescription = "Undo")
            }
            IconButton(
                onClick = onRedo,
                enabled = canRedo,
                modifier = Modifier.size(40.dp),
                colors = toolbarIconColors()
            ) {
                Icon(Icons.AutoMirrored.Filled.ArrowForward, contentDescription = "Redo")
            }

            VerticalDivider(
                modifier = Modifier.height(26.dp).padding(horizontal = 2.dp),
                color = TimelineDesignTokens.Divider
            )

            TimelineActionButton(
                icon = Icons.AutoMirrored.Filled.ArrowBack,
                label = "Trim Left",
                enabled = canEditAtPlayhead,
                onClick = onTrimLeft
            )
            TimelineActionButton(
                icon = Icons.AutoMirrored.Filled.ArrowForward,
                label = "Trim Right",
                enabled = canEditAtPlayhead,
                onClick = onTrimRight
            )
            TimelineActionButton(
                icon = Icons.Default.ContentCut,
                label = "Split",
                enabled = canEditAtPlayhead,
                onClick = onSplit
            )
            TimelineActionButton(
                icon = Icons.Default.Photo,
                label = "Split + Freeze",
                enabled = canInsertFreezeFrame,
                onClick = onSplitAndInsertFreezeFrame
            )

            IconButton(
                onClick = onDelete,
                enabled = hasSelection,
                modifier = Modifier.size(40.dp),
                colors = toolbarIconColors(destructive = true)
            ) {
                Icon(Icons.Default.Delete, contentDescription = "Delete clip")
            }

            VerticalDivider(
                modifier = Modifier.height(26.dp).padding(horizontal = 2.dp),
                color = TimelineDesignTokens.Divider
            )

            IconButton(onClick = onZoomOut, modifier = Modifier.size(40.dp), colors = toolbarIconColors()) {
                Text("−", color = Color.White, fontSize = 22.sp)
            }
            IconButton(onClick = onZoomIn, modifier = Modifier.size(40.dp), colors = toolbarIconColors()) {
                Text("+", color = Color.White, fontSize = 20.sp)
            }
        }
    }
}

@Composable
private fun TimelineActionButton(
    icon: ImageVector,
    label: String,
    enabled: Boolean,
    onClick: () -> Unit
) {
    Button(
        onClick = onClick,
        enabled = enabled,
        shape = RoundedCornerShape(9.dp),
        contentPadding = PaddingValues(horizontal = 8.dp, vertical = 0.dp),
        modifier = Modifier.height(38.dp),
        colors = ButtonDefaults.buttonColors(
            containerColor = TimelineDesignTokens.ToolbarButton,
            contentColor = Color.White,
            disabledContainerColor = TimelineDesignTokens.ToolbarButton,
            disabledContentColor = MaterialTheme.colorScheme.onSurface.copy(alpha = 0.35f)
        )
    ) {
        Icon(icon, contentDescription = null, modifier = Modifier.size(16.dp))
        Text(label, modifier = Modifier.padding(start = 4.dp), fontWeight = FontWeight.Medium, fontSize = 10.sp)
    }
}

@Composable
private fun toolbarIconColors(destructive: Boolean = false) = IconButtonDefaults.iconButtonColors(
    containerColor = TimelineDesignTokens.ToolbarButton,
    contentColor = if (destructive) TimelineDesignTokens.Accent else Color.White,
    disabledContainerColor = TimelineDesignTokens.ToolbarButton,
    disabledContentColor = Color.White.copy(alpha = 0.28f)
)
