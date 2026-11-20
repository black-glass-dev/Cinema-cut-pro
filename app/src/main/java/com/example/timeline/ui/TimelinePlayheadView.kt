package com.example.timeline.ui

import androidx.compose.foundation.Canvas
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.offset
import androidx.compose.foundation.layout.width
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.Path
import androidx.compose.ui.unit.dp

@Composable
fun TimelinePlayheadView(
    modifier: Modifier = Modifier
) {
    Canvas(
        modifier = modifier
            .width(20.dp)
            .fillMaxHeight()
            .offset(x = (-10).dp)
    ) {
        val w = size.width
        val h = size.height
        val centerX = w / 2f
        
        // Vertical line
        drawLine(
            color = TimelineDesignTokens.Accent,
            start = Offset(centerX, 0f),
            end = Offset(centerX, h),
            strokeWidth = 3f
        )
        
        // Cap
        val path = Path().apply {
            moveTo(0f, 0f)
            lineTo(w, 0f)
            lineTo(w, 15f)
            lineTo(centerX, 25f)
            lineTo(0f, 15f)
            close()
        }
        drawPath(path, TimelineDesignTokens.Accent)
    }
}
