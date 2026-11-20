package com.example.timeline.core

import java.util.UUID

/**
 * Legacy nested-sequence authoring is not supported by the Timeline Core v2 project protocol.
 * Kept only for source migration; call-sites are compile-guarded and the editor no longer exposes it.
 */
@Deprecated(
    message = "Nested/compound timeline edits are not implemented by Timeline Core v2",
    level = DeprecationLevel.ERROR
)
object CompoundClipEngine {
    fun compoundClipFromSelection(
        project: TimelineProject,
        selectedClipIds: List<String>,
        newSequenceName: String,
        destTrackId: String
    ): TimelineResult {
        if (selectedClipIds.isEmpty()) return TimelineResult(project, "No clips selected")
        val selectedClipsWithTrack = project.tracks.flatMap { track ->
            track.clips.filter { it.id in selectedClipIds }.map { it to track }
        }
        if (selectedClipsWithTrack.isEmpty()) return TimelineResult(project, "Clips not found")
        if (selectedClipsWithTrack.any { it.second.isLocked || it.first.isLocked }) {
            return TimelineResult(project, "Cannot compound locked clips/tracks")
        }

        val earliestStart = selectedClipsWithTrack.minOf { it.first.timelineStart }
        val latestEnd = selectedClipsWithTrack.maxOf { it.first.timelineEnd }
        val sequenceDuration = try {
            Math.subtractExact(latestEnd, earliestStart)
        } catch (_: ArithmeticException) {
            return TimelineResult(project, "TL_E_OVERFLOW")
        }
        val newTracks = selectedClipsWithTrack.groupBy { it.second.id }.map { (originalTrackId, entries) ->
            val originalTrack = entries.first().second
            val nestedTrack = TimelineTrack(name = originalTrack.name, type = originalTrack.type)
            nestedTrack.copy(
                clips = entries.map { (clip, _) ->
                    val relativeStart = try {
                        Math.subtractExact(clip.timelineStart, earliestStart)
                    } catch (_: ArithmeticException) {
                        return TimelineResult(project, "TL_E_OVERFLOW")
                    }
                    clip.copy(timelineStart = relativeStart, trackId = nestedTrack.id)
                }.sortedBy { it.timelineStart }
            )
        }
        val newSequence = TimelineSequence(
            settings = project.settings.copy(projectName = newSequenceName),
            tracks = newTracks
        )
        val deleted = TimelineCoreV2Adapter.applyEdit(
            project,
            CoreV2EditCommand(kind = CoreV2EditKind.DELETE, clipIds = selectedClipIds)
        )
        if (deleted is CoreV2EditOutcome.Rejected) return TimelineResult(project, deleted.errorCode)
        val afterDelete = (deleted as CoreV2EditOutcome.Applied).project
        val withSequence = NestedTimelineEngine.createSequence(afterDelete, newSequence)
        val nestedClip = TimelineClip(
            trackId = destTrackId,
            mediaId = newSequence.id,
            name = newSequenceName,
            sourceIn = 0L,
            sourceOut = sequenceDuration,
            timelineStart = earliestStart,
            type = ClipType.NESTED_SEQUENCE
        )
        val inserted = CoreTimelineEngine.addClip(withSequence, destTrackId, nestedClip)
        return if (inserted.isSuccess) inserted else TimelineResult(project, inserted.error)
    }

    fun decomposeCompoundClip(
        project: TimelineProject,
        compoundClipId: String
    ): TimelineResult {
        val pair = project.tracks.flatMap { track ->
            track.clips.filter { it.id == compoundClipId }.map { track to it }
        }.firstOrNull() ?: return TimelineResult(project, "Compound clip not found")
        val (parentTrack, compoundClip) = pair
        if (parentTrack.isLocked || compoundClip.isLocked) {
            return TimelineResult(project, "Track or clip is locked")
        }
        if (compoundClip.type != ClipType.NESTED_SEQUENCE) {
            return TimelineResult(project, "Clip is not a nested sequence")
        }
        val sequence = project.sequences.find { it.id == compoundClip.mediaId }
            ?: return TimelineResult(project, "Sequence reference not found")
        val deleted = TimelineCoreV2Adapter.applyEdit(
            project,
            CoreV2EditCommand(kind = CoreV2EditKind.DELETE, clipIds = listOf(compoundClipId))
        )
        if (deleted is CoreV2EditOutcome.Rejected) return TimelineResult(project, deleted.errorCode)
        var currentProject = (deleted as CoreV2EditOutcome.Applied).project

        for (sequenceTrack in sequence.tracks) {
            val newTrack = TimelineTrack(
                name = "${sequence.name} - ${sequenceTrack.name}",
                type = sequenceTrack.type
            )
            currentProject = CoreTimelineEngine.addTrack(currentProject, newTrack).project
            val addedTrackId = newTrack.id
            for (sequenceClip in sequenceTrack.clips) {
                val sequenceClipEnd = try {
                    Math.addExact(sequenceClip.timelineStart, sequenceClip.duration)
                } catch (_: ArithmeticException) {
                    return TimelineResult(project, "TL_E_OVERFLOW")
                }
                if (sequenceClipEnd <= compoundClip.sourceIn || sequenceClip.timelineStart >= compoundClip.sourceOut) {
                    continue
                }
                val visibleStart = maxOf(sequenceClip.timelineStart, compoundClip.sourceIn)
                val visibleEnd = minOf(sequenceClipEnd, compoundClip.sourceOut)
                val trimStart = try {
                    Math.subtractExact(visibleStart, sequenceClip.timelineStart)
                } catch (_: ArithmeticException) {
                    return TimelineResult(project, "TL_E_OVERFLOW")
                }
                val trimEnd = try {
                    Math.subtractExact(sequenceClipEnd, visibleEnd)
                } catch (_: ArithmeticException) {
                    return TimelineResult(project, "TL_E_OVERFLOW")
                }
                val sourceIn = try {
                    Math.addExact(sequenceClip.sourceIn, trimStart)
                } catch (_: ArithmeticException) {
                    return TimelineResult(project, "TL_E_OVERFLOW")
                }
                val sourceOut = try {
                    Math.subtractExact(sequenceClip.sourceOut, trimEnd)
                } catch (_: ArithmeticException) {
                    return TimelineResult(project, "TL_E_OVERFLOW")
                }
                val timelineStart = try {
                    Math.addExact(compoundClip.timelineStart, Math.subtractExact(visibleStart, compoundClip.sourceIn))
                } catch (_: ArithmeticException) {
                    return TimelineResult(project, "TL_E_OVERFLOW")
                }
                val decomposedClip = sequenceClip.copy(
                    id = UUID.randomUUID().toString(),
                    trackId = addedTrackId,
                    sourceIn = sourceIn,
                    sourceOut = sourceOut,
                    timelineStart = timelineStart
                )
                val inserted = CoreTimelineEngine.addClip(currentProject, addedTrackId, decomposedClip)
                if (!inserted.isSuccess) return TimelineResult(project, inserted.error)
                currentProject = inserted.project
            }
        }
        return TimelineResult(currentProject)
    }
}
