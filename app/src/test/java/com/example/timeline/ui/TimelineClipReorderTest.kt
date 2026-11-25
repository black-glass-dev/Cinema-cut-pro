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

class TimelineClipReorderTest {
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
    fun dropBetweenClipsReordersAndRetainsExistingGapPattern() {
        val commands = TimelineClipReorder.commands(
            project(clip("a", 0), clip("b", 15), clip("c", 25)),
            clipId = "c",
            dropFrame = 8
        )

        assertEquals(listOf("c", "b"), commands.map { it.clipId })
        assertEquals(listOf(15L, 25L), commands.map { it.targetFrame })
        assertTrue(commands.all { it.kind == CoreV2EditKind.MOVE })
    }

    @Test
    fun dropOnEitherSideUsesClipMidpointAndNoopProducesNoCommands() {
        val project = project(clip("a", 0), clip("b", 10), clip("c", 20))

        assertEquals(listOf("c", "a"), TimelineClipReorder.commands(project, "c", 4).map { it.clipId })
        assertTrue(TimelineClipReorder.commands(project, "b", 15).isEmpty())
    }

    @Test
    fun lockedOrOverlappingTrackClipsCannotBeReordered() {
        val trackLocked = project(clip("a", 0), clip("b", 10), locked = true)
        val clipLocked = project(clip("a", 0), clip("b", 10).copy(isLocked = true))
        val overlapping = project(clip("a", 0, 15), clip("b", 10))

        assertTrue(TimelineClipReorder.commands(trackLocked, "a", 20).isEmpty())
        assertTrue(TimelineClipReorder.commands(clipLocked, "a", 20).isEmpty())
        assertTrue(TimelineClipReorder.commands(overlapping, "a", 20).isEmpty())
    }
}
