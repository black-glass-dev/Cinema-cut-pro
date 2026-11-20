package com.example.timeline.ui

import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.IntrinsicSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.DropdownMenu
import androidx.compose.material3.DropdownMenuItem
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.unit.dp

@Composable
fun TimelineContextMenu(
    expanded: Boolean,
    onDismissRequest: () -> Unit,
    onSplit: () -> Unit,
    onDelete: () -> Unit,
    onTrimStartToPlayhead: () -> Unit = {},
    onTrimEndToPlayhead: () -> Unit = {},
    showGroupLock: Boolean = false,
    groupLockLabel: String = "Lock Clip",
    groupLockActionEnabled: Boolean = true,
    onToggleGroupLock: () -> Unit = {}
) {
    DropdownMenu(
        expanded = expanded,
        onDismissRequest = onDismissRequest,
        modifier = Modifier
            .width(IntrinsicSize.Min)
            .background(Color(0xFF2C2C2C), RoundedCornerShape(8.dp))
    ) {
        DropdownMenuItem(
            text = { Text("Split at Playhead", color = Color.White) },
            onClick = {
                onSplit()
                onDismissRequest()
            }
        )
        DropdownMenuItem(
            text = { Text("Trim Start to Playhead", color = Color.White) },
            onClick = {
                onTrimStartToPlayhead()
                onDismissRequest()
            }
        )
        DropdownMenuItem(
            text = { Text("Trim End to Playhead", color = Color.White) },
            onClick = {
                onTrimEndToPlayhead()
                onDismissRequest()
            }
        )
        if (showGroupLock) {
            DropdownMenuItem(
                enabled = groupLockActionEnabled,
                text = { Text(groupLockLabel, color = Color.White) },
                onClick = {
                    onToggleGroupLock()
                    onDismissRequest()
                }
            )
        }
        DropdownMenuItem(
            text = { Text("Delete Clip", color = Color(0xFFFF5252)) },
            onClick = {
                onDelete()
                onDismissRequest()
            }
        )
    }
}
