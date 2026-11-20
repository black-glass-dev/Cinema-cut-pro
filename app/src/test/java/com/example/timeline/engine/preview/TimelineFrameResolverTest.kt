package com.example.timeline.engine.preview

import com.example.timeline.core.Rational
import com.example.timeline.core.TimelineClip
import com.example.timeline.core.TimelineFrameMath
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

class TimelineFrameResolverTest {
    @Test
    fun frameAndMicrosecondConversionsAreExactAtFractionalRates() {
        assertEquals(1_001_000L, TimelineFrameResolver.frameToMicroseconds(24L, Rational(24_000, 1_001)))
        assertEquals(24L, TimelineFrameResolver.microsecondsToFrames(1_001_000L, Rational(24_000, 1_001)))
        assertNull(TimelineFrameMath.framesFromMicroseconds(-1L, Rational(24, 1)))
    }

    @Test
    fun sourceFrameMappingRejectsOverflowInsteadOfWrapping() {
        val clip = TimelineClip(
            id = "clip",
            trackId = "track",
            mediaId = "asset",
            name = "clip",
            sourceIn = Long.MAX_VALUE,
            sourceOut = Long.MAX_VALUE,
            timelineStart = 0L
        )

        assertNull(TimelineFrameResolver.resolveSourceFrame(Long.MAX_VALUE, clip))
    }
}
