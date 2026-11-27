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
    fun moveIntoAvailableGapCommitsExactRequestedPosition() {
        val project = project(clip("a", 0), clip("b", 10), clip("c", 40))

        val command = TimelineClipMove.commands(project, "b", targetStartFrame = 22).single()

        assertEquals(CoreV2EditKind.MOVE, command.kind)
        assertEquals("b", command.clipId)
        assertEquals("v1", command.targetTrackId)
        assertEquals(22L, command.targetFrame)
    }

    @Test
    fun moveIntoOccupiedSpaceChoosesNearestLegalGapInDragDirection() {
        val crowdedProject = project(clip("a", 0), clip("b", 10), clip("c", 20))

        val movedRight = TimelineClipMove.commands(crowdedProject, "a", targetStartFrame = 24).single()
        val leftGapProject = project(clip("a", 0), clip("b", 20), clip("c", 30))
        val movedLeft = TimelineClipMove.commands(leftGapProject, "b", targetStartFrame = 8).single()

        assertEquals(30L, movedRight.targetFrame)
        assertEquals(10L, movedLeft.targetFrame)
    }

    @Test
    fun normalDragPreviewUsesSameResolvedPositionAsDropCommit() {
        val crowdedProject = project(clip("a", 0), clip("b", 10), clip("c", 20))
        val movingClip = crowdedProject.tracks.single().clips.first { it.id == "a" }

        val previewCommands = TimelineGestureController.previewCommands(
            action = TimelineDragAction.MoveClip(movingClip, startOffsetFrames = 0),
            targetFrame = 24,
            project = crowdedProject
        )
        val committedCommand = TimelineClipMove.commands(crowdedProject, "a", 24).single()

        assertEquals(30L, previewCommands?.single()?.targetFrame)
        assertEquals(committedCommand.targetFrame, previewCommands?.single()?.targetFrame)
    }

    @Test
    fun gapResolverClampsToNearestEdgeWithoutJumpingToOldSlot() {
        val project = project(clip("a", 0), clip("b", 10), clip("c", 20), clip("d", 40))

        assertEquals(20L, TimelineClipMove.resolveStartFrame(project, "c", 18))
        assertEquals(30L, TimelineClipMove.resolveStartFrame(project, "c", 32))
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
