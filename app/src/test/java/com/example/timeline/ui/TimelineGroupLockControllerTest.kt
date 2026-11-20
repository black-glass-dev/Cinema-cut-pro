package com.example.timeline.ui

import com.example.timeline.core.TimelineClip
import com.example.timeline.core.TimelineProject
import com.example.timeline.core.TimelineTrack
import com.example.timeline.core.TrackType
import com.example.timeline.core.ProjectSettings
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Test

class TimelineGroupLockControllerTest {
    @Test
    fun persistentLinkedGroupTargetAggregatesLockStateAndMembers() {
        val first = clip("video", "video-track", "shared-group", locked = false)
        val second = clip("audio", "audio-track", "shared-group", locked = true)
        val unrelated = clip("other", "audio-track", "other-group", locked = false)
        val project = project(
            TimelineTrack("video-track", "Video", TrackType.VIDEO, clips = listOf(first)),
            TimelineTrack("audio-track", "Audio", TrackType.AUDIO, clips = listOf(unrelated, second))
        )

        val target = TimelineGroupLockController.targetFor(project, first.id)

        assertNotNull(target)
        assertEquals("shared-group", target?.linkedGroupId)
        assertEquals(true, target?.isLocked)
        assertEquals(listOf("audio", "video"), target?.memberClipIds)
        assertEquals("Unlock Linked Group", target?.actionLabel)
    }

    @Test
    fun ungroupedTargetControlsOnlyTheRequestedClip() {
        val selected = clip("selected", "track", groupId = null, locked = false)
        val neighbor = clip("neighbor", "track", groupId = null, locked = false)
        val project = project(TimelineTrack("track", "Video", TrackType.VIDEO, clips = listOf(selected, neighbor)))

        val target = TimelineGroupLockController.targetFor(project, selected.id)

        assertNotNull(target)
        assertEquals(null, target?.linkedGroupId)
        assertEquals(false, target?.isLocked)
        assertEquals(listOf("selected"), target?.memberClipIds)
        assertEquals("Lock Clip", target?.actionLabel)
    }

    private fun project(vararg tracks: TimelineTrack) = TimelineProject(
        id = "group-lock-project",
        settings = ProjectSettings(projectId = "group-lock-project", projectName = "Group lock"),
        tracks = tracks.toList()
    )

    private fun clip(id: String, trackId: String, groupId: String?, locked: Boolean): TimelineClip =
        TimelineClip(
            id = id,
            trackId = trackId,
            mediaId = "media-$id",
            name = "$id.mp4",
            sourceIn = 0L,
            sourceOut = 100L,
            timelineStart = 0L,
            metadata = groupId?.let { mapOf("linkedGroupId" to it) } ?: emptyMap(),
            isGroupLocked = locked
        )
}
