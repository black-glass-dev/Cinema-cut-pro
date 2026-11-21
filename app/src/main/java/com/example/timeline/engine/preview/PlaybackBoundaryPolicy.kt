package com.example.timeline.engine.preview

import com.example.timeline.core.TimelineClip
import com.example.timeline.core.TimelineProject

internal sealed interface PlaybackBoundaryDecision {
    data class AdvanceToNextClip(val frame: Long, val clip: TimelineClip) : PlaybackBoundaryDecision
    data class PlayThroughGap(val gapStartFrame: Long, val nextClip: TimelineClip) : PlaybackBoundaryDecision
    data class StopAtLastFrame(val frame: Long) : PlaybackBoundaryDecision
}

internal fun playbackBoundaryDecision(
    project: TimelineProject,
    clip: TimelineClip
): PlaybackBoundaryDecision {
    val boundaryFrame = clip.timelineEnd
    val nextClip = TimelineFrameResolver.getTopmostVisibleVideoClip(project, boundaryFrame)
        ?: project.tracks.asSequence()
            .filter { it.type == com.example.timeline.core.TrackType.VIDEO && it.isVisible }
            .flatMap { it.clips.asSequence() }
            .filter {
                it.isEnabled && it.duration > 0L &&
                    it.type != com.example.timeline.core.ClipType.ADJUSTMENT && it.timelineStart >= boundaryFrame
            }
            .minByOrNull { it.timelineStart }
            ?.let { firstUpcoming ->
                TimelineFrameResolver.getTopmostVisibleVideoClip(project, firstUpcoming.timelineStart) ?: firstUpcoming
            }
    return when {
        nextClip == null -> PlaybackBoundaryDecision.StopAtLastFrame(
            frame = (boundaryFrame - 1L).coerceAtLeast(clip.timelineStart)
        )
        nextClip.timelineStart > boundaryFrame -> PlaybackBoundaryDecision.PlayThroughGap(boundaryFrame, nextClip)
        else -> PlaybackBoundaryDecision.AdvanceToNextClip(frame = boundaryFrame, clip = nextClip)
    }
}
