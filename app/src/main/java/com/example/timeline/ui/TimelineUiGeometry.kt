package com.example.timeline.ui

import kotlin.math.floor

/** Presentation-only frame/pixel conversion. Timeline model and edit math remain integer/native. */
object TimelineUiGeometry {
    fun frameToX(frame: Long, pixelsPerFrame: Float): Float {
        if (!pixelsPerFrame.isFinite() || pixelsPerFrame <= 0f) return 0f
        val pixel = frame.toDouble() * pixelsPerFrame.toDouble()
        return pixel.coerceIn(-Float.MAX_VALUE.toDouble(), Float.MAX_VALUE.toDouble()).toFloat()
    }

    fun xToFrame(x: Float, pixelsPerFrame: Float): Long {
        if (!x.isFinite() || !pixelsPerFrame.isFinite() || pixelsPerFrame <= 0f) return 0L
        val value = x.toDouble() / pixelsPerFrame.toDouble()
        if (value >= Long.MAX_VALUE.toDouble()) return Long.MAX_VALUE
        if (value <= Long.MIN_VALUE.toDouble()) return Long.MIN_VALUE
        return floor(value + 0.5).toLong()
    }
}
