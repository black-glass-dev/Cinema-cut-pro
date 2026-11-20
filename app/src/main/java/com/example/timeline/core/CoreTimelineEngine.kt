package com.example.timeline.core

data class TimelineResult(
    val project: TimelineProject,
    val error: String? = null
) {
    val isSuccess: Boolean get() = error == null
}

object CoreTimelineEngine {
    fun addTrack(project: TimelineProject, track: TimelineTrack): TimelineResult {
        if (track.id.isBlank()) return TimelineResult(project, "TL_E_INVALID_ARGUMENT")
        if (project.tracks.any { it.id == track.id }) return TimelineResult(project, "TL_E_DUPLICATE_ID")
        return TimelineResult(project.copy(tracks = project.tracks + track))
    }

    /** Track removal is metadata-only and may not silently delete clips or bypass clip locks. */
    fun removeTrack(project: TimelineProject, trackId: String): TimelineResult {
        val track = project.tracks.firstOrNull { it.id == trackId }
            ?: return TimelineResult(project, "TL_E_TRACK_NOT_FOUND")
        if (track.isLocked) return TimelineResult(project, "TL_E_LOCKED_TRACK")
        if (track.clips.isNotEmpty()) {
            return TimelineResult(project, "TL_E_INVALID_ARGUMENT")
        }
        return TimelineResult(project.copy(tracks = project.tracks.filterNot { it.id == trackId }))
    }

    fun renameTrack(project: TimelineProject, trackId: String, newName: String): TimelineResult {
        val newTracks = project.tracks.map { if (it.id == trackId) it.copy(name = newName) else it }
        return TimelineResult(project.copy(tracks = newTracks))
    }

    fun reorderTrack(project: TimelineProject, trackId: String, newIndex: Int): TimelineResult {
        val tracks = project.tracks.toMutableList()
        val trackIndex = tracks.indexOfFirst { it.id == trackId }
        if (trackIndex == -1) return TimelineResult(project, "Track not found")
        
        val track = tracks.removeAt(trackIndex)
        val targetIndex = newIndex.coerceIn(0, tracks.size)
        tracks.add(targetIndex, track)
        
        return TimelineResult(project.copy(tracks = tracks))
    }

    fun lockTrack(project: TimelineProject, trackId: String, isLocked: Boolean): TimelineResult {
        val newTracks = project.tracks.map { if (it.id == trackId) it.copy(isLocked = isLocked) else it }
        return TimelineResult(project.copy(tracks = newTracks))
    }

    fun setTrackMute(project: TimelineProject, trackId: String, isMuted: Boolean): TimelineResult {
        val newTracks = project.tracks.map { if (it.id == trackId) it.copy(isMuted = isMuted) else it }
        return TimelineResult(project.copy(tracks = newTracks))
    }

    fun setTrackSolo(project: TimelineProject, trackId: String, isSolo: Boolean): TimelineResult {
        val newTracks = project.tracks.map { if (it.id == trackId) it.copy(isSolo = isSolo) else it }
        return TimelineResult(project.copy(tracks = newTracks))
    }

    fun setTrackEnabled(project: TimelineProject, trackId: String, isEnabled: Boolean): TimelineResult {
        val newTracks = project.tracks.map { if (it.id == trackId) it.copy(isVisible = isEnabled) else it }
        return TimelineResult(project.copy(tracks = newTracks))
    }
    
    fun setTrackExpanded(project: TimelineProject, trackId: String, isExpanded: Boolean): TimelineResult {
        val newTracks = project.tracks.map { if (it.id == trackId) it.copy(isExpanded = isExpanded) else it }
        return TimelineResult(project.copy(tracks = newTracks))
    }

    fun updateTrackHeight(project: TimelineProject, trackId: String, height: Int): TimelineResult {
        val newTracks = project.tracks.map { if (it.id == trackId) it.copy(height = height) else it }
        return TimelineResult(project.copy(tracks = newTracks))
    }

    fun addClip(project: TimelineProject, trackId: String, clip: TimelineClip): TimelineResult {
        val insertedClip = clip.copy(trackId = trackId)
        return when (val outcome = TimelineCoreV2Adapter.applyEdit(
            project,
            CoreV2EditCommand(
                kind = CoreV2EditKind.INSERT,
                targetTrackId = trackId,
                insertedClip = insertedClip
            )
        )) {
            is CoreV2EditOutcome.Applied -> TimelineResult(outcome.project)
            is CoreV2EditOutcome.Rejected -> TimelineResult(project, outcome.errorCode)
        }
    }

    fun moveClipToTrack(
        project: TimelineProject,
        clipId: String,
        sourceTrackId: String,
        destTrackId: String,
        newTimelineStart: Long
    ): TimelineResult {
        val sourceTrack = project.tracks.firstOrNull { it.id == sourceTrackId }
            ?: return TimelineResult(project, "TL_E_TRACK_NOT_FOUND")
        if (sourceTrack.clips.none { it.id == clipId }) {
            return TimelineResult(project, "TL_E_CLIP_NOT_FOUND")
        }
        return moveClips(project, clipId, destTrackId, newTimelineStart, setOf(clipId))
    }

    /** One native Move command preserves the relative offset of every selected clip atomically. */
    fun moveClips(
        project: TimelineProject,
        primaryClipId: String,
        targetTrackId: String,
        primaryNewStartFrame: Long,
        clipIds: Set<String>
    ): TimelineResult {
        val sourceTrack = project.tracks.firstOrNull { track ->
            track.clips.any { it.id == primaryClipId }
        } ?: return TimelineResult(project, "TL_E_CLIP_NOT_FOUND")
        val destination = project.tracks.firstOrNull { it.id == targetTrackId }
            ?: return TimelineResult(project, "TL_E_TRACK_NOT_FOUND")
        val incompatible = (sourceTrack.type == TrackType.VIDEO && destination.type == TrackType.AUDIO) ||
            (sourceTrack.type == TrackType.AUDIO && destination.type == TrackType.VIDEO)
        if (incompatible) return TimelineResult(project, "TL_E_INVALID_ARGUMENT")
        val ids = (clipIds + primaryClipId).sorted()
        return when (val outcome = TimelineCoreV2Adapter.applyEdit(
            project,
            CoreV2EditCommand(
                kind = CoreV2EditKind.MOVE,
                clipId = primaryClipId,
                targetTrackId = targetTrackId,
                targetFrame = primaryNewStartFrame,
                clipIds = ids
            )
        )) {
            is CoreV2EditOutcome.Applied -> TimelineResult(outcome.project)
            is CoreV2EditOutcome.Rejected -> TimelineResult(project, outcome.errorCode)
        }
    }
}
