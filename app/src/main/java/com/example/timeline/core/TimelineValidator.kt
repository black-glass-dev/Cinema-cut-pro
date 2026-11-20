package com.example.timeline.core

/** Deprecated duplicate Kotlin edit validation. All new edits must use Timeline Core v2. */
@Deprecated(
    message = "Compile guard: use TimelineCoreV2Adapter/Core v2 validation",
    level = DeprecationLevel.ERROR
)
object TimelineValidator {
    fun validateClip(clip: TimelineClip): List<String> {
        val errors = mutableListOf<String>()
        if (clip.timelineStart < 0) errors.add("Timeline start cannot be below 0")
        if (clip.sourceOut < clip.sourceIn) errors.add("Source out cannot be before source in")
        if (clip.duration < 0) errors.add("Negative duration is invalid") 
        return errors
    }

    fun detectTrackCollision(track: TimelineTrack, newClip: TimelineClip): Boolean {
        // Track types like TEXT or ADJUSTMENT inherently allow overlaps.
        val allowsOverlaps = track.type == TrackType.TEXT || track.type == TrackType.ADJUSTMENT
        if (allowsOverlaps) return false

        return track.clips.any { existingClip ->
            existingClip.id != newClip.id &&
            newClip.timelineStart < existingClip.timelineEnd &&
            newClip.timelineEnd > existingClip.timelineStart
        }
    }

    fun validateTrack(track: TimelineTrack): List<String> {
        val errors = mutableListOf<String>()
        for (i in track.clips.indices) {
            val clip = track.clips[i]
            errors.addAll(validateClip(clip).map { "Clip ${clip.id}: $it" })
            
            for (j in i + 1 until track.clips.size) {
                val other = track.clips[j]
                if (clip.timelineStart < other.timelineEnd && clip.timelineEnd > other.timelineStart) {
                    val allowsOverlaps = track.type == TrackType.TEXT || track.type == TrackType.ADJUSTMENT
                    if (!allowsOverlaps) {
                       errors.add("Collision detected between ${clip.id} and ${other.id} on track ${track.id}")
                    }
                }
            }
        }
        return errors
    }

    fun isValidMove(project: TimelineProject, clipId: String, targetTrackId: String, newStartFrame: Long): Boolean {
        val originalTrack = project.tracks.find { it.clips.any { c -> c.id == clipId } } ?: return false
        val originalClip = originalTrack.clips.find { it.id == clipId } ?: return false
        val targetTrack = project.tracks.find { it.id == targetTrackId } ?: return false
        
        if (targetTrack.isLocked) return false
        
        val newClip = originalClip.copy(timelineStart = newStartFrame)
        return !detectTrackCollision(targetTrack, newClip)
    }
}
