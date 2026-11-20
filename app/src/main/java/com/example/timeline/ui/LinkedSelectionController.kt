package com.example.timeline.ui

import com.example.timeline.core.TimelineProject

class LinkedSelectionController {
    fun getLinkedClips(clipId: String, project: TimelineProject, linkedSelectionEnabled: Boolean): Set<String> {
        val selected = mutableSetOf(clipId)
        if (!linkedSelectionEnabled) return selected

        val initialClip = project.tracks.flatMap { it.clips }.find { it.id == clipId } ?: return selected
        
        // Find all clips sharing the same mediaId and roughly matching in time
        // Or those with the same metadata["linkedGroupId"]
        
        val linkedGroupId = initialClip.linkedGroupId
        
        project.tracks.forEach { track ->
            track.clips.forEach { clip ->
                if (linkedGroupId != null && clip.linkedGroupId == linkedGroupId) {
                    selected.add(clip.id)
                } else if (linkedGroupId == null && clip.mediaId == initialClip.mediaId && clip.id != clipId) {
                    // Fallback heuristic: same media, same timelineStart and sourceIn
                    if (clip.timelineStart == initialClip.timelineStart && clip.sourceIn == initialClip.sourceIn) {
                        selected.add(clip.id)
                    }
                }
            }
        }
        
        return selected
    }
}
