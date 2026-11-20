package com.example.timeline.engine.preview

import org.junit.Assert.assertEquals
import org.junit.Test

class PreviewEngineRoutingTest {
    @Test
    fun automaticSelectionUsesMedia3AsPrimary() {
        assertEquals(
            PreviewEngineType.MEDIA3_FALLBACK,
            resolveRequestedPreviewEngine(PreviewEngineType.AUTO)
        )
    }

    @Test
    fun motionCamRawHasDedicatedNativePreviewRoute() {
        assertEquals(
            PreviewEngineType.MOTIONCAM_RAW,
            resolveRequestedPreviewEngine(PreviewEngineType.MOTIONCAM_RAW)
        )
    }

    @Test
    fun explicitFfmpegAndLibVlcPreferencesRemainAvailable() {
        assertEquals(
            PreviewEngineType.FFMPEG_NATIVE,
            resolveRequestedPreviewEngine(PreviewEngineType.FFMPEG_NATIVE)
        )
        assertEquals(
            PreviewEngineType.VLC_NATIVE,
            resolveRequestedPreviewEngine(PreviewEngineType.VLC_NATIVE)
        )
        assertEquals("FFmpeg native playback", PreviewEngineType.FFMPEG_NATIVE.displayName)
        assertEquals("LibVLC playback fallback", PreviewEngineType.VLC_NATIVE.displayName)
    }

    @Test
    fun pausedScrubbingSeeksAtFrameCadenceAndCoalescesSubFrameNoise() {
        assertEquals(true, shouldSeekPreviewPosition(positionDeltaMs = 17L, isPlaying = false, forceSeek = false))
        assertEquals(false, shouldSeekPreviewPosition(positionDeltaMs = 8L, isPlaying = false, forceSeek = false))
        assertEquals(false, shouldSeekPreviewPosition(positionDeltaMs = 100L, isPlaying = true, forceSeek = false))
        assertEquals(true, shouldSeekPreviewPosition(positionDeltaMs = 301L, isPlaying = true, forceSeek = false))
        assertEquals(true, shouldSeekPreviewPosition(positionDeltaMs = 0L, isPlaying = true, forceSeek = true))
    }

    @Test
    fun unsupportedOriginalMediaCanUseFfmpegButEffectsAndProxyFailuresCannot() {
        assertEquals(true, shouldFallbackToFfmpegForUnsupportedSource(
            engineType = PreviewEngineType.MEDIA3_FALLBACK,
            failureCategory = PreviewPlaybackFailureCategory.UNSUPPORTED_MEDIA,
            autoFallbackEnabled = true,
            isProxySource = false,
            colorGradingPreviewActive = false,
            fallbackAlreadyAttempted = false
        ))
        assertEquals(false, shouldFallbackToFfmpegForUnsupportedSource(
            engineType = PreviewEngineType.MEDIA3_FALLBACK,
            failureCategory = PreviewPlaybackFailureCategory.UNSUPPORTED_MEDIA,
            autoFallbackEnabled = true,
            isProxySource = true,
            colorGradingPreviewActive = false,
            fallbackAlreadyAttempted = false
        ))
        assertEquals(false, shouldFallbackToFfmpegForUnsupportedSource(
            engineType = PreviewEngineType.MEDIA3_FALLBACK,
            failureCategory = PreviewPlaybackFailureCategory.DECODER,
            autoFallbackEnabled = true,
            isProxySource = false,
            colorGradingPreviewActive = false,
            fallbackAlreadyAttempted = false
        ))
        assertEquals(false, shouldFallbackToFfmpegForUnsupportedSource(
            engineType = PreviewEngineType.MEDIA3_FALLBACK,
            failureCategory = PreviewPlaybackFailureCategory.UNSUPPORTED_MEDIA,
            autoFallbackEnabled = true,
            isProxySource = false,
            colorGradingPreviewActive = true,
            fallbackAlreadyAttempted = false
        ))
        assertEquals(false, shouldFallbackToFfmpegForUnsupportedSource(
            engineType = PreviewEngineType.MEDIA3_FALLBACK,
            failureCategory = PreviewPlaybackFailureCategory.UNSUPPORTED_MEDIA,
            autoFallbackEnabled = true,
            isProxySource = false,
            colorGradingPreviewActive = false,
            fallbackAlreadyAttempted = true
        ))
    }

    @Test
    fun liveGradeFallbackUsesTheRequestedEngineNotTheTemporaryActiveEngine() {
        assertEquals(true, requiresMedia3ForLiveGrade(PreviewEngineType.VLC_NATIVE, isMotionCamRaw = false))
        assertEquals(true, requiresMedia3ForLiveGrade(PreviewEngineType.FFMPEG_NATIVE, isMotionCamRaw = false))
        assertEquals(true, requiresMedia3ForLiveGrade(PreviewEngineType.BROWSER, isMotionCamRaw = false))
        assertEquals(false, requiresMedia3ForLiveGrade(PreviewEngineType.AUTO, isMotionCamRaw = false))
        assertEquals(false, requiresMedia3ForLiveGrade(PreviewEngineType.MEDIA3_FALLBACK, isMotionCamRaw = false))
        assertEquals(false, requiresMedia3ForLiveGrade(PreviewEngineType.VLC_NATIVE, isMotionCamRaw = true))
    }
}
