package com.example.timeline.media

internal enum class FrameRateCadence {
    UNKNOWN,
    CONSTANT,
    VARIABLE
}

internal data class FrameRateCadenceAnalysis(
    val cadence: FrameRateCadence,
    val medianIntervalUs: Long?
)

/**
 * Classifies cadence using integer MediaExtractor presentation timestamps. Timestamp intervals within
 * five percent of their median are treated as CFR; isolated discontinuities are not sufficient evidence
 * of VFR. UNKNOWN is kept distinct from CFR when the probe has too few usable timestamps.
 */
internal object VariableFrameRateDetector {
    private const val MIN_INTERVAL_COUNT = 12
    private const val MIN_OUTLIERS = 2
    private const val OUTLIER_RATIO_PERCENT = 5L
    private const val MIN_TOLERANCE_US = 500L
    private const val RELATIVE_TOLERANCE_DENOMINATOR = 20L

    fun classifySegments(timestampSegmentsUs: List<List<Long>>): FrameRateCadence {
        val classifications = timestampSegmentsUs.map(::classify)
        return when {
            classifications.any { it == FrameRateCadence.VARIABLE } -> FrameRateCadence.VARIABLE
            classifications.any { it == FrameRateCadence.CONSTANT } -> FrameRateCadence.CONSTANT
            else -> FrameRateCadence.UNKNOWN
        }
    }

    fun classify(presentationTimesUs: List<Long>): FrameRateCadence =
        analyze(presentationTimesUs).cadence

    fun analyze(presentationTimesUs: List<Long>): FrameRateCadenceAnalysis {
        // MediaExtractor exposes samples in decode order for some containers; cadence is based on
        // presentation timestamps, so compare adjacent timestamps after ordering them by PTS.
        val ordered = presentationTimesUs.sorted()
        val intervals = ordered.zipWithNext { previous, next ->
            try {
                Math.subtractExact(next, previous)
            } catch (_: ArithmeticException) {
                0L
            }
        }.filter { it > 0L }
        if (intervals.isEmpty()) return FrameRateCadenceAnalysis(FrameRateCadence.UNKNOWN, null)

        val sortedIntervals = intervals.sorted()
        val median = if (sortedIntervals.size % 2 == 0) {
            val lower = sortedIntervals[sortedIntervals.size / 2 - 1]
            val upper = sortedIntervals[sortedIntervals.size / 2]
            lower + (upper - lower) / 2L
        } else {
            sortedIntervals[sortedIntervals.size / 2]
        }
        if (intervals.size < MIN_INTERVAL_COUNT) {
            return FrameRateCadenceAnalysis(FrameRateCadence.UNKNOWN, median)
        }

        val toleranceUs = maxOf(MIN_TOLERANCE_US, median / RELATIVE_TOLERANCE_DENOMINATOR)
        val outlierCount = intervals.count { interval ->
            val difference = if (interval >= median) interval - median else median - interval
            difference > toleranceUs
        }
        val requiredOutliers = maxOf(
            MIN_OUTLIERS,
            ((intervals.size.toLong() * OUTLIER_RATIO_PERCENT + 99L) / 100L).toInt()
        )
        val cadence = if (outlierCount >= requiredOutliers) FrameRateCadence.VARIABLE
            else FrameRateCadence.CONSTANT
        return FrameRateCadenceAnalysis(cadence, median)
    }

    // Retained for existing call sites/tests while callers migrate to the tri-state result.
    fun isVariableFrameRateSegments(timestampSegmentsUs: List<List<Long>>): Boolean =
        classifySegments(timestampSegmentsUs) == FrameRateCadence.VARIABLE

    fun isVariableFrameRate(presentationTimesUs: List<Long>): Boolean =
        classify(presentationTimesUs) == FrameRateCadence.VARIABLE
}
