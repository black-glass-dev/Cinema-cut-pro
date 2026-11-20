package com.example.timeline.export.backend

import com.example.timeline.media.FrameRate
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class Media3TransformerBackendValidationRulesTest {
    @Test
    fun resolutionLimitPreservesPortraitAndLandscapeOrientations() {
        assertTrue(isWithinMedia3ResolutionLimit(1920, 1080, 3840, 2160))
        assertTrue(isWithinMedia3ResolutionLimit(1080, 1920, 3840, 2160))
        assertTrue(isWithinMedia3ResolutionLimit(3840, 2160, 3840, 2160))
        assertTrue(isWithinMedia3ResolutionLimit(2160, 3840, 3840, 2160))
    }

    @Test
    fun mcrawIntermediateDimensionsHonorRotationScaleAndEvenEncoderSizes() {
        assertEquals(McrawRenderSize(1440, 1920), mcrawRenderSize(4000, 3000, 90, 1920))
        assertEquals(McrawRenderSize(1920, 1080), mcrawRenderSize(1920, 1080, 0, 3840))
        assertEquals(null, mcrawRenderSize(4000, 3000, 45, 1920))
        assertEquals(null, mcrawRenderSize(1, 1080, 0, 1920))
    }

    @Test
    fun mcrawClipTimesMapToSourceFramesUsingTheSourceRate() {
        assertEquals(
            SourceFrameRange(startFrame = 30L, endFrameExclusive = 60L),
            mcrawFrameRangeForTimeUs(1_000_000L, 2_000_000L, FrameRate(30, 1))
        )
        assertEquals(
            SourceFrameRange(startFrame = 1L, endFrameExclusive = 2L),
            mcrawFrameRangeForTimeUs(40_000L, 50_000L, FrameRate(30, 1))
        )
        assertEquals(
            SourceFrameRange(startFrame = 2_400L, endFrameExclusive = 2_402L),
            mcrawFrameRangeForTimeUs(100_100_000L, 100_150_000L, FrameRate(24_000, 1_001))
        )
    }

    @Test
    fun mcrawClipTimeMappingRejectsInvalidOrOverflowingRanges() {
        assertEquals(null, mcrawFrameRangeForTimeUs(-1L, 10L, FrameRate(30, 1)))
        assertEquals(null, mcrawFrameRangeForTimeUs(10L, 10L, FrameRate(30, 1)))
        assertEquals(null, mcrawFrameRangeForTimeUs(0L, 100L, FrameRate(30, 0)))
        assertEquals(null, mcrawFrameRangeForTimeUs(0L, Long.MAX_VALUE, FrameRate(Int.MAX_VALUE, 1)))
    }

    @Test
    fun resolutionLimitRejectsOversizedOrInvalidDimensions() {
        assertFalse(isWithinMedia3ResolutionLimit(4096, 2160, 3840, 2160))
        assertFalse(isWithinMedia3ResolutionLimit(2160, 4096, 3840, 2160))
        assertFalse(isWithinMedia3ResolutionLimit(3000, 3000, 3840, 2160))
        assertFalse(isWithinMedia3ResolutionLimit(0, 1080, 3840, 2160))
    }
}
