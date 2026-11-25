package com.example.timeline.ui

import org.junit.Assert.assertEquals
import org.junit.Test

class FreezeFramePngSizingTest {
    @Test
    fun preservesFullHdAndUhdSourceResolution() {
        assertEquals(1920 to 1080, FreezeFramePngSizing.targetSize(1920, 1080))
        assertEquals(3840 to 2160, FreezeFramePngSizing.targetSize(3840, 2160))
    }

    @Test
    fun limitsOversizedSourcesToFourKWithoutChangingAspectRatio() {
        assertEquals(3840 to 2160, FreezeFramePngSizing.targetSize(7680, 4320))
        assertEquals(2160 to 3840, FreezeFramePngSizing.targetSize(4320, 7680))
    }

    @Test
    fun invalidDimensionsAreSafelyBounded() {
        assertEquals(1 to 1, FreezeFramePngSizing.targetSize(0, -5))
    }
}
