package com.example.timeline.ui

import androidx.compose.ui.geometry.Offset
import com.example.timeline.core.ProjectSettings
import com.example.timeline.core.TimelineClip
import com.example.timeline.core.TimelineProject
import com.example.timeline.core.TimelineTrack
import com.example.timeline.core.TrackType
import com.example.timeline.engine.native.v2.NativeTimelineCoreV2
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class AdvancedTimelineGestureControllerTest {
    private fun projectWithClip(startFrame: Long): Pair<TimelineProject, TimelineClip> {
        val clip = TimelineClip(
            id = "clip",
            trackId = "video-track",
            mediaId = "media",
            name = "clip.mp4",
            sourceIn = 0L,
            sourceOut = 100L,
            timelineStart = startFrame
        )
        val project = TimelineProject(
            id = "project",
            settings = ProjectSettings(projectId = "project", projectName = "Test project"),
            tracks = listOf(TimelineTrack("video-track", "Video 1", TrackType.VIDEO, clips = listOf(clip)))
        )
        return project to clip
    }

    @Test
    fun clipEditGestureBecomesPanWhenNativeCoreIsUnavailable() {
        NativeTimelineCoreV2.markUnavailable()
        val (project, clip) = projectWithClip(startFrame = 0L)

        val action = AdvancedTimelineGestureController.determineDragAction(
            startOffset = Offset(x = 50f, y = 40f + 84f),
            scrollX = 0f,
            scrollY = 0f,
            rulerHeightPx = 40f,
            project = project,
            pixelsPerFrame = 1f,
            selectedClipIds = setOf(clip.id)
        )

        assertTrue(
            "clip edit gestures must not fall back to Kotlin when native is unavailable",
            action is TimelineDragAction.Pan
        )
    }

    @Test
    fun snappingRejectsWithoutNativeCoreInsteadOfUsingKotlinMirror() {
        NativeTimelineCoreV2.markUnavailable()
        val (project, _) = projectWithClip(startFrame = 100L)

        val result = AdvancedTimelineGestureController.snapFrame(
            inputFrame = 101L,
            project = project,
            playheadFrame = 0L,
            pixelsPerFrame = 1f
        )

        assertNull(result)
    }
}
