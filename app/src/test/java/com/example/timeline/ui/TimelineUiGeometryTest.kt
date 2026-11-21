package com.example.timeline.ui

import com.example.timeline.core.ProjectSettings
import com.example.timeline.core.TimelineClip
import com.example.timeline.core.TimelineProject
import com.example.timeline.core.TimelineTrack
import com.example.timeline.core.TrackType
import org.junit.Assert.assertEquals
import org.junit.Test

class TimelineUiGeometryTest {
    @Test
    fun convertsFramesAndPixelsUsingPresentationScale() {
        assertEquals(250f, TimelineUiGeometry.frameToX(100L, 2.5f), 0f)
        assertEquals(100L, TimelineUiGeometry.xToFrame(250f, 2.5f))
        assertEquals(3L, TimelineUiGeometry.xToFrame(5f, 2f))
        assertEquals(0L, TimelineUiGeometry.xToFrame(Float.NaN, 2f))
        assertEquals(0f, TimelineUiGeometry.frameToX(100L, Float.NaN), 0f)
    }

    @Test
    fun horizontalScrollCannotMoveTimelinePastItsContentBounds() {
        val clip = TimelineClip(
            id = "clip",
            trackId = "video",
            mediaId = "media",
            name = "clip.mp4",
            sourceIn = 0L,
            sourceOut = 100L,
            timelineStart = 20L
        )
        val project = TimelineProject(
            id = "project",
            settings = ProjectSettings(projectId = "project", projectName = "Test"),
            tracks = listOf(TimelineTrack("video", "Video", TrackType.VIDEO, clips = listOf(clip)))
        )

        assertEquals(140f, TimelineUiGeometry.maxScrollX(project, 2f, 100f), 0f)
        assertEquals(0f, TimelineUiGeometry.maxScrollX(project, 2f, 300f), 0f)
        assertEquals(0f, TimelineUiGeometry.maxScrollX(project, Float.NaN, 100f), 0f)
    }

    @Test
    fun fixedPlayheadScrollFollowsTheCursorAndClampsAtSequenceEnds() {
        assertEquals(0f, TimelineUiGeometry.followPlayheadScrollX(20L, 2f, 50f, 140f), 0f)
        assertEquals(50f, TimelineUiGeometry.followPlayheadScrollX(50L, 2f, 50f, 140f), 0f)
        assertEquals(140f, TimelineUiGeometry.followPlayheadScrollX(110L, 2f, 50f, 140f), 0f)
    }

    @Test
    fun tapsInEmptyTimelineGapsPlacePlayheadAtTheTappedFrame() {
        val first = TimelineClip(
            id = "first",
            trackId = "video",
            mediaId = "media-a",
            name = "first.mp4",
            sourceIn = 0L,
            sourceOut = 50L,
            timelineStart = 0L
        )
        val second = TimelineClip(
            id = "second",
            trackId = "video",
            mediaId = "media-b",
            name = "second.mp4",
            sourceIn = 0L,
            sourceOut = 50L,
            timelineStart = 200L
        )
        val project = TimelineProject(
            id = "project",
            settings = ProjectSettings(projectId = "project", projectName = "Test"),
            tracks = listOf(TimelineTrack("video", "Video", TrackType.VIDEO, clips = listOf(first, second)))
        )

        assertEquals(125L, TimelineUiGeometry.tapToPlayheadFrame(125f, 0f, 1f, project))
    }

    @Test
    fun clampsScrubbingToTheSequenceEndAndZero() {
        val clip = TimelineClip(
            id = "clip",
            trackId = "video",
            mediaId = "media",
            name = "clip.mp4",
            sourceIn = 10L,
            sourceOut = 110L,
            timelineStart = 20L
        )
        val project = TimelineProject(
            id = "project",
            settings = ProjectSettings(projectId = "project", projectName = "Test"),
            tracks = listOf(TimelineTrack("video", "Video", TrackType.VIDEO, clips = listOf(clip)))
        )

        assertEquals(0L, TimelineUiGeometry.clampPlayheadFrame(project, -1L))
        assertEquals(75L, TimelineUiGeometry.clampPlayheadFrame(project, 75L))
        assertEquals(120L, TimelineUiGeometry.clampPlayheadFrame(project, Long.MAX_VALUE))
        assertEquals(50L, TimelineUiGeometry.tapToPlayheadFrame(80f, 20f, 2f, project))
        assertEquals(120L, TimelineUiGeometry.tapToPlayheadFrame(500f, 0f, 2f, project))
    }
}
