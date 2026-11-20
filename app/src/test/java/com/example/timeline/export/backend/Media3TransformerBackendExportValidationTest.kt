package com.example.timeline.export.backend

import android.content.Context
import androidx.media3.common.util.UnstableApi
import androidx.media3.transformer.Composition
import androidx.test.core.app.ApplicationProvider
import com.example.timeline.core.ClipType
import com.example.timeline.core.ProjectFrameRatePreset
import com.example.timeline.core.ProjectSettings
import com.example.timeline.core.TimelineClip
import com.example.timeline.core.TimelineProject
import com.example.timeline.core.TimelineTrack
import com.example.timeline.core.TrackType
import com.example.model.colorgrade.ColorGradeKeyframe
import com.example.model.colorgrade.ColorGradeLayer
import com.example.model.colorgrade.ColorGradeLayerStack
import com.example.model.colorgrade.ColorGradeParams
import com.example.model.colorgrade.ColorGradeStack
import com.example.model.colorgrade.ColorGradeTarget
import com.example.model.colorgrade.ColorLayerType
import com.example.model.colorgrade.ColorWheelParams
import com.example.timeline.export.model.ExportCodec
import com.example.timeline.export.model.ExportContainer
import com.example.timeline.export.model.ExportSettings
import com.example.timeline.export.preset.SocialExportPresets
import com.example.timeline.media.AudioStreamInfo
import com.example.timeline.media.FrameRate
import com.example.timeline.media.MediaAsset
import com.example.timeline.media.MediaAssetType
import com.example.timeline.media.MediaMetadata
import com.example.timeline.media.MotionCamRawFormat
import com.example.timeline.media.VideoStreamInfo
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [33])
@OptIn(UnstableApi::class)
class Media3TransformerBackendExportValidationTest {
    private val applicationContext: Context = ApplicationProvider.getApplicationContext()

    private class CodecProbe(
        private val anyVideoEncoder: Boolean = true,
        private val hardwareVideoEncoder: Boolean = true,
        private val audioEncoder: Boolean = true,
        private val mcrawEncoder: Boolean = true
    ) : Media3CodecSupportProbe {
        override fun hasVideoEncoder(settings: ExportSettings, requireHardware: Boolean): Boolean =
            if (requireHardware) hardwareVideoEncoder else anyVideoEncoder

        override fun hasAudioEncoder(sampleRate: Int, channelCount: Int, bitrate: Int): Boolean = audioEncoder

        override fun hasMcrawIntermediateEncoder(width: Int, height: Int, frameRate: Float, bitrate: Int): Boolean = mcrawEncoder
    }

    private fun asset(
        id: String,
        audioSampleRate: Int? = null,
        audioChannels: Int = 2,
        videoStreams: List<VideoStreamInfo> = emptyList()
    ) = MediaAsset(
        assetId = id,
        projectId = "project",
        displayName = "$id.mp4",
        originalUriString = "file:///tmp/$id.mp4",
        persistedUriPermission = true,
        mimeType = "video/mp4",
        fileSizeBytes = 2_000_000L,
        importedAt = 1L,
        modifiedAt = 1L,
        assetType = MediaAssetType.VIDEO,
        metadata = MediaMetadata(
            durationUs = 10_000_000L,
            durationFramesInSourceRate = 300L,
            containerFormat = "mp4",
            bitrate = 8_000_000L,
            width = 1920,
            height = 1080,
            rotationDegrees = 0,
            pixelAspectRatio = 1f,
            videoStreams = videoStreams,
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
            estimatedFrameRate = FrameRate(30, 1),
            exactFrameRate = FrameRate(30, 1),
            timecodeStart = null,
            creationTime = null,
            variableFrameRateDetectionComplete = true
        )
    )

