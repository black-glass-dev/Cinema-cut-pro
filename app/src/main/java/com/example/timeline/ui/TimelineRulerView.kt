package com.example.timeline.ui

import androidx.compose.foundation.Canvas
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.nativeCanvas
import com.example.timeline.core.Rational
import kotlin.math.ceil
import kotlin.math.floor

@Composable
fun TimelineRulerView(
    scrollX: Float,
    viewportWidth: Int,
    pixelsPerFrame: Float,
    fpsRational: Rational,
    modifier: Modifier = Modifier
) {
    Canvas(modifier = modifier.fillMaxSize()) {
        val width = size.width
        val height = size.height
        drawRect(color = Color(0xFF141414))
        drawRect(
            color = Color.White.copy(alpha = 0.1f),
            topLeft = Offset(0f, height - 1f),
            size = androidx.compose.ui.geometry.Size(width, 1f)
        )
        if (!pixelsPerFrame.isFinite() || pixelsPerFrame <= 0f ||
            fpsRational.numerator <= 0 || fpsRational.denominator <= 0
        ) return@Canvas

        val nominalFps = ((fpsRational.numerator.toLong() + fpsRational.denominator / 2L) /
            fpsRational.denominator).coerceAtLeast(1L)
        val startFrame = floor((scrollX.coerceAtLeast(0f) / pixelsPerFrame).toDouble())
            .toLong().coerceAtLeast(0L)
        val endFrame = ceil(((scrollX.coerceAtLeast(0f) + width) / pixelsPerFrame).toDouble())
            .toLong().coerceAtLeast(startFrame)
        val (minorInterval, majorInterval) = when {
            pixelsPerFrame > 20f -> 1L to nominalFps
            pixelsPerFrame > 5f -> (nominalFps / 2L).coerceAtLeast(1L) to nominalFps
            pixelsPerFrame > 1f -> nominalFps to nominalFps * 10L
            pixelsPerFrame > 0.1f -> nominalFps * 10L to nominalFps * 60L
            else -> nominalFps * 60L to nominalFps * 300L
        }
        val firstTick = startFrame / minorInterval * minorInterval
        val paint = android.graphics.Paint().apply {
            color = android.graphics.Color.LTGRAY
            textSize = 24f
            isAntiAlias = true
        }
        var frame = firstTick
        while (frame <= endFrame) {
            val x = TimelineUiGeometry.frameToX(frame, pixelsPerFrame) - scrollX
            val isMajor = frame % majorInterval == 0L
            if (isMajor) {
                drawLine(
                    Color.White.copy(alpha = 0.8f),
                    start = Offset(x, height * 0.4f),
                    end = Offset(x, height),
                    strokeWidth = 2f
                )
                val timecode = TimecodeFormatter.formatTimecode(frame, fpsRational)
                drawContext.canvas.nativeCanvas.drawText(timecode, x + 4f, height * 0.6f, paint)
            } else {
                drawLine(
                    Color.White.copy(alpha = 0.3f),
                    start = Offset(x, height * 0.7f),
                    end = Offset(x, height),
                    strokeWidth = 2f
                )
            }
            if (Long.MAX_VALUE - frame < minorInterval) break
            frame += minorInterval
        }
    }
}
