package com.example.timeline.media

import com.example.timeline.core.FrameTimecode
import com.example.timeline.core.Rational
import com.example.timeline.core.TimelineFrameMath

/** Integer-only mappings between decoded media timestamps and project/source frame counts. */
object MediaTimeMapping {
    fun sourceFrameToProjectFrame(
        sourceFrame: Long,
        sourceFps: FrameRate,
        projectFps: Rational
    ): Long = requireConvertedFrame(
        TimelineFrameMath.convertFrameCount(
            frameCount = sourceFrame,
            sourceNumerator = sourceFps.numerator,
            sourceDenominator = sourceFps.denominator,
            projectNumerator = projectFps.numerator,
            projectDenominator = projectFps.denominator
        )
    )

    fun projectFrameToSourceFrame(
        projectFrame: Long,
        projectFps: Rational,
        sourceFps: FrameRate
    ): Long = requireConvertedFrame(
        TimelineFrameMath.convertFrameCount(
            frameCount = projectFrame,
            sourceNumerator = projectFps.numerator,
            sourceDenominator = projectFps.denominator,
            projectNumerator = sourceFps.numerator,
            projectDenominator = sourceFps.denominator
        )
    )

    fun microsecondsToSourceFrame(micros: Long, sourceFps: FrameRate): Long = requireConvertedFrame(
        TimelineFrameMath.framesFromMicroseconds(micros, Rational(sourceFps.numerator, sourceFps.denominator))
    )

    fun sourceFrameToMicroseconds(sourceFrame: Long, sourceFps: FrameRate): Long = requireConvertedFrame(
        TimelineFrameMath.microsecondsFromFrames(
            sourceFrame,
            Rational(sourceFps.numerator, sourceFps.denominator)
        )
    )

    /** Represents a median microsecond frame interval as an exact rational rate, bounded to 1–240 fps. */
    fun frameRateFromIntervalMicroseconds(intervalUs: Long): FrameRate? {
        if (intervalUs < 4_167L || intervalUs > 1_000_000L) return null
        val numerator = 1_000_000L
        var a = numerator
        var b = intervalUs
        while (b != 0L) {
            val remainder = a % b
            a = b
            b = remainder
        }
        val divisor = a
        return FrameRate((numerator / divisor).toInt(), (intervalUs / divisor).toInt())
    }

    /** Represents a median nanosecond frame interval as an exact rational rate, bounded to 1–240 fps. */
    fun frameRateFromIntervalNanoseconds(intervalNs: Long): FrameRate? {
        if (intervalNs < 4_166_667L || intervalNs > 1_000_000_000L) return null
        val numerator = 1_000_000_000L
        var a = numerator
        var b = intervalNs
        while (b != 0L) {
            val remainder = a % b
            a = b
            b = remainder
        }
        val divisor = a
        return FrameRate((numerator / divisor).toInt(), (intervalNs / divisor).toInt())
    }

    /** Uses integer nominal-rate display; this does not implement drop-frame timecode. */
    fun frameToTimecode(frame: Long, fps: FrameRate): String =
        FrameTimecode.frameToTimecode(frame, Rational(fps.numerator, fps.denominator))

    fun timecodeToFrame(timecode: String, fps: FrameRate): Long =
        FrameTimecode.timecodeToFrame(timecode, Rational(fps.numerator, fps.denominator))

    private fun requireConvertedFrame(value: Long?): Long =
        value ?: throw ArithmeticException("Frame-rate conversion is invalid or overflows Long")
}