    private fun mcrawAsset(id: String, withAudio: Boolean = false): MediaAsset {
        val base = asset(id, audioSampleRate = if (withAudio) 48_000 else null)
        val rawStream = videoStream(bitDepth = 12).copy(
            codecName = "motioncam-raw",
            codecMime = MotionCamRawFormat.MIME_TYPE,
            colorTransfer = null,
            colorStandard = null,
            chromaSubsampling = "Bayer",
            hardwareDecodeSupported = false,
            softwareDecodeSupported = true
        )
        val metadata = base.metadata.copy(
            containerFormat = "MotionCam RAW (MCRAW v3)",
            width = 4000,
            height = 3000,
            rotationDegrees = 90,
            videoStreams = listOf(rawStream),
            audioStreams = if (withAudio) base.metadata.audioStreams.map { it.copy(codecMime = "audio/raw") } else emptyList(),
            hasAudio = withAudio,
            estimatedFrameRate = FrameRate(30, 1),
            exactFrameRate = null,
            isVariableFrameRate = false,
            variableFrameRateDetectionComplete = true
        )
        return base.copy(
            displayName = "$id.mcraw",
            originalUriString = "file:///tmp/$id.mcraw",
            mimeType = MotionCamRawFormat.MIME_TYPE,
            metadata = metadata
        )
    }

    private fun videoStream(bitDepth: Int = 8, isHdr: Boolean = false, isLog: Boolean = false) = VideoStreamInfo(
        codecName = "hevc",
        codecMime = "video/hevc",
        codecProfile = null,
        codecLevel = null,
        width = 3840,
        height = 2160,
        frameRate = FrameRate(30, 1),
        bitDepth = bitDepth,
        chromaSubsampling = "4:2:0",
        colorStandard = null,
        colorTransfer = when {
            isHdr -> android.media.MediaFormat.COLOR_TRANSFER_HLG
            bitDepth > 8 -> android.media.MediaFormat.COLOR_TRANSFER_SDR_VIDEO
            else -> null
        },
        colorRange = null,
        hdrStaticMetadata = null,
        hdrDynamicMetadata = null,
        isHdr = isHdr,
        isLog = isLog,
        detectedLogProfile = null,
        hardwareDecodeSupported = true,
        softwareDecodeSupported = false,
        requiresProxy = false,
        requiresTranscode = false
    )

    private fun clip(id: String, mediaId: String, start: Long) = TimelineClip(
        id = id,
        trackId = "video",
        mediaId = mediaId,
        name = "$id.mp4",
        sourceIn = 0L,
        sourceOut = 100L,
        timelineStart = start,
        type = ClipType.MEDIA
    )

    private fun project(
        assets: List<MediaAsset>,
        videoClips: List<TimelineClip>,
        withSeparateAudioTrack: Boolean = false
    ): TimelineProject {
        val tracks = buildList {
            add(TimelineTrack(id = "video", name = "Video", type = TrackType.VIDEO, clips = videoClips))
            if (withSeparateAudioTrack) {
                add(
                    TimelineTrack(
                        id = "audio",
                        name = "Audio",
                        type = TrackType.AUDIO,
                        clips = listOf(clip("audio-clip", assets.first().assetId, 0L))
                    )
                )
            }
        }
        return TimelineProject(
            id = "project",
            settings = ProjectSettings(
                projectId = "project",
                projectName = "Export test",
                frameRate = ProjectFrameRatePreset.FPS_30
            ),
            tracks = tracks,
            mediaAssets = assets
        )
    }

    private fun settings(includeAudio: Boolean = false) = SocialExportPresets
        .youtube1080p("project", "validation_test")
        .copy(includeAudio = includeAudio, frameRate = com.example.timeline.export.model.ExportFrameRate.FPS_30)

    @Test
    fun standardCfrSingleTrackProjectPassesWithAnAvailableH264Encoder() {
        val source = asset("source")
        val project = project(listOf(source), listOf(clip("clip", source.assetId, 0L)))
        val backend = Media3TransformerBackend(applicationContext, CodecProbe())

        val result = backend.validate(settings(), project)

        assertTrue(result.unsupportedFeatures.joinToString("\n"), result.isValid)
    }

    @Test
    fun twoImportedMp4ClipsOnOneSequentialTrackPassExportValidation() {
        val first = asset("first")
        val second = asset("second")
        val project = project(
            listOf(first, second),
            listOf(clip("first-clip", first.assetId, 0L), clip("second-clip", second.assetId, 100L))
        )
        val backend = Media3TransformerBackend(applicationContext, CodecProbe())

        val result = backend.validate(settings(), project)

        assertTrue(result.unsupportedFeatures.joinToString("\n"), result.isValid)
    }

