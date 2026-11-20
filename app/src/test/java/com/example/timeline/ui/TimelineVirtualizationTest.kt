package com.example.timeline.ui

import com.example.timeline.core.ProjectFrameRatePreset
import com.example.timeline.core.ProjectSettings
import com.example.timeline.core.TimelineClip
import com.example.timeline.core.TimelineProject
import com.example.timeline.core.TimelineTrack
import com.example.timeline.core.TrackType
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class TimelineVirtualizationTest {
    @Test
    fun largeTimelineRetainsOnlyViewportAndOverscanClips() {
        val tracks = (0 until 20).map { trackIndex ->
            val trackId = "track-$trackIndex"
            val clips = (0 until 25).map { clipIndex ->
                TimelineClip(
                    id = "clip-$trackIndex-$clipIndex",
                    trackId = trackId,
                    mediaId = "media-$trackIndex-$clipIndex",
                    name = "clip-$clipIndex",
                    sourceIn = 0L,
                    sourceOut = 10L,
                    timelineStart = clipIndex * 20L
                )
            }
            TimelineTrack(
                id = trackId,
                name = "Track $trackIndex",
                type = TrackType.VIDEO,
                clips = clips
            )
        }
        val project = TimelineProject(
            id = "large-project",
            settings = ProjectSettings(
                projectId = "large-project",
                projectName = "Large timeline",
                frameRate = ProjectFrameRatePreset.FPS_24
            ),
            tracks = tracks
        )
        assertEquals(500, project.tracks.sumOf { it.clips.size })

        val visible = TimelineVirtualization.visibleClips(
            project = project,
            scrollX = 0f,
            scrollY = 0f,
            viewportWidthPx = 120f,
            viewportHeightPx = 160f,
            pixelsPerFrame = 2f,
            trackHeightPx = 80f,
            trackPaddingPx = 12f,
            rulerHeightPx = 40f,
            overscanPx = 0f
        )

        assertTrue("viewport culling must not retain every clip", visible.size < 500)
        assertTrue(visible.isNotEmpty())
        assertTrue(visible.all { it.trackIndex in 0..1 })
        assertTrue(visible.all { it.clip.timelineStart <= 60L })

        val scrolled = TimelineVirtualization.visibleClips(
            project = project,
            scrollX = 80f,
            scrollY = 100f,
            viewportWidthPx = 120f,
            viewportHeightPx = 160f,
            pixelsPerFrame = 2f,
            trackHeightPx = 80f,
            trackPaddingPx = 12f,
            rulerHeightPx = 40f,
            overscanPx = 0f
        )
        assertTrue(scrolled.isNotEmpty())
        assertTrue(scrolled.all { it.clip.timelineStart >= 20L })
        assertTrue(scrolled.all { it.trackIndex in 0..2 })
    }
}
