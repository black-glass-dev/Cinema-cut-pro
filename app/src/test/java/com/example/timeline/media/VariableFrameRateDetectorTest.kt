package com.example.timeline.media

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class VariableFrameRateDetectorTest {
    @Test
    fun constantFrameRateTimestampRoundingIsNotMarkedVariable() {
        val timestampsUs = (0L..120L).map { frame -> frame * 1_000_000L * 1001L / 24_000L }

        assertFalse(VariableFrameRateDetector.isVariableFrameRate(timestampsUs))
    }

    @Test
    fun timestampJitterBelowFivePercentIsTreatedAsConstantFrameRate() {
        val timestampsUs = mutableListOf(0L)
        repeat(120) { index ->
            val intervalUs = if (index % 2 == 0) 31_800L else 34_866L
            timestampsUs += timestampsUs.last() + intervalUs
        }

        assertFalse(VariableFrameRateDetector.isVariableFrameRate(timestampsUs))
    }

    @Test
    fun repeatedCadenceChangesAreMarkedVariable() {
        val timestampsUs = mutableListOf(0L)
        repeat(120) { index ->
            val intervalUs = if (index % 8 == 0) 50_000L else 33_333L
            timestampsUs += timestampsUs.last() + intervalUs
        }

        assertTrue(VariableFrameRateDetector.isVariableFrameRate(timestampsUs))
    }

    @Test
    fun presentationTimestampsAreSortedBeforeCadenceAnalysis() {
        val timestamps = (0L..80L).map { frame -> frame * 33_333L }.shuffled()
        assertFalse(VariableFrameRateDetector.isVariableFrameRate(timestamps))
    }

    @Test
    fun cadenceChangesInAnySampledSegmentAreDetected() {
        val constantSegment = (0L..80L).map { frame -> frame * 33_333L }
        val variableSegment = mutableListOf(0L)
        repeat(80) { index ->
            val intervalUs = if (index % 6 == 0) 50_000L else 33_333L
            variableSegment += variableSegment.last() + intervalUs
        }

        assertTrue(
            VariableFrameRateDetector.isVariableFrameRateSegments(
                listOf(constantSegment, variableSegment, constantSegment)
            )
        )
    }

    @Test
    fun anIsolatedTimestampGapIsTreatedAsDiscontinuityNotVfr() {
        val timestampsUs = mutableListOf(0L)
        repeat(120) { index ->
            val intervalUs = if (index == 60) 250_000L else 33_333L
            timestampsUs += timestampsUs.last() + intervalUs
        }

        assertFalse(VariableFrameRateDetector.isVariableFrameRate(timestampsUs))
    }

    @Test
    fun tooFewSamplesAreReportedAsUnknownRatherThanCfr() {
        val timestampsUs = listOf(0L, 33_333L, 66_666L, 99_999L)

        assertEquals(FrameRateCadence.UNKNOWN, VariableFrameRateDetector.classify(timestampsUs))
        assertFalse(VariableFrameRateDetector.isVariableFrameRate(timestampsUs))
    }

    @Test
    fun cadenceAnalysisReturnsIntegerMedianEvenWhenClassificationIsUnknown() {
        val timestampsUs = listOf(0L, 33_333L, 66_667L, 100_000L)
        val analysis = VariableFrameRateDetector.analyze(timestampsUs)

        assertEquals(FrameRateCadence.UNKNOWN, analysis.cadence)
        assertEquals(33_333L, analysis.medianIntervalUs)
        assertEquals(
            FrameRate(1_000_000, 33_333),
            MediaTimeMapping.frameRateFromIntervalMicroseconds(33_333L)
        )
    }

    @Test
    fun failedTimestampProbeRemainsUnknown() {
        assertEquals(FrameRateCadence.UNKNOWN, VariableFrameRateDetector.classifySegments(emptyList()))
    }
}
