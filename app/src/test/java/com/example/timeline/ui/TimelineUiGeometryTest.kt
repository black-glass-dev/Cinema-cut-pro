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
    }
}
