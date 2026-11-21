package com.example.timeline.ui

import androidx.compose.ui.geometry.Offset
import com.example.timeline.core.ProjectFrameRatePreset
import com.example.timeline.core.ProjectSettings
import com.example.timeline.core.TimelineClip
import com.example.timeline.core.TimelineProject
import com.example.timeline.core.TimelineTrack
import com.example.timeline.core.TrackType
import com.example.timeline.engine.native.v2.NativeTimelineCoreV2
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class TimelineGestureControllerTest {
    @Test
    fun trimEdgeHitTargetsAreEasyToReachAndResolveClosestBoundary() {
        val clip = TimelineClip(
            id = "clip",
            trackId = "video-track",
            mediaId = "media",
            name = "clip.mp4",
            sourceIn = 0L,
            sourceOut = 100L,
            timelineStart = 10L
        )

        val leftAction = TimelineGestureController.trimActionAtEdge(
            clip = clip,
            tapX = 16f,
            pixelsPerFrame = 2f,
            hitRadiusPx = 12f
        )
        val rightAction = TimelineGestureController.trimActionAtEdge(
            clip = clip,
            tapX = 214f,
            pixelsPerFrame = 2f,
            hitRadiusPx = 12f
        )

        assertTrue(leftAction is TimelineDragAction.TrimStart)
        assertTrue(rightAction is TimelineDragAction.TrimEnd)
        assertNull(TimelineGestureController.trimActionAtEdge(clip, 100f, 2f, 12f))
    }

    @Test
    fun trimDragTargetsClampToLegalTimelineAndKnownSourceBounds() {
        val clip = TimelineClip(
            id = "clip",
            trackId = "video-track",
            mediaId = "media",
            name = "clip.mp4",
            sourceIn = 5L,
            sourceOut = 100L,
            timelineStart = 10L
        )
        val project = TimelineProject(
            id = "project",
            settings = ProjectSettings(projectId = "project", projectName = "Test project"),
            tracks = listOf(TimelineTrack("video-track", "Video 1", TrackType.VIDEO, clips = listOf(clip)))
        )

        assertEquals(5L, TimelineGestureController.clampTrimTarget(TimelineDragAction.TrimStart(clip, 10L), Long.MIN_VALUE, project))
        assertEquals(104L, TimelineGestureController.clampTrimTarget(TimelineDragAction.TrimStart(clip, 10L), Long.MAX_VALUE, project))
        assertEquals(11L, TimelineGestureController.clampTrimTarget(TimelineDragAction.TrimEnd(clip, 105L), Long.MIN_VALUE, project))
        assertEquals(105L, TimelineGestureController.clampTrimTarget(TimelineDragAction.TrimEnd(clip, 105L), Long.MAX_VALUE, project))
    }

    @Test
    fun testINV15_DragOnClipIsDisabledWhenNativeCoreIsUnavailable() {
        NativeTimelineCoreV2.markUnavailable()
        val clip = TimelineClip(
            id = "clip",
            trackId = "video-track",
            mediaId = "media",
            name = "clip.mp4",
            sourceIn = 0L,
            sourceOut = 100L,
            timelineStart = 0L
        )
        val project = TimelineProject(
            id = "project",
            settings = ProjectSettings(projectId = "project", projectName = "Test project"),
            tracks = listOf(TimelineTrack("video-track", "Video 1", TrackType.VIDEO, clips = listOf(clip)))
        )

        val action = TimelineGestureController.determineDragAction(
            startOffset = Offset(x = 50f, y = 40f + 84f),
            scrollX = 0f,
            scrollY = 0f,
            rulerHeightPx = 40f,
            project = project,
            pixelsPerFrame = 1f,
            selectedClipId = clip.id
        )

        assertTrue("clip edit gestures must not fall back to Kotlin when native is unavailable", action is TimelineDragAction.Pan)
    }

    @Test
    fun draggingVisiblePlayheadLineOverAClipStartsScrubbingNotClipEditing() {
        val clip = TimelineClip(
            id = "clip",
            trackId = "video-track",
            mediaId = "media",
            name = "clip.mp4",
            sourceIn = 0L,
            sourceOut = 100L,
            timelineStart = 0L
        )
        val project = TimelineProject(
            id = "project",
            settings = ProjectSettings(projectId = "project", projectName = "Test project"),
            tracks = listOf(TimelineTrack("video-track", "Video 1", TrackType.VIDEO, clips = listOf(clip)))
        )

        val action = TimelineGestureController.determineDragAction(
            startOffset = Offset(x = 44f, y = 60f),
            scrollX = 0f,
            scrollY = 0f,
            rulerHeightPx = 40f,
            project = project,
            pixelsPerFrame = 2f,
            selectedClipId = clip.id,
            playheadFrame = 20L,
            playheadHitRadiusPx = 8f
        )

        assertTrue("the visible playhead hit target must win over the clip underneath", action is TimelineDragAction.ScrubPlayhead)
    }

    @Test
    fun testINV15_SnapDoesNotUseKotlinFallbackWhenNativeCoreIsUnavailable() {
        NativeTimelineCoreV2.markUnavailable()
        val clip = TimelineClip(
            id = "clip",
            trackId = "video-track",
            mediaId = "media",
            name = "clip.mp4",
            sourceIn = 0L,
            sourceOut = 100L,
            timelineStart = 100L
        )
        val project = TimelineProject(
            id = "project",
            settings = ProjectSettings(projectId = "project", projectName = "Test project"),
            tracks = listOf(TimelineTrack("video-track", "Video 1", TrackType.VIDEO, clips = listOf(clip)))
        )

        val result = TimelineGestureController.snapFrame(
            inputFrame = 101L,
            project = project,
            playheadFrame = 0L,
            pixelsPerFrame = 1f
        )

        assertNull("native-unavailable snap must cancel instead of returning an unsnapped fallback", result)
    }

    @Test
    fun dragStartingOutsideTrackDoesNotMoveTheClipVertically() {
        NativeTimelineCoreV2.markUnavailable()
        val clip = TimelineClip(
            id = "clip",
            trackId = "video-track",
            mediaId = "media",
            name = "clip.mp4",
            sourceIn = 0L,
            sourceOut = 100L,
            timelineStart = 0L
        )
        val project = TimelineProject(
            id = "project",
            settings = ProjectSettings(
                projectId = "project",
                projectName = "Test project",
                frameRate = ProjectFrameRatePreset.FPS_24
            ),
            tracks = listOf(
                TimelineTrack("video-track", "Video 1", TrackType.VIDEO, clips = listOf(clip))
            )
        )

        val action = TimelineGestureController.determineDragAction(
            startOffset = Offset(x = 50f, y = 40f + 84f),
            scrollX = 0f,
            scrollY = 0f,
            rulerHeightPx = 40f,
            project = project,
            pixelsPerFrame = 1f,
            selectedClipId = clip.id
        )

        assertTrue(action is TimelineDragAction.Pan)
    }

    @Test
    fun linkedTrimPreviewBuildsTheSameNativeBatchForCommit() {
        val primary = TimelineClip(
            id = "clip-a",
            trackId = "video-track",
            mediaId = "media",
            name = "video",
            sourceIn = 0L,
            sourceOut = 100L,
            timelineStart = 100L
        )
        val linked = TimelineClip(
            id = "clip-b",
            trackId = "audio-track",
            mediaId = "media",
            name = "audio",
            sourceIn = 0L,
            sourceOut = 100L,
            timelineStart = 200L
        )
        val project = TimelineProject(
            id = "project",
            settings = ProjectSettings(projectId = "project", projectName = "Test project"),
            tracks = listOf(
                TimelineTrack("video-track", "Video", TrackType.VIDEO, clips = listOf(primary)),
                TimelineTrack("audio-track", "Audio", TrackType.AUDIO, clips = listOf(linked))
            )
        )
        val commands = TimelineGestureController.previewCommands(
            action = TimelineDragAction.TrimStart(primary, primary.timelineStart),
            targetFrame = 105L,
            project = project,
            linkedClipIds = setOf(primary.id, linked.id)
        )

        assertEquals(2, commands?.size)
        assertEquals(listOf("clip-a", "clip-b"), commands?.map { it.clipId })
        assertEquals(listOf(105L, 205L), commands?.map { it.targetFrame })
    }
}
