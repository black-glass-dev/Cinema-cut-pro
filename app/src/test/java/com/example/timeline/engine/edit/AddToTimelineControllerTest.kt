package com.example.timeline.engine.edit

import com.example.timeline.core.CoreV2EditKind
import com.example.timeline.core.ProjectFrameRatePreset
import com.example.timeline.core.ProjectSettings
import com.example.timeline.core.TimelineClip
import com.example.timeline.core.TimelineProject
import com.example.timeline.core.TimelineTrack
import com.example.timeline.core.TrackType
import com.example.timeline.media.FrameRate
import com.example.timeline.media.MediaAsset
import com.example.timeline.media.MediaAssetType
import com.example.timeline.media.MediaMetadata
import com.example.timeline.media.VideoStreamInfo
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class AddToTimelineControllerTest {
    private fun project(tracks: List<TimelineTrack> = emptyList()) = TimelineProject(
        id = "project",
        settings = ProjectSettings(
            projectId = "project",
            projectName = "Test project",
            frameRate = ProjectFrameRatePreset.FPS_24
        ),
        tracks = tracks
    )

    private fun videoAsset() = MediaAsset(
        projectId = "project",
        displayName = "test.mp4",
        originalUriString = "file:///test.mp4",
        persistedUriPermission = true,
        mimeType = "video/mp4",
        fileSizeBytes = 0L,
        importedAt = 0L,
        modifiedAt = 0L,
        assetType = MediaAssetType.VIDEO,
        metadata = MediaMetadata(
            durationUs = 18_300_000L,
            durationFramesInSourceRate = 439L,
            containerFormat = "mp4",
            bitrate = 0L,
            width = 1920,
            height = 1080,
            rotationDegrees = 0,
            pixelAspectRatio = 1f,
            videoStreams = emptyList<VideoStreamInfo>(),
            audioStreams = emptyList(),
            hasVideo = true,
            hasAudio = false,
            isVariableFrameRate = false,
            estimatedFrameRate = FrameRate.FPS_24,
            exactFrameRate = FrameRate.FPS_24,
            timecodeStart = null,
            creationTime = null
        )
    )

    private fun plan(
        project: TimelineProject = project(),
        asset: MediaAsset = videoAsset(),
        playheadFrame: Long = 0L
    ) = requireNotNull(
        AddToTimelineController.createClipInsertionPlan(project, asset, playheadFrame)
    )

    @Test
    fun motionCamRawAssetWithCustomMimeCreatesVideoInsertRequest() {
        val mcrawAsset = videoAsset().copy(
            displayName = "Video 1",
            mimeType = com.example.timeline.media.MotionCamRawFormat.MIME_TYPE,
            assetType = MediaAssetType.VIDEO,
            metadata = videoAsset().metadata.copy(hasVideo = true, hasAudio = true)
        )
        val insertion = plan(asset = mcrawAsset, playheadFrame = 20L)

        assertEquals(TrackType.VIDEO, insertion.baseProject.tracks.single().type)
        assertEquals(mcrawAsset.assetId, insertion.clip.mediaId)
        assertEquals(0L, insertion.clip.timelineStart)
        assertEquals(CoreV2EditKind.INSERT, insertion.command.kind)
        assertTrue(insertion.baseProject.tracks.single().clips.isEmpty())
    }

    @Test
    fun firstImportedVideoStartsAtFrameZeroEvenWhenPlayheadIsNotAtZero() {
        assertEquals(0L, plan(playheadFrame = 16L).clip.timelineStart)
    }

    @Test
    fun firstImportedVideoStartsAtFrameZeroWhenProjectAlreadyHasAudio() {
        val audioTrack = TimelineTrack(
            id = "audio-track",
            name = "Audio 1",
            type = TrackType.AUDIO,
            clips = listOf(
                TimelineClip(
                    id = "audio-bed",
                    trackId = "audio-track",
                    mediaId = "audio-media",
                    name = "music.wav",
                    sourceIn = 0L,
                    sourceOut = 200L,
                    timelineStart = 0L
                )
            )
        )
        assertEquals(0L, plan(project = project(listOf(audioTrack)), playheadFrame = 72L).clip.timelineStart)
    }

    @Test
    fun firstEnabledVideoUsesANewEmptyTrackWhenExistingVideoItemsAreDisabled() {
        val disabledVideoTrack = TimelineTrack(
            id = "disabled-track",
            name = "Video 1",
            type = TrackType.VIDEO,
            clips = listOf(
                TimelineClip(
                    id = "disabled-clip",
                    trackId = "disabled-track",
                    mediaId = "old-media",
                    name = "disabled.mp4",
                    sourceIn = 0L,
                    sourceOut = 80L,
                    timelineStart = 0L,
                    isEnabled = false
                )
            )
        )
        val insertion = plan(project = project(listOf(disabledVideoTrack)), playheadFrame = 72L)

        assertEquals(0L, insertion.clip.timelineStart)
        assertTrue(insertion.clip.trackId != disabledVideoTrack.id)
        assertEquals(2, insertion.baseProject.tracks.size)
        assertEquals("disabled-clip", insertion.baseProject.tracks.first().clips.single().id)
        assertTrue(insertion.baseProject.tracks.last().clips.isEmpty())
    }

    @Test
    fun laterImportedVideoUsesPlayheadWhenThereIsNoCollision() {
        val videoTrack = TimelineTrack(
            id = "video-track",
            name = "Video 1",
            type = TrackType.VIDEO,
            clips = listOf(
                TimelineClip(
                    id = "existing",
                    trackId = "video-track",
                    mediaId = "existing-media",
                    name = "existing.mp4",
                    sourceIn = 0L,
                    sourceOut = 100L,
                    timelineStart = 0L
                )
            )
        )
        val insertion = plan(project = project(listOf(videoTrack)), playheadFrame = 120L)
        assertEquals(120L, insertion.clip.timelineStart)
    }

    @Test
    fun unknownDurationRejectsInsteadOfInventingAPlaceholder() {
        val unknownDurationAsset = videoAsset().copy(
            metadata = videoAsset().metadata.copy(
                durationUs = 0L,
                durationFramesInSourceRate = 0L,
                exactFrameRate = null,
                estimatedFrameRate = null
            )
        )
        assertNull(AddToTimelineController.createClipInsertionPlan(project(), unknownDurationAsset, 0L))
    }

    @Test
    fun overflowingTimelinePlacementRejectsWithoutCreatingAClip() {
        val videoTrack = TimelineTrack(
            id = "video-track",
            name = "Video 1",
            type = TrackType.VIDEO,
            clips = listOf(
                TimelineClip(
                    id = "existing",
                    trackId = "video-track",
                    mediaId = "existing-media",
                    name = "existing.mp4",
                    sourceIn = 0L,
                    sourceOut = 100L,
                    timelineStart = 0L
                )
            )
        )
        assertNull(
            AddToTimelineController.createClipInsertionPlan(
                project = project(listOf(videoTrack)),
                mediaAsset = videoAsset(),
                playheadFrame = Long.MAX_VALUE - 10L
            )
        )
    }
}
