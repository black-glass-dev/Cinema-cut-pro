package com.example.timeline.export.resolver

import com.example.timeline.core.Rational
import com.example.timeline.core.TimelineFrameMath
import com.example.timeline.export.model.ExportFrameRate
import com.example.timeline.export.model.ExportSettings

/** Checked integer conversion at the export time boundary. */
object ExportFrameTimeMapper {
    fun outputFrameToTimeUs(outputFrameIndex: Long, settings: ExportSettings): Long? =
        frameToMicroseconds(outputFrameIndex, settings.frameRate)

    fun timeUsToProjectFrame(timeUs: Long, fpsRational: Rational): Long? =
        TimelineFrameMath.framesFromMicroseconds(timeUs, fpsRational)

    fun projectFrameToTimeUs(projectFrame: Long, fpsRational: Rational): Long? =
        frameToMicroseconds(projectFrame, fpsRational.numerator, fpsRational.denominator)

    fun timeUsToFrame(timeUs: Long, frameRate: ExportFrameRate): Long? =
        TimelineFrameMath.framesFromMicroseconds(
            timeUs,
            Rational(frameRate.numerator, frameRate.denominator)
        )

    fun frameToMicroseconds(frame: Long, frameRate: ExportFrameRate): Long? =
        frameToMicroseconds(frame, frameRate.numerator, frameRate.denominator)

    private fun frameToMicroseconds(frame: Long, numerator: Int, denominator: Int): Long? =
        TimelineFrameMath.microsecondsFromFrames(frame, Rational(numerator, denominator))
}
