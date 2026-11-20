package com.example.timeline.ui

import com.example.timeline.core.FrameTimecode
import com.example.timeline.core.Rational

/** Formats rational frame rates using the integer nominal rate, without Float conversion. */
object TimecodeFormatter {
    fun formatTimecode(frame: Long, fpsRational: Rational): String =
        FrameTimecode.frameToTimecode(frame, fpsRational)
}
