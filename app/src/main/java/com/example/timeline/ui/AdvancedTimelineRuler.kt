package com.example.timeline.ui

import androidx.compose.foundation.Canvas
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.nativeCanvas
import androidx.compose.ui.unit.dp
import com.example.timeline.core.Rational
import kotlin.math.ceil
import kotlin.math.floor

@Composable
fun AdvancedTimelineRuler(
    scrollX: Float,
    viewportWidth: Int,
    pixelsPerFrame: Float,
    frameRate: Rational,
    modifier: Modifier = Modifier
) {
    Canvas(
        modifier = modifier
            .fillMaxSize()
            .background(Color(0xFF1E1E1E))
    ) {
        val width = size.width
        val height = size.height
        drawLine(
            color = Color.DarkGray,
            start = Offset(0f, height),
            end = Offset(width, height),
            strokeWidth = 1.dp.toPx()
        )
        if (!pixelsPerFrame.isFinite() || pixelsPerFrame <= 0f ||
            frameRate.numerator <= 0 || frameRate.denominator <= 0
        ) return@Canvas

        val nominalFps = ((frameRate.numerator.toLong() + frameRate.denominator / 2L) /
            frameRate.denominator).coerceAtLeast(1L)
        val startFrame = floor((scrollX.coerceAtLeast(0f) / pixelsPerFrame).toDouble())
            .toLong().coerceAtLeast(0L)
        val endFrame = ceil(((scrollX.coerceAtLeast(0f) + width) / pixelsPerFrame).toDouble())
            .toLong().coerceAtLeast(startFrame)
        val stepFrames = when {
            pixelsPerFrame * nominalFps.toFloat() < 5f -> nominalFps * 10L
            pixelsPerFrame * nominalFps.toFloat() < 20f -> nominalFps * 5L
            else -> nominalFps
        }.coerceAtLeast(1L)
        val firstTick = startFrame / stepFrames * stepFrames
        val paint = android.graphics.Paint().apply {
            color = android.graphics.Color.LTGRAY
            textSize = 10.dp.toPx()
            isAntiAlias = true
        }

        var frame = firstTick
        while (frame <= endFrame) {
            val x = TimelineUiGeometry.frameToX(frame, pixelsPerFrame) - scrollX
            if (x in 0f..width) {
                val isMajor = frame % (nominalFps * 10L) == 0L
                val tickHeight = if (isMajor) height * 0.5f else height * 0.25f
                drawLine(
                    color = Color.Gray,
                    start = Offset(x, height - tickHeight),
                    end = Offset(x, height),
                    strokeWidth = 1f
                )
                if (isMajor) {
                    val timecode = TimecodeFormatter.formatTimecode(frame, frameRate)
                    drawContext.canvas.nativeCanvas.drawText(
                        timecode,
                        x + 4.dp.toPx(),
                        height - tickHeight - 4.dp.toPx(),
                        paint
                    )
                }
            }
            if (Long.MAX_VALUE - frame < stepFrames) break
            frame += stepFrames
        }
    }
}
