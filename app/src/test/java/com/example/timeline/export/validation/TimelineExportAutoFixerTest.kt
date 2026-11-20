package com.example.timeline.export.validation

import com.example.timeline.core.CoreV2EditKind
import com.example.timeline.core.ProjectFrameRatePreset
import com.example.timeline.core.ProjectSettings
import com.example.timeline.core.TimelineClip
import com.example.timeline.core.TimelineProject
import com.example.timeline.core.TimelineTrack
import com.example.timeline.core.TrackType
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

class TimelineExportAutoFixerTest {
    private fun project(vararg tracks: TimelineTrack) = TimelineProject(
        id = "project",
        settings = ProjectSettings(
            projectId = "project",
            projectName = "Test project",
            frameRate = ProjectFrameRatePreset.FPS_24
        ),
        tracks = tracks.toList()
    )

    @Test
    fun emitsOrderedNativeMovesAndPreservesSourceTrimFields() {
        val first = TimelineClip(
            id = "first",
            trackId = "video",
            mediaId = "media-1",
            name = "first.mp4",
            sourceIn = 12L,
            sourceOut = 52L,
            timelineStart = 80L
        )
        val second = TimelineClip(
            id = "second",
            trackId = "video",
            mediaId = "media-2",
            name = "second.mp4",
            sourceIn = 30L,
            sourceOut = 90L,
            timelineStart = 0L
        )
        val original = project(
            TimelineTrack("video", "Video 1", TrackType.VIDEO, clips = listOf(first, second))
        )

        val commands = TimelineExportAutoFixer.compactVisibleVideoTrackCommands(original)!!
        assertEquals(listOf("first"), commands.map { it.clipId })
        assertEquals(listOf(CoreV2EditKind.MOVE), commands.map { it.kind })
        assertEquals(listOf("video"), commands.map { it.targetTrackId })
        assertEquals(listOf(60L), commands.map { it.targetFrame })
        assertEquals(12L, first.sourceIn)
        assertEquals(52L, first.sourceOut)
        assertEquals(30L, second.sourceIn)
        assertEquals(90L, second.sourceOut)
        // Command construction is read-only; only the native commit may alter the project.
        assertEquals(80L, original.tracks.single().clips.first { it.id == "first" }.timelineStart)
    }

    @Test
    fun refusesToRewriteLockedClipsOrMultipleVisibleVideoTracks() {
        val lockedClip = TimelineClip(
            id = "locked",
            trackId = "video-1",
            mediaId = "media",
            name = "locked.mp4",
            sourceIn = 0L,
            sourceOut = 30L,
            timelineStart = 40L,
            isLocked = true
        )
        val video1 = TimelineTrack(
            "video-1",
            "Video 1",
            TrackType.VIDEO,
            clips = listOf(lockedClip)
        )
        assertNull(TimelineExportAutoFixer.compactVisibleVideoTrackCommands(project(video1)))

        val video2 = TimelineTrack(
            "video-2",
            "Video 2",
            TrackType.VIDEO,
            clips = listOf(
                TimelineClip(
                    id = "other",
                    trackId = "video-2",
                    mediaId = "media-2",
                    name = "other.mp4",
                    sourceIn = 0L,
                    sourceOut = 30L,
                    timelineStart = 0L
                )
            )
        )
        val visibleVideo1 = video1.copy(clips = listOf(lockedClip.copy(isLocked = false)))
        assertNull(
            TimelineExportAutoFixer.compactVisibleVideoTrackCommands(project(visibleVideo1, video2))
        )
    }
}
