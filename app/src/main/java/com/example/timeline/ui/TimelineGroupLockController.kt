package com.example.timeline.ui

import com.example.timeline.core.TimelineProject

/** Describes the clip or persistent linked group targeted by the native lock command. */
data class TimelineGroupLockTarget(
    val clipId: String,
    val linkedGroupId: String?,
    val isLocked: Boolean,
    val memberClipIds: List<String>
) {
    val actionLabel: String
        get() = when {
            linkedGroupId != null && isLocked -> "Unlock Linked Group"
            linkedGroupId != null -> "Lock Linked Group"
            isLocked -> "Unlock Clip"
            else -> "Lock Clip"
        }
}

/** UI query only; lock state changes are submitted to Timeline Core v2 by TimelineViewModel. */
object TimelineGroupLockController {
    fun targetFor(project: TimelineProject, clipId: String): TimelineGroupLockTarget? {
        val anchor = project.tracks.asSequence()
            .flatMap { it.clips.asSequence() }
            .firstOrNull { it.id == clipId }
            ?: return null
        val groupId = anchor.linkedGroupId
        val members = project.tracks.asSequence()
            .flatMap { it.clips.asSequence() }
            .filter { clip ->
                if (groupId == null) clip.id == anchor.id else clip.linkedGroupId == groupId
            }
            .toList()
        if (members.none { it.id == anchor.id }) return null
        return TimelineGroupLockTarget(
            clipId = anchor.id,
            linkedGroupId = groupId,
            isLocked = members.any { it.isGroupLocked },
            memberClipIds = members.map { it.id }.sorted()
        )
    }
}
