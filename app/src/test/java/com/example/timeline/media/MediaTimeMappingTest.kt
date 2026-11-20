package com.example.timeline.media

import org.junit.Assert.assertEquals
import org.junit.Test

class MediaTimeMappingTest {
    @Test
    fun rationalSourceAndProjectFrameConversionsStayIntegerExact() {
        assertEquals(
            24_024L,
            MediaTimeMapping.sourceFrameToProjectFrame(
                sourceFrame = 24_000L,
                sourceFps = FrameRate.FPS_23_976,
                projectFps = com.example.timeline.core.Rational(24, 1)
            )
        )
        assertEquals(
            24_000L,
            MediaTimeMapping.projectFrameToSourceFrame(
                projectFrame = 24_024L,
                projectFps = com.example.timeline.core.Rational(24, 1),
                sourceFps = FrameRate.FPS_23_976
            )
        )
    }

    @Test
    fun medianFrameIntervalProducesAnExactBoundedRationalRate() {
        assertEquals(FrameRate(1, 1), MediaTimeMapping.frameRateFromIntervalNanoseconds(1_000_000_000L))
        assertEquals(
            FrameRate(1_000_000_000, 41_708_333),
            MediaTimeMapping.frameRateFromIntervalNanoseconds(41_708_333L)
        )
        assertEquals(null, MediaTimeMapping.frameRateFromIntervalNanoseconds(0L))
        assertEquals(null, MediaTimeMapping.frameRateFromIntervalNanoseconds(1_000_000_001L))
    }

    @Test
    fun nominalTimecodeRateUsesIntegerRoundingForFractionalRates() {
        assertEquals("00:00:01:00", MediaTimeMapping.frameToTimecode(30L, FrameRate.FPS_29_97))
        assertEquals(30L, MediaTimeMapping.timecodeToFrame("00:00:01:00", FrameRate.FPS_29_97))
    }
}