    @Test
    fun splitAndTrimmedMp4SegmentsPassSequentialExportValidation() {
        val source = asset("source")
        val firstSegment = clip("first-segment", source.assetId, 0L).copy(sourceIn = 20L, sourceOut = 60L)
        val secondSegment = clip("second-segment", source.assetId, 40L).copy(sourceIn = 60L, sourceOut = 110L)
        val project = project(listOf(source), listOf(firstSegment, secondSegment))
        val backend = Media3TransformerBackend(applicationContext, CodecProbe())

        val result = backend.validate(settings(), project)

        assertTrue(result.unsupportedFeatures.joinToString("\n"), result.isValid)
    }

    @Test
    fun mcrawSensorBitDepthCanUseTheActualEightBitH264Bridge() {
        val source = mcrawAsset("raw-source")
        val project = project(listOf(source), listOf(clip("clip", source.assetId, 0L)))
        val backend = Media3TransformerBackend(applicationContext, CodecProbe())

        val result = backend.validate(settings(includeAudio = false), project)

        assertTrue(result.unsupportedFeatures.joinToString("\n"), result.isValid)
        assertTrue(result.warnings.any { it.contains("lightweight Bayer renderer to 8-bit SDR") })
        val capabilities = backend.getCapabilities()
        assertEquals(listOf(ExportCodec.H264), capabilities.supportedCodecs)
        assertEquals(listOf(ExportContainer.MP4), capabilities.supportedContainers)
        assertFalse(capabilities.supports10Bit)
        assertFalse(capabilities.supportsProRes)
        assertFalse(capabilities.supportsAV1)
    }

    @Test
    fun mcrawExportIsBlockedWhenRawFrameWouldExceedMemoryBound() {
        val raw = mcrawAsset("raw-too-large")
        // 6200x4200 = 26.04 megapixels, strictly above the 24-megapixel memory bound enforced
        // by both the validator and the MotionCam transcoder's own runtime guard.
        val source = raw.copy(metadata = raw.metadata.copy(width = 6200, height = 4200))
        val project = project(listOf(source), listOf(clip("clip", source.assetId, 0L)))
        val backend = Media3TransformerBackend(applicationContext, CodecProbe())

        val result = backend.validate(settings(includeAudio = false), project)

        assertFalse(result.isValid)
        assertTrue(result.unsupportedFeatures.any { it.contains("above 24 megapixels") })
    }

    @Test
    fun mcrawExportIsBlockedWhenNoCompatibleIntermediateEncoderExists() {
        val source = mcrawAsset("raw-no-flexible-encoder")
        val project = project(listOf(source), listOf(clip("clip", source.assetId, 0L)))
        val backend = Media3TransformerBackend(applicationContext, CodecProbe(mcrawEncoder = false))

        val result = backend.validate(settings(includeAudio = false), project)

        assertFalse(result.isValid)
        assertTrue(result.unsupportedFeatures.any { it.contains("flexible YUV input") })
    }

    @Test
    fun mcrawEmbeddedPcmAudioRequiresSilentVideoExportForNow() {
        val source = mcrawAsset("raw-with-audio", withAudio = true)
        val project = project(listOf(source), listOf(clip("clip", source.assetId, 0L)))
        val backend = Media3TransformerBackend(applicationContext, CodecProbe())

        val result = backend.validate(settings(includeAudio = true), project)

        assertFalse(result.isValid)
        assertTrue(result.unsupportedFeatures.any { it.contains("embedded PCM audio is not connected") })
    }

    @Test
    fun experimentalBackendsDoNotAdvertiseUnwiredCodecOrContainerPaths() {
        val capabilities = listOf(
            MediaCodecExportBackend(applicationContext).getCapabilities(),
            NativeFfmpegExportBackend().getCapabilities(),
            NativeSoftwareExportBackend().getCapabilities()
        )

        capabilities.forEach { capability ->
            assertFalse(capability.backendAvailable)
            assertTrue(capability.supportedCodecs.isEmpty())
            assertTrue(capability.supportedContainers.isEmpty())
            assertFalse(capability.supports10Bit)
            assertFalse(capability.supportsProRes)
            assertFalse(capability.supportsAV1)
        }
    }

