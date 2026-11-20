package com.example.timeline.core

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

class TimelineFrameMathTest {
    @Test
    fun microsecondsUseExactRationalFloorConversion() {
        assertEquals(23L, TimelineFrameMath.framesFromMicroseconds(1_000_000L, Rational(24_000, 1_001)))
        assertEquals(24L, TimelineFrameMath.framesFromMicroseconds(1_001_000L, Rational(24_000, 1_001)))
    }

    @Test
    fun millisecondsUseExactRationalFloorConversion() {
        assertEquals(23L, TimelineFrameMath.framesFromMilliseconds(1_000L, Rational(24_000, 1_001)))
        assertEquals(24L, TimelineFrameMath.framesFromMilliseconds(1_001L, Rational(24_000, 1_001)))
    }

    @Test
    fun outputFrameCountsUseCeilingToCoverTheRequestedDuration() {
        assertEquals(1L, TimelineFrameMath.framesFromMicrosecondsCeiling(1L, Rational(24, 1)))
        assertEquals(24L, TimelineFrameMath.framesFromMicrosecondsCeiling(1_000_000L, Rational(24, 1)))
        assertEquals(
            2L,
            TimelineFrameMath.convertFrameCountCeiling(
                frameCount = 1L,
                sourceNumerator = 24,
                sourceDenominator = 1,
                targetNumerator = 25,
                targetDenominator = 1
            )
        )
        assertNull(TimelineFrameMath.framesFromMicrosecondsCeiling(-1L, Rational(24, 1)))
        assertNull(TimelineFrameMath.framesFromMicrosecondsCeiling(Long.MAX_VALUE, Rational(Int.MAX_VALUE, 1)))
        assertNull(
            TimelineFrameMath.convertFrameCountCeiling(
                frameCount = 1L,
                sourceNumerator = 0,
                sourceDenominator = 1,
                targetNumerator = 24,
                targetDenominator = 1
            )
        )
    }

    @Test
    fun rationalOutputFrameBoundariesDistributeAudioSamplesWithoutCumulativeDrift() {
        fun sampleBoundary(frame: Long): Long = requireNotNull(
            TimelineFrameMath.convertFrameCount(
                frameCount = frame,
                sourceNumerator = 24_000,
                sourceDenominator = 1_001,
                projectNumerator = 44_100,
                projectDenominator = 1
            )
        )

        assertEquals(0L, sampleBoundary(0L))
        assertEquals(1_839L, sampleBoundary(1L))
        assertEquals(3_678L, sampleBoundary(2L))
        assertEquals(5_518L, sampleBoundary(3L))
        assertEquals(44_144L, sampleBoundary(24L))
    }

    @Test
    fun sourceFrameRateConversionDoesNotRoundThroughFloatingPoint() {
        assertEquals(
            24_024L,
            TimelineFrameMath.convertFrameCount(
                frameCount = 24_000L,
                sourceNumerator = 24_000,
                sourceDenominator = 1_001,
                projectNumerator = 24,
                projectDenominator = 1
            )
        )
    }

    @Test
    fun frameToMicrosecondAndNominalTimecodeConversionsAreExact() {
        assertEquals(1_001_000L, TimelineFrameMath.microsecondsFromFrames(24L, Rational(24_000, 1_001)))
        assertEquals(30L, TimelineFrameMath.nominalFramesPerSecond(Rational(30_000, 1_001)))
        assertNull(TimelineFrameMath.nominalFramesPerSecond(Rational(0, 1)))
    }

    @Test
    fun invalidRatesNegativeTimesAndOverflowReject() {
        assertNull(TimelineFrameMath.framesFromMilliseconds(-1L, Rational(24, 1)))
        assertNull(TimelineFrameMath.framesFromMicroseconds(1L, Rational(24, 0)))
        assertNull(TimelineFrameMath.framesFromMicroseconds(Long.MAX_VALUE, Rational(Int.MAX_VALUE, 1)))
        assertNull(
            TimelineFrameMath.convertFrameCount(
                frameCount = Long.MAX_VALUE,
                sourceNumerator = 1,
                sourceDenominator = 1,
                projectNumerator = 120,
                projectDenominator = 1
            )
        )
    }
}
