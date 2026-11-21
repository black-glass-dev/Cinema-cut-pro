package com.example.timeline.ui

import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.example.timeline.core.TimelineClip
import com.example.timeline.media.MediaAsset

@Composable
fun AdvancedTimelineClipView(
    clip: TimelineClip,
    mediaAsset: MediaAsset?,
    isSelected: Boolean,
    pixelsPerFrame: Float,
    scrollX: Float,
    viewportWidth: Float,
    modifier: Modifier = Modifier
) {
    val durationFrames = clip.timelineEnd - clip.timelineStart
    val widthPx = durationFrames * pixelsPerFrame
    val widthDp = with(androidx.compose.ui.platform.LocalDensity.current) { widthPx.toDp() }

    val clipColor = Color(0xFF0055A4) // A nice cinematic blue for video clips
    val borderColor = if (isSelected) Color.White else Color.Black
    val borderWidth = if (isSelected) 2.dp else 1.dp

    Box(
        modifier = modifier
            .width(widthDp)
            .fillMaxHeight()
            .clip(RoundedCornerShape(4.dp))
            .background(clipColor)
            .border(borderWidth, borderColor, RoundedCornerShape(4.dp))
    ) {
        timelineClipLabel(clip, mediaAsset, widthDp.value)?.let { label ->
            Text(
                text = label,
                color = Color.White,
                fontSize = 10.sp,
                maxLines = 1,
                softWrap = false,
                overflow = TextOverflow.Clip,
                modifier = Modifier.align(Alignment.CenterStart).padding(horizontal = 8.dp)
            )
        }
        
        if (isSelected) {
            // Left Trim Handle
            Box(
                modifier = Modifier
                    .fillMaxHeight()
                    .width(8.dp)
                    .background(Color.White.copy(alpha = 0.5f))
                    .align(Alignment.CenterStart)
            )
            // Right Trim Handle
            Box(
                modifier = Modifier
                    .fillMaxHeight()
                    .width(8.dp)
                    .background(Color.White.copy(alpha = 0.5f))
                    .align(Alignment.CenterEnd)
            )
        }
    }
}