    @Test
    fun hdrSourceIsValidatedForExplicitSdrToneMapping() {
        val source = asset("hdr", videoStreams = listOf(videoStream(bitDepth = 10, isHdr = true)))
        val project = project(listOf(source), listOf(clip("clip", source.assetId, 0L)))
        val backend = Media3TransformerBackend(applicationContext, CodecProbe())

        val result = backend.validate(settings(), project)

        assertTrue(result.unsupportedFeatures.joinToString("\n"), result.isValid)
        assertTrue(result.warnings.any { it.contains("tone-mapped to Rec.709 SDR") })
        assertEquals(
            Composition.HDR_MODE_TONE_MAP_HDR_TO_SDR_USING_OPEN_GL,
            hdrModeForExport(hasHdrInput = true)
        )
        assertEquals(Composition.HDR_MODE_KEEP_HDR, hdrModeForExport(hasHdrInput = false))
    }

    @Test
    fun tenBitSdrSourceCanBeConvertedToEightBitH264() {
        val source = asset("ten-bit-sdr", videoStreams = listOf(videoStream(bitDepth = 10)))
        val project = project(listOf(source), listOf(clip("clip", source.assetId, 0L)))
        val backend = Media3TransformerBackend(applicationContext, CodecProbe())

        val result = backend.validate(settings(), project)

        assertTrue(result.unsupportedFeatures.joinToString("\n"), result.isValid)
        assertTrue(result.warnings.any { it.contains("converted to 8-bit H.264") })
    }

    @Test
    fun tenBitSourceWithUnknownTransferIsBlockedRatherThanAssumedSdr() {
        val stream = videoStream(bitDepth = 10).copy(colorTransfer = null)
        val source = asset("unknown-transfer", videoStreams = listOf(stream))
        val project = project(listOf(source), listOf(clip("clip", source.assetId, 0L)))
        val backend = Media3TransformerBackend(applicationContext, CodecProbe())

        val result = backend.validate(settings(), project)

        assertFalse(result.isValid)
        assertTrue(result.unsupportedFeatures.any { it.contains("no verified SDR transfer metadata") })
    }

    @Test
    fun logSourceRemainsBlockedInsteadOfBeingSilentlyTreatedAsRec709() {
        val source = asset("log", videoStreams = listOf(videoStream(bitDepth = 10, isLog = true)))
        val project = project(listOf(source), listOf(clip("clip", source.assetId, 0L)))
        val backend = Media3TransformerBackend(applicationContext, CodecProbe())

        val result = backend.validate(settings(), project)

        assertFalse(result.isValid)
        assertTrue(result.unsupportedFeatures.any { it.contains("Log-encoded") })
    }

    @Test
    @Config(sdk = [28])
    fun hdrSourceIsRejectedOnAndroidVersionsWithoutOpenGlToneMappingSupport() {
        val source = asset("hdr", videoStreams = listOf(videoStream(bitDepth = 10, isHdr = true)))
        val project = project(listOf(source), listOf(clip("clip", source.assetId, 0L)))
        val backend = Media3TransformerBackend(applicationContext, CodecProbe())

        val result = backend.validate(settings(), project)

        assertFalse(result.isValid)
        assertTrue(result.unsupportedFeatures.any { it.contains("HDR-to-SDR export requires Android 10") })
    }

    @Test
    fun videoOnlyExportDoesNotFailForMixedEmbeddedAudioOrSeparateAudioTracks() {
        val first = asset("first", audioSampleRate = 44_100)
        val second = asset("second", audioSampleRate = 48_000)
        val project = project(
            listOf(first, second),
            listOf(clip("first-clip", first.assetId, 0L), clip("second-clip", second.assetId, 100L)),
            withSeparateAudioTrack = true
        )
        val backend = Media3TransformerBackend(applicationContext, CodecProbe())

        val result = backend.validate(settings(includeAudio = false), project)

        assertTrue(result.unsupportedFeatures.joinToString("\n"), result.isValid)
        assertFalse(shouldIncludeEmbeddedAudio(settings(includeAudio = false), trackMuted = false, clipHasAudio = listOf(true, true)))
    }

