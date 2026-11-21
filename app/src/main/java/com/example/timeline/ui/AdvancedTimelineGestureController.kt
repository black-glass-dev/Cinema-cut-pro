package com.example.timeline.ui

import androidx.compose.ui.geometry.Offset
import com.example.timeline.core.CoreV2SnapOutcome
import com.example.timeline.core.TimelineClip
import com.example.timeline.core.TimelineCoreV2Adapter
import com.example.timeline.core.TimelineProject
import com.example.timeline.core.TimelineSnapSettings

object AdvancedTimelineGestureController {
    fun hitTest(
        x: Float,
        y: Float,
        project: TimelineProject,
        pixelsPerFrame: Float,
        trackHeight: Float = 80f,
        trackPadding: Float = 12f
    ): TimelineClip? {
        if (!x.isFinite() || !y.isFinite() || !pixelsPerFrame.isFinite() || pixelsPerFrame <= 0f) return null
        project.tracks.forEachIndexed { trackIndex, track ->
            val top = trackIndex * (trackHeight + trackPadding)
            val bottom = top + trackHeight
            track.clips.asReversed().forEach { clip ->
                val left = TimelineUiGeometry.frameToX(clip.timelineStart, pixelsPerFrame)
                val right = TimelineUiGeometry.frameToX(clip.timelineEnd, pixelsPerFrame)
                if (x in left..right && y in top..bottom) return clip
            }
        }
        return null
    }

    fun determineDragAction(
        startOffset: Offset,
        scrollX: Float,
        scrollY: Float,
        rulerHeightPx: Float,
        project: TimelineProject,
        pixelsPerFrame: Float,
        selectedClipIds: Set<String>,
        playheadFrame: Long = 0L,
        playheadHitRadiusPx: Float = 24f,
        trimHitRadiusPx: Float = 40f,
        trackHeightPx: Float = 80f,
        trackPaddingPx: Float = 12f
    ): TimelineDragAction {
        if (startOffset.y < rulerHeightPx) return TimelineDragAction.ScrubPlayhead
        if (!TimelineCoreV2Adapter.isAvailable()) return TimelineDragAction.Pan(scrollX, scrollY)

        val tapX = startOffset.x + scrollX
        val tapY = startOffset.y + scrollY - rulerHeightPx
        val hitClip = hitTest(tapX, tapY, project, pixelsPerFrame, trackHeightPx, trackPaddingPx)
        val selectedClip = project.tracks.withIndex().asSequence()
            .flatMap { (index, track) ->
                val trackTop = index * (trackHeightPx + trackPaddingPx)
                if (tapY in trackTop..(trackTop + trackHeightPx)) {
                    track.clips.asSequence().filter { it.id in selectedClipIds }
                } else emptySequence()
            }
            .firstOrNull()
        val trimClip = hitClip ?: selectedClip
        if (trimClip != null) {
            TimelineGestureController.trimActionAtEdge(trimClip, tapX, pixelsPerFrame, trimHitRadiusPx)
                ?.let { return it }
        }

        val playheadX = TimelineUiGeometry.frameToX(playheadFrame, pixelsPerFrame) - scrollX
        if (playheadHitRadiusPx.isFinite() && playheadHitRadiusPx >= 0f &&
            startOffset.x.isFinite() && kotlin.math.abs(startOffset.x - playheadX) <= playheadHitRadiusPx
        ) return TimelineDragAction.ScrubPlayhead
        if (hitClip == null) return TimelineDragAction.Pan(scrollX, scrollY)

        val startFrame = TimelineUiGeometry.xToFrame(tapX, pixelsPerFrame)
        val offsetFrames = try {
            Math.subtractExact(startFrame, hitClip.timelineStart)
        } catch (_: ArithmeticException) {
            return TimelineDragAction.Pan(scrollX, scrollY)
        }
        return TimelineDragAction.MoveClip(hitClip, offsetFrames)
    }

    /** Null means native snapping rejected/unavailable; callers must cancel the edit gesture. */
    fun snapFrame(
        inputFrame: Long,
        project: TimelineProject,
        playheadFrame: Long,
        pixelsPerFrame: Float,
        excludeClipId: String? = null,
        snapSettings: TimelineSnapSettings = TimelineSnapSettings()
    ): Long? {
        if (!TimelineCoreV2Adapter.isAvailable()) return null
        val thresholdFrames = TimelineCoreV2Adapter.pixelThresholdToFrames(
            snapSettings.snapThresholdPixels, pixelsPerFrame
        )
        return when (val result = TimelineCoreV2Adapter.snap(
            project = project,
            inputFrame = inputFrame,
            playheadFrame = playheadFrame,
            thresholdFrames = thresholdFrames,
            excludeClipId = excludeClipId,
            snapToPlayhead = snapSettings.snapToPlayhead,
            snapToClipEdges = snapSettings.snapToClips,
            snapToMarkers = snapSettings.snapToMarkers,
            gridIntervalFrames = if (snapSettings.snapToGrid) snapSettings.gridIntervalFrames else 0L
        )) {
            is CoreV2SnapOutcome.Snapped -> if (result.didSnap) result.frame else inputFrame
            is CoreV2SnapOutcome.Rejected -> null
        }
    }
}
