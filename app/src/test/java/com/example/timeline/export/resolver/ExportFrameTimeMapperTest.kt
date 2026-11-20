package com.example.timeline.export.resolver

import com.example.timeline.core.Rational
import com.example.timeline.export.model.ExportFrameRate
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

class ExportFrameTimeMapperTest {
    @Test
    fun fractionalOutputRateConversionsUseExactIntegerArithmetic() {
        assertEquals(1_001_000_000L, ExportFrameTimeMapper.frameToMicroseconds(24_000L, ExportFrameRate.FPS_23_976))
        assertEquals(24_000L, ExportFrameTimeMapper.timeUsToFrame(1_001_000_000L, ExportFrameRate.FPS_23_976))
        assertEquals(24L, ExportFrameTimeMapper.timeUsToProjectFrame(1_001_000L, Rational(24_000, 1_001)))
    }

    @Test
    fun invalidAndOverflowingConversionsReject() {
        assertNull(ExportFrameTimeMapper.frameToMicroseconds(1L, ExportFrameRate(24, 0)))
        assertNull(ExportFrameTimeMapper.frameToMicroseconds(Long.MAX_VALUE, ExportFrameRate(1, 1)))
        assertNull(ExportFrameTimeMapper.timeUsToFrame(-1L, ExportFrameRate.FPS_24))
    }
}
