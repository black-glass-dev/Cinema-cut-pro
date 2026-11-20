package com.example.timeline.ui

import org.junit.Assert.assertEquals
import org.junit.Test

class TimelineUiGeometryTest {
    @Test
    fun convertsFramesAndPixelsUsingPresentationScale() {
        assertEquals(250f, TimelineUiGeometry.frameToX(100L, 2.5f), 0f)
        assertEquals(100L, TimelineUiGeometry.xToFrame(250f, 2.5f))
        assertEquals(3L, TimelineUiGeometry.xToFrame(5f, 2f))
        assertEquals(0L, TimelineUiGeometry.xToFrame(Float.NaN, 2f))
        assertEquals(0f, TimelineUiGeometry.frameToX(100L, Float.NaN), 0f)
    }
}
