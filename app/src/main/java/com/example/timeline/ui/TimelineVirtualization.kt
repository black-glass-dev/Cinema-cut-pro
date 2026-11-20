package com.example.timeline.ui

import com.example.timeline.core.TimelineClip
import com.example.timeline.core.TimelineProject

/** An on-screen timeline item retained by the canvas' viewport culling pass. */
data class VisibleTimelineClip(val trackIndex: Int, val clip: TimelineClip)

/** UI-only culling; edit results and timeline-time semantics stay in Timeline Core v2. */
object TimelineVirtualization {
    fun visibleClips(
        project: TimelineProject,
        scrollX: Float,
        scrollY: Float,
        viewportWidthPx: Float,
        viewportHeightPx: Float,
        pixelsPerFrame: Float,
        trackHeightPx: Float = 80f,
        trackPaddingPx: Float = 12f,
        rulerHeightPx: Float = 40f,
        overscanPx: Float = 256f
    ): List<VisibleTimelineClip> {
        if (!scrollX.isFinite() || !scrollY.isFinite() ||
            !viewportWidthPx.isFinite() || !viewportHeightPx.isFinite() ||
            !pixelsPerFrame.isFinite() || pixelsPerFrame <= 0f ||
            viewportWidthPx <= 0f || viewportHeightPx <= 0f
        ) return emptyList()

        val leftBound = (scrollX - overscanPx).coerceAtLeast(0f)
        val rightBound = scrollX + viewportWidthPx + overscanPx
        val topBound = (scrollY - overscanPx).coerceAtLeast(0f)
        val bottomBound = scrollY + viewportHeightPx + overscanPx
        val rowStride = trackHeightPx + trackPaddingPx
        val visible = ArrayList<VisibleTimelineClip>()
        project.tracks.forEachIndexed { trackIndex, track ->
            val trackTop = rulerHeightPx + trackIndex * rowStride
            if (trackTop + trackHeightPx < topBound || trackTop > bottomBound) return@forEachIndexed
            track.clips.forEach { clip ->
                val clipEndFrame = try {
                    Math.addExact(clip.timelineStart, clip.duration)
                } catch (_: ArithmeticException) {
                    Long.MAX_VALUE
                }
                val left = TimelineUiGeometry.frameToX(clip.timelineStart, pixelsPerFrame)
                val right = TimelineUiGeometry.frameToX(clipEndFrame, pixelsPerFrame)
                if (right >= leftBound && left <= rightBound) {
                    visible += VisibleTimelineClip(trackIndex, clip)
                }
            }
        }
        return visible
    }
}
