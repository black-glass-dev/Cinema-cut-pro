package com.example.timeline.ui

import com.example.timeline.core.CoreV2EditKind
import com.example.timeline.core.ProjectSettings
import com.example.timeline.core.TimelineClip
import com.example.timeline.core.TimelineProject
import com.example.timeline.core.TimelineTrack
import com.example.timeline.core.TrackType
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class TimelineClipMoveTest {
    private fun clip(id: String, start: Long, duration: Long = 10L) = TimelineClip(
        id = id, trackId = "v1", mediaId = "media-$id", name = id,
        sourceIn = 0L, sourceOut = duration, timelineStart = start
    )

    private fun project(vararg clips: TimelineClip, locked: Boolean = false) = TimelineProject(
        id = "project",
        settings = ProjectSettings(projectId = "project", projectName = "Test"),
        tracks = listOf(TimelineTrack("v1", "Video", TrackType.VIDEO, isLocked = locked, clips = clips.toList()))
    )

    @Test
    fun smallHorizontalDragChangesClipStartInBothDirections() {
        assertEquals(12L, TimelineClipMove.targetStartFrame(10L, 2f, 1f, 10L))
        assertEquals(8L, TimelineClipMove.targetStartFrame(10L, -2f, 1f, 10L))
        assertEquals(0L, TimelineClipMove.targetStartFrame(1L, -8f, 1f, 10L))
    }

    @Test
    fun smallDragWithinSameOrderCommitsExactRequestedPosition() {
        val project = project(clip("a", 0), clip("b", 10), clip("c", 20))

        val command = TimelineClipMove.commands(project, "b", targetStartFrame = 12).single()

        assertEquals(CoreV2EditKind.MOVE, command.kind)
        assertEquals("b", command.clipId)
        assertEquals("v1", command.targetTrackId)
        assertEquals(12L, command.targetFrame)
    }

    @Test
    fun draggingAcrossOtherClipsKeepsRequestedStartInsteadOfReturningToOldSlot() {
        val project = project(clip("a", 0), clip("b", 10), clip("c", 20))

        val command = TimelineClipMove.commands(project, "a", targetStartFrame = 24).single()

        assertEquals("a", command.clipId)
        assertEquals(24L, command.targetFrame)
    }

    @Test
    fun unchangedNegativeOrLockedMoveProducesNoCommand() {
        val unlocked = project(clip("a", 0), clip("b", 10))
        val trackLocked = project(clip("a", 0), clip("b", 10), locked = true)
        val clipLocked = project(clip("a", 0), clip("b", 10).copy(isLocked = true))
        val groupLocked = project(clip("a", 0).copy(isGroupLocked = true), clip("b", 10))

        assertTrue(TimelineClipMove.commands(unlocked, "a", 0).isEmpty())
        assertTrue(TimelineClipMove.commands(unlocked, "a", -1).isEmpty())
        assertTrue(TimelineClipMove.commands(trackLocked, "a", 20).isEmpty())
        assertTrue(TimelineClipMove.commands(clipLocked, "b", 20).isEmpty())
        assertTrue(TimelineClipMove.commands(groupLocked, "a", 20).isEmpty())
    }
}
