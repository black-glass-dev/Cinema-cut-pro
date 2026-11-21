package com.example.timeline.engine.preview

import com.example.timeline.core.ProjectSettings
import com.example.timeline.core.TimelineClip
import com.example.timeline.core.TimelineProject
import com.example.timeline.core.TimelineTrack
import com.example.timeline.core.TrackType
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class PlaybackBoundaryPolicyTest {
    private fun clip(id: String, start: Long) = TimelineClip(
        id = id,
        trackId = "video",
        mediaId = "asset-$id",
        name = "$id.mp4",
        sourceIn = 0L,
        sourceOut = 20L,
        timelineStart = start
    )

    private fun project(vararg clips: TimelineClip) = TimelineProject(
        id = "project",
        settings = ProjectSettings(projectId = "project", projectName = "Boundary test"),
        tracks = listOf(TimelineTrack(id = "video", name = "Video", type = TrackType.VIDEO, clips = clips.toList()))
    )

    @Test
    fun gapBeforeNextClipKeepsPlaybackMovingThroughAnEmptyRange() {
        val first = clip("first", start = 0L)
        val next = clip("next", start = 35L)
        val decision = playbackBoundaryDecision(project(first, next), first)

        assertEquals(PlaybackBoundaryDecision.PlayThroughGap(gapStartFrame = 20L, nextClip = next), decision)
    }

    @Test
    fun finalClipStopsOnItsLastFrameInsteadOfWrappingOrReplaying() {
        val finalClip = clip("final", start = 40L)
        val decision = playbackBoundaryDecision(project(finalClip), finalClip)

        assertEquals(PlaybackBoundaryDecision.StopAtLastFrame(frame = 59L), decision)
    }

    @Test
    fun sequentialClipBoundaryAdvancesToTheNextTimelineClip() {
        val first = clip("first", start = 0L)
        val second = clip("second", start = first.timelineEnd)
        val decision = playbackBoundaryDecision(project(first, second), first)

        assertTrue(decision is PlaybackBoundaryDecision.AdvanceToNextClip)
        assertEquals(20L, (decision as PlaybackBoundaryDecision.AdvanceToNextClip).frame)
        assertEquals(second.id, decision.clip.id)
    }
}
