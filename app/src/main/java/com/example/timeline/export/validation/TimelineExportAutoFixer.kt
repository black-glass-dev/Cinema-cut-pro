package com.example.timeline.export.validation

import com.example.timeline.core.CoreV2EditCommand
import com.example.timeline.core.CoreV2EditKind
import com.example.timeline.core.TimelineProject
import com.example.timeline.core.TrackType

/**
 * Builds native move requests for the bounded cut-only export repair. It does not mutate or
 * validate a project; Timeline Core v2 validates and applies the returned command batch.
 */
object TimelineExportAutoFixer {
    fun compactVisibleVideoTrackCommands(project: TimelineProject): List<CoreV2EditCommand>? {
        val visibleVideoTracks = project.tracks.filter { track ->
            track.type == TrackType.VIDEO && track.isVisible && track.clips.any { it.isEnabled }
        }
        if (visibleVideoTracks.size != 1) return null

        val track = visibleVideoTracks.single()
        if (track.isLocked) return null
        val enabledClips = track.clips
            .filter { it.isEnabled }
            .sortedWith(compareBy({ it.timelineStart }, { it.id }))
        if (enabledClips.isEmpty() || enabledClips.any { it.duration <= 0L }) return null

        var nextStartFrame = 0L
        val commands = mutableListOf<CoreV2EditCommand>()
        for (clip in enabledClips) {
            if (clip.timelineStart != nextStartFrame && clip.isLocked) return null
            if (clip.timelineStart != nextStartFrame) {
                commands += CoreV2EditCommand(
                    kind = CoreV2EditKind.MOVE,
                    clipId = clip.id,
                    targetTrackId = track.id,
                    targetFrame = nextStartFrame
                )
            }
            nextStartFrame = try {
                Math.addExact(nextStartFrame, clip.duration)
            } catch (_: ArithmeticException) {
                return null
            }
        }
        return commands.takeIf { it.isNotEmpty() }
    }
}