    @Test
    fun enabledAudioReportsSampleRateMismatchAndCanBeDisabled() {
        val first = asset("first", audioSampleRate = 44_100)
        val second = asset("second", audioSampleRate = 48_000)
        val project = project(
            listOf(first, second),
            listOf(clip("first-clip", first.assetId, 0L), clip("second-clip", second.assetId, 100L))
        )
        val backend = Media3TransformerBackend(applicationContext, CodecProbe())

        val result = backend.validate(settings(includeAudio = true).copy(audioSampleRate = 44_100), project)

        assertFalse(result.isValid)
        assertTrue(result.unsupportedFeatures.any { it.contains("sample rate and channels must match") })
    }

    @Test
    fun compatibleSoftwareEncoderIsNotRejectedJustBecauseHardwareWasNotDetected() {
        val source = asset("source")
        val project = project(listOf(source), listOf(clip("clip", source.assetId, 0L)))
        val backend = Media3TransformerBackend(
            applicationContext,
            CodecProbe(anyVideoEncoder = true, hardwareVideoEncoder = false)
        )

        val result = backend.validate(settings(), project)

        assertTrue(result.unsupportedFeatures.joinToString("\n"), result.isValid)
        assertTrue(result.warnings.any { it.contains("software encoder") })
    }

    private fun clipGrade(primaryCorrections: ColorGradeParams) = ColorGradeStack(
        targetType = ColorGradeTarget.CLIP,
        targetId = "clip",
        primaryCorrections = primaryCorrections
    )

    @Test
    fun clipGradeAndEnabledColorNodesAreComposedIntoTheSharedExportEffect() {
        val source = asset("graded-source")
        val node = ColorGradeLayer(
            id = "node",
            name = "Primary node",
            type = ColorLayerType.PRIMARY_CORRECTION,
            grade = clipGrade(ColorGradeParams(contrast = 0.2f))
        )
        val gradedClip = clip("graded", source.assetId, 0L).copy(
            colorGrade = clipGrade(
                ColorGradeParams(
                    lift = ColorWheelParams(r = 0.25f),
                    saturation = 1.4f
                )
            ),
            colorLayers = ColorGradeLayerStack(layers = listOf(node))
        )
        val project = project(listOf(source), listOf(gradedClip))
        val backend = Media3TransformerBackend(applicationContext, CodecProbe())

        val result = backend.validate(settings(), project)

        assertTrue(result.unsupportedFeatures.joinToString("\n"), result.isValid)
    }

    @Test
    fun colorMatchOnClipOrNodesIsRejectedInsteadOfSilentlyDropped() {
        val source = asset("match-source")
        val gradedClip = clip("matched", source.assetId, 0L).copy(
            colorGrade = clipGrade(ColorGradeParams())
                .copy(colorMatch = com.example.model.colorgrade.ColorMatchParams(enabled = true))
        )
        val project = project(listOf(source), listOf(gradedClip))
        val backend = Media3TransformerBackend(applicationContext, CodecProbe())

        val result = backend.validate(settings(), project)

        assertFalse(result.isValid)
        assertTrue(result.unsupportedFeatures.any { it.contains("Color Match") })
    }

    @Test
    fun animatedClipGradesAreRejectedInsteadOfExportingOneStaticFrame() {
        val source = asset("animated-source")
        val keyframedGrade = clipGrade(ColorGradeParams(saturation = 1.4f)).copy(
            keyframes = listOf(
                ColorGradeKeyframe(
                    parameterId = "primaryCorrections.saturation",
                    frame = 0L,
                    value = 1f
                ),
                ColorGradeKeyframe(
                    parameterId = "primaryCorrections.saturation",
                    frame = 10L,
                    value = 1.8f
                )
            )
        )
        val gradedClip = clip("animated", source.assetId, 0L).copy(colorGrade = keyframedGrade)
        val project = project(listOf(source), listOf(gradedClip))
        val backend = Media3TransformerBackend(applicationContext, CodecProbe())

        val result = backend.validate(settings(), project)

        assertFalse(result.isValid)
        assertTrue(result.unsupportedFeatures.any { it.contains("Animated clip color grades") })
    }
}
