package com.example.ui.export

import com.example.timeline.core.ProjectFrameRatePreset
import com.example.timeline.core.ProjectSettings
import com.example.timeline.core.TimelineClip
import com.example.timeline.core.TimelineProject
import com.example.timeline.core.TimelineTrack
import com.example.timeline.core.TrackType
import com.example.timeline.export.model.ExportAudioSettings
import com.example.timeline.export.model.ExportCodec
import com.example.timeline.export.model.ExportContainer
import com.example.timeline.export.model.ExportFrameRate
import com.example.timeline.export.model.ExportQualityPreset
import com.example.timeline.export.model.ExportSettings
import com.example.timeline.export.preset.SocialExportPresets
import com.example.timeline.media.AudioStreamInfo
import com.example.timeline.media.FrameRate
import com.example.timeline.media.MediaAsset
import com.example.timeline.media.MediaAssetType
import com.example.timeline.media.MediaMetadata
import com.example.timeline.media.MotionCamRawFormat
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class ExportSettingsDefaultsTest {
    private fun asset(
        id: String,
        frameRate: FrameRate,
        audioSampleRate: Int? = null,
        audioChannels: Int = 2
    ) = MediaAsset(
        assetId = id,
        projectId = "project",
        displayName = "$id.mp4",
        originalUriString = "file:///tmp/$id.mp4",
        persistedUriPermission = true,
        mimeType = "video/mp4",
        fileSizeBytes = 1_000_000L,
        importedAt = 1L,
        modifiedAt = 1L,
        assetType = MediaAssetType.VIDEO,
        metadata = MediaMetadata(
            durationUs = 10_000_000L,
            durationFramesInSourceRate = 100L,
            containerFormat = "mp4",
            bitrate = 8_000_000L,
            width = 1920,
            height = 1080,
            rotationDegrees = 0,
            pixelAspectRatio = 1f,
            videoStreams = emptyList(),
            audioStreams = audioSampleRate?.let { sampleRate ->
                listOf(
                    AudioStreamInfo(
                        codecMime = "audio/aac",
                        sampleRate = sampleRate,
                        channelCount = audioChannels,
                        bitrate = 128_000,
                        durationUs = 10_000_000L,
                        language = null,
                        hardwareDecodeSupported = true
                    )
                )
            }.orEmpty(),
            hasVideo = true,
            hasAudio = audioSampleRate != null,
            isVariableFrameRate = false,
            estimatedFrameRate = frameRate,
            exactFrameRate = frameRate,
            timecodeStart = null,
            creationTime = null,
            variableFrameRateDetectionComplete = true
        )
    )

    private fun project(vararg clips: TimelineClip, assets: List<MediaAsset>) = TimelineProject(
        id = "project",
        settings = ProjectSettings(
            projectId = "project",
            projectName = "Test project",
            frameRate = ProjectFrameRatePreset.FPS_30
        ),
        tracks = listOf(
            TimelineTrack(
                id = "video",
                name = "Video 1",
                type = TrackType.VIDEO,
                clips = clips.toList()
            )
        ),
        mediaAssets = assets
    )

    private fun clip(id: String, mediaId: String, start: Long = 0L) = TimelineClip(
        id = id,
        trackId = "video",
        mediaId = mediaId,
        name = "$id.mp4",
        sourceIn = 0L,
        sourceOut = 100L,
        timelineStart = start
    )

    private fun settings() = SocialExportPresets.youtube1080p("project", "output.mp4")

    @Test
    fun singleClipDefaultsToItsCfrSourceRateInsteadOfTheProjectRate() {
        val source = asset("source", FrameRate(2188, 100), audioSampleRate = 44_100, audioChannels = 1)
        val project = project(clip("clip", source.assetId), assets = listOf(source))

        val result = ExportSettingsDefaults.adaptToSimpleSource(settings(), project)

        assertEquals(2188, result.frameRate.numerator)
        assertEquals(100, result.frameRate.denominator)
        assertEquals(44_100, result.audioSampleRate)
        assertEquals(1, result.audioChannels)
        assertTrue(result.includeAudio)
    }

    @Test
    fun multipleSameRateClipsUseTheirSharedSourceRate() {
        val rate = FrameRate(30_000, 1_001)
        val first = asset("first", rate, audioSampleRate = 48_000)
        val second = asset("second", rate, audioSampleRate = 48_000)
        val project = project(
            clip("clip-1", first.assetId),
            clip("clip-2", second.assetId, start = 100L),
            assets = listOf(first, second)
        )

        val result = ExportSettingsDefaults.adaptToSimpleSource(settings(), project)

        assertEquals(ExportFrameRate.FPS_29_97, result.frameRate)
        assertEquals(48_000, result.audioSampleRate)
        assertEquals(2, result.audioChannels)
    }

    @Test
    fun mixedSourceRatesKeepProjectRateSoValidationCanExplainUnsupportedConform() {
        val first = asset("first", FrameRate(24, 1))
        val second = asset("second", FrameRate(30, 1))
        val project = project(
            clip("clip-1", first.assetId),
            clip("clip-2", second.assetId, start = 100L),
            assets = listOf(first, second)
        )

        val result = ExportSettingsDefaults.adaptToSimpleSource(settings(), project)

        assertEquals(ExportFrameRate.FPS_30, result.frameRate)
    }

    @Test
    fun presetDefaultsOnlyLeaveOptionsImplementedByTheRealBackend() {
        val source = asset("source", FrameRate(24, 1))
        val project = project(clip("clip", source.assetId), assets = listOf(source))
        val requested = settings().copy(
            codec = ExportCodec.H265,
            container = ExportContainer.MOV,
            bitrateMode = ExportSettings.BitrateMode.CRF,
            useHardwareEncoder = false,
            useOriginalMedia = false,
            allowProxyExport = true,
            renderAlpha = true,
            exportHDR = true,
            audioCodec = ExportAudioSettings.AudioCodec.OPUS,
            normalizeAudio = true,
            includeWatermark = true
        )

        val result = ExportSettingsDefaults.adaptToSimpleSource(requested, project)

        assertEquals(ExportCodec.H264, result.codec)
        assertEquals(ExportContainer.MP4, result.container)
        assertEquals(ExportSettings.BitrateMode.VBR, result.bitrateMode)
        assertTrue(result.useHardwareEncoder)
        assertTrue(result.useOriginalMedia)
        assertFalse(result.allowProxyExport)
        assertFalse(result.renderAlpha)
        assertFalse(result.exportHDR)
        assertEquals(ExportAudioSettings.AudioCodec.AAC, result.audioCodec)
        assertFalse(result.normalizeAudio)
        assertFalse(result.includeWatermark)
    }

    @Test
    fun projectsWithoutCompatibleEmbeddedAudioDefaultToVideoOnly() {
        val source = asset("source", FrameRate(30, 1))
        val project = project(clip("clip", source.assetId), assets = listOf(source))

        val result = ExportSettingsDefaults.adaptToSimpleSource(settings(), project)

        assertFalse(result.includeAudio)
    }

    @Test
    fun mcrawSourcesDefaultToVideoOnlyUntilTheirPcmAudioCanBeEncoded() {
        val rawSource = asset("raw", FrameRate(30, 1), audioSampleRate = 48_000).copy(
            displayName = "raw.mcraw",
            originalUriString = "file:///tmp/raw.mcraw",
            mimeType = MotionCamRawFormat.MIME_TYPE
        )
        val project = project(clip("clip", rawSource.assetId), assets = listOf(rawSource))

        val result = ExportSettingsDefaults.adaptToSimpleSource(
            settings().copy(includeAudio = true),
            project
        )

        assertFalse(result.includeAudio)
    }

    @Test
    fun defaultLandscapePresetCanMatchPortraitProjectWithoutRotatingTheProject() {
        val source = asset("source", FrameRate(30, 1))
        val landscapeProject = project(clip("clip", source.assetId), assets = listOf(source))
        val portraitProject = landscapeProject.copy(
            settings = landscapeProject.settings.copy(resolutionWidth = 1080, resolutionHeight = 1920)
        )

        val result = ExportSettingsDefaults.adaptToSimpleSource(
            settings(),
            portraitProject,
            matchProjectOrientation = true
        )

        assertEquals(1080, result.resolutionWidth)
        assertEquals(1920, result.resolutionHeight)
    }

    @Test
    fun resolutionOptionsPreserveOrientationAndStayWithinAdapterLimits() {
        val landscape = ExportSettingsDefaults.resolutionOptions(1920, 1080)
        val portrait = ExportSettingsDefaults.resolutionOptions(1080, 1920)

        assertEquals("720p", landscape.first().label)
        assertEquals(3840, landscape.last().width)
        assertEquals(2160, landscape.last().height)
        assertEquals(2160, portrait.last().width)
        assertEquals(3840, portrait.last().height)
    }

    @Test
    fun qualityPresetsDriveTheActualEncoderBitrate() {
        val draft = ExportSettingsDefaults.bitrateForQualityPreset(
            ExportQualityPreset.DRAFT,
            1920,
            1080,
            ExportFrameRate.FPS_30
        )!!
        val master = ExportSettingsDefaults.bitrateForQualityPreset(
            ExportQualityPreset.MASTER,
            1920,
            1080,
            ExportFrameRate.FPS_30
        )!!

        assertTrue(draft > 0)
        assertTrue(master > draft)
        assertEquals(null, ExportSettingsDefaults.bitrateForQualityPreset(
            ExportQualityPreset.CUSTOM,
            1920,
            1080,
            ExportFrameRate.FPS_30
        ))
    }
}
