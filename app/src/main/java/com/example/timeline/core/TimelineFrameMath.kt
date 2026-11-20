package com.example.timeline.core

import java.math.BigInteger

/** Exact integer conversions at media/UI boundaries; timeline edit semantics remain native. */
object TimelineFrameMath {
    private const val MILLIS_PER_SECOND = 1_000L
    private const val MICROS_PER_SECOND = 1_000_000L

    /** Converts a non-negative media timestamp to project frames using floor rounding. */
    fun framesFromMilliseconds(milliseconds: Long, frameRate: Rational): Long? =
        scaleFloor(
            value = milliseconds,
            numeratorFactors = longArrayOf(frameRate.numerator.toLong()),
            denominatorFactors = longArrayOf(MILLIS_PER_SECOND, frameRate.denominator.toLong())
        )

    /** Converts a non-negative media timestamp to project frames using floor rounding. */
    fun framesFromMicroseconds(microseconds: Long, frameRate: Rational): Long? =
        scaleFloor(
            value = microseconds,
            numeratorFactors = longArrayOf(frameRate.numerator.toLong()),
            denominatorFactors = longArrayOf(MICROS_PER_SECOND, frameRate.denominator.toLong())
        )

    /** Counts the output frames needed to cover a non-negative media duration. */
    fun framesFromMicrosecondsCeiling(microseconds: Long, frameRate: Rational): Long? =
        convertFrameCountCeiling(
            frameCount = microseconds,
            sourceNumerator = MICROS_PER_SECOND.toInt(),
            sourceDenominator = 1,
            targetNumerator = frameRate.numerator,
            targetDenominator = frameRate.denominator
        )

    /** Converts frame counts to integer microseconds with floor rounding. */
    fun microsecondsFromFrames(frameCount: Long, frameRate: Rational): Long? =
        convertFrameCount(
            frameCount = frameCount,
            sourceNumerator = frameRate.numerator,
            sourceDenominator = frameRate.denominator,
            projectNumerator = MICROS_PER_SECOND.toInt(),
            projectDenominator = 1
        )

    /** Converts source-frame counts to project-frame counts with floor rounding. */
    fun convertFrameCount(
        frameCount: Long,
        sourceNumerator: Int,
        sourceDenominator: Int,
        projectNumerator: Int,
        projectDenominator: Int
    ): Long? = scaleFloor(
        value = frameCount,
        numeratorFactors = longArrayOf(sourceDenominator.toLong(), projectNumerator.toLong()),
        denominatorFactors = longArrayOf(sourceNumerator.toLong(), projectDenominator.toLong())
    )

    /**
     * Converts a duration expressed in source frames to the number of target frames needed to cover
     * it. This is the ceiling counterpart used for output frame counts, not frame-position mapping.
     */
    fun convertFrameCountCeiling(
        frameCount: Long,
        sourceNumerator: Int,
        sourceDenominator: Int,
        targetNumerator: Int,
        targetDenominator: Int
    ): Long? {
        if (frameCount < 0L || sourceNumerator <= 0 || sourceDenominator <= 0 ||
            targetNumerator <= 0 || targetDenominator <= 0
        ) return null
        val numerator = BigInteger.valueOf(frameCount)
            .multiply(BigInteger.valueOf(sourceDenominator.toLong()))
            .multiply(BigInteger.valueOf(targetNumerator.toLong()))
        val denominator = BigInteger.valueOf(sourceNumerator.toLong())
            .multiply(BigInteger.valueOf(targetDenominator.toLong()))
        val division = numerator.divideAndRemainder(denominator)
        val rounded = if (division[1] == BigInteger.ZERO) division[0] else division[0].add(BigInteger.ONE)
        return try {
            rounded.longValueExact()
        } catch (_: ArithmeticException) {
            null
        }
    }

    /** SMPTE-style nominal integer rate rounded to nearest, with half-up tie handling. */
    fun nominalFramesPerSecond(frameRate: Rational): Long? {
        if (frameRate.numerator <= 0 || frameRate.denominator <= 0) return null
        val numerator = BigInteger.valueOf(frameRate.numerator.toLong())
        val denominator = BigInteger.valueOf(frameRate.denominator.toLong())
        val parts = numerator.divideAndRemainder(denominator)
        val rounded = if (parts[1].shiftLeft(1) >= denominator) {
            parts[0].add(BigInteger.ONE)
        } else {
            parts[0]
        }
        if (rounded <= BigInteger.ZERO) return null
        return try {
            rounded.longValueExact()
        } catch (_: ArithmeticException) {
            null
        }
    }

    private fun scaleFloor(
        value: Long,
        numeratorFactors: LongArray,
        denominatorFactors: LongArray
    ): Long? {
        if (value < 0L || numeratorFactors.any { it <= 0L } || denominatorFactors.any { it <= 0L }) {
            return null
        }
        var numerator = BigInteger.valueOf(value)
        numeratorFactors.forEach { numerator = numerator.multiply(BigInteger.valueOf(it)) }
        var denominator = BigInteger.ONE
        denominatorFactors.forEach { denominator = denominator.multiply(BigInteger.valueOf(it)) }
        return try {
            numerator.divide(denominator).longValueExact()
        } catch (_: ArithmeticException) {
            null
        }
    }
}
