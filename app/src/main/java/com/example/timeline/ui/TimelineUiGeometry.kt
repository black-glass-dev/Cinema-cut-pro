package com.example.timeline.ui

import com.example.timeline.core.TimelineProject
import kotlin.math.floor

/** Presentation-only frame/pixel conversion. Timeline model and edit math remain integer/native. */
object TimelineUiGeometry {
    fun maxScrollX(project: TimelineProject, pixelsPerFrame: Float, viewportWidthPx: Float): Float {
        if (!pixelsPerFrame.isFinite() || pixelsPerFrame <= 0f ||
            !viewportWidthPx.isFinite() || viewportWidthPx <= 0f
        ) return 0f
        val endFrame = project.tracks.asSequence()
            .flatMap { it.clips.asSequence() }
            .maxOfOrNull { it.timelineEnd }
            ?: 0L
        val contentWidth = endFrame.toDouble() * pixelsPerFrame.toDouble()
        if (!contentWidth.isFinite()) return Float.MAX_VALUE
        return (contentWidth - viewportWidthPx.toDouble())
            .coerceIn(0.0, Float.MAX_VALUE.toDouble())
            .toFloat()
    }

    fun followPlayheadScrollX(
        playheadFrame: Long,
        pixelsPerFrame: Float,
        centerX: Float,
        maxScrollX: Float
    ): Float {
        if (!centerX.isFinite() || centerX < 0f || !maxScrollX.isFinite() || maxScrollX < 0f) return 0f
        return (frameToX(playheadFrame, pixelsPerFrame) - centerX).coerceIn(0f, maxScrollX)
    }

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

    /** Converts a tap in the scrollable timeline pane into a sequence-bounded project frame. */
    fun tapToPlayheadFrame(
        localX: Float,
        scrollX: Float,
        pixelsPerFrame: Float,
        project: TimelineProject
    ): Long {
        val contentX = if (localX.isFinite() && scrollX.isFinite()) localX + scrollX else 0f
        return clampPlayheadFrame(project, xToFrame(contentX, pixelsPerFrame))
    }

    /** Scrubbing is navigation, not a Core v2 edit; keep it inside the represented sequence. */
    fun clampPlayheadFrame(project: TimelineProject, frame: Long): Long {
        val sequenceEnd = project.tracks.asSequence()
            .flatMap { it.clips.asSequence() }
            .maxOfOrNull { it.timelineEnd }
            ?: 0L
        return frame.coerceIn(0L, sequenceEnd)
    }
}
