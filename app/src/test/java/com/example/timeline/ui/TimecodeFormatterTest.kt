package com.example.timeline.ui

import com.example.timeline.core.Rational
import org.junit.Assert.assertEquals
import org.junit.Test

class TimecodeFormatterTest {
    @Test
    fun fractionalRateFormattingUsesNominalIntegerRateWithoutFloatMath() {
        assertEquals("00:00:01:00", TimecodeFormatter.formatTimecode(30L, Rational(30_000, 1_001)))
        assertEquals("00:00:00:23", TimecodeFormatter.formatTimecode(23L, Rational(24_000, 1_001)))
    }
}
