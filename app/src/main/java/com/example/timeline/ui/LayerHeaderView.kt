package com.example.timeline.ui

import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.Icon
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.res.painterResource
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.example.timeline.core.TrackType
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Lock
import androidx.compose.material.icons.filled.Visibility

import androidx.compose.foundation.clickable
import androidx.compose.material3.DropdownMenu
import androidx.compose.material3.DropdownMenuItem
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.getValue
import androidx.compose.runtime.setValue
import androidx.compose.ui.text.style.TextOverflow
import com.example.timeline.core.TimelineTrack

@Composable
fun LayerHeaderView(
    track: TimelineTrack,
    trackIndex: Int,
    onRename: (String) -> Unit = {},
    onDelete: () -> Unit = {},
    onMoveUp: () -> Unit = {},
    onMoveDown: () -> Unit = {},
    modifier: Modifier = Modifier
) {
    Row(
        modifier = modifier
            .fillMaxWidth()
            .fillMaxHeight()
            .background(TimelineDesignTokens.TrackHeader)
            .border(0.5.dp, TimelineDesignTokens.Divider)
            .padding(horizontal = 8.dp),
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.SpaceBetween
    ) {
        var showMenu by remember { mutableStateOf(false) }

        Column(
            verticalArrangement = Arrangement.Center,
            modifier = Modifier.weight(1f).clickable { showMenu = true }
        ) {
            Text(
                text = track.name,
                color = Color.White,
                fontSize = 12.sp,
                fontWeight = FontWeight.Bold,
                maxLines = 1,
                overflow = TextOverflow.Ellipsis
            )
            Text(
                text = if (track.type == TrackType.VIDEO) "V${trackIndex + 1}" else "A${trackIndex + 1}",
                color = Color.Gray,
                fontSize = 10.sp
            )
            
            DropdownMenu(expanded = showMenu, onDismissRequest = { showMenu = false }) {
                DropdownMenuItem(text = { Text("Rename") }, onClick = { showMenu = false; onRename(track.name) })
                DropdownMenuItem(text = { Text("Move Up") }, onClick = { showMenu = false; onMoveUp() })
                DropdownMenuItem(text = { Text("Move Down") }, onClick = { showMenu = false; onMoveDown() })
                DropdownMenuItem(text = { Text("Delete") }, onClick = { showMenu = false; onDelete() })
            }
        }
        Row(horizontalArrangement = Arrangement.spacedBy(2.dp)) {
            Icon(Icons.Default.Visibility, contentDescription = "Visible", tint = if (track.isVisible) Color.LightGray else Color.DarkGray, modifier = Modifier.size(14.dp))
            Icon(Icons.Default.Lock, contentDescription = "Lock", tint = if (track.isLocked) Color.LightGray else Color.DarkGray, modifier = Modifier.size(14.dp))
        }
    }
}
