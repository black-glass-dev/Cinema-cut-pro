package com.example.timeline.ui

import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.offset
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.Canvas
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clipToBounds
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.unit.IntOffset
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.example.timeline.core.TimelineMarker
import kotlin.math.roundToInt

/** Project markers drawn in viewport coordinates above the scrolled clip rows. */
@Composable
internal fun TimelineMarkerOverlay(
    markers: List<TimelineMarker>,
    pixelsPerFrame: Float,
    scrollX: Float,
    rulerHeightPx: Float,
    modifier: Modifier = Modifier
) {
    if (markers.isEmpty() || !pixelsPerFrame.isFinite() || pixelsPerFrame <= 0f || !scrollX.isFinite()) return

    Box(modifier = modifier.clipToBounds()) {
        Canvas(Modifier.fillMaxSize()) {
            markers.forEach { marker ->
                val x = TimelineUiGeometry.frameToX(marker.frame, pixelsPerFrame) - scrollX
                if (x < -8f || x > size.width + 8f) return@forEach
                val color = markerColor(marker.color)
                drawLine(
                    color = color.copy(alpha = 0.88f),
                    start = Offset(x, rulerHeightPx.coerceIn(0f, size.height)),
                    end = Offset(x, size.height),
                    strokeWidth = 1.5.dp.toPx()
                )
                drawCircle(color, radius = 4.dp.toPx(), center = Offset(x, (rulerHeightPx - 2.dp.toPx()).coerceAtLeast(0f)))
            }
        }
        markers.forEach { marker ->
            val x = (TimelineUiGeometry.frameToX(marker.frame, pixelsPerFrame) - scrollX).roundToInt()
            if (x < -160 || x > 20000) return@forEach
            Text(
                text = marker.name.ifBlank { "Marker" },
                color = markerColor(marker.color),
                fontSize = 9.sp,
                maxLines = 1,
                modifier = Modifier
                    .offset { IntOffset((x + 5).coerceAtLeast(0), 0) }
                    .widthIn(max = 120.dp)
                    .background(Color.Black.copy(alpha = 0.62f))
                    .padding(horizontal = 3.dp, vertical = 1.dp)
            )
        }
    }
}

private fun markerColor(value: String): Color = try {
    Color(android.graphics.Color.parseColor(value))
} catch (_: IllegalArgumentException) {
    Color(0xFFFFC247)
}
