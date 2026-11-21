package com.example.timeline.ui

import com.example.timeline.core.TimelineClip
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

class TimelineClipLabelTest {
    private fun clip(name: String) = TimelineClip(
        id = "clip",
        trackId = "video",
        mediaId = "asset",
        name = name,
        sourceIn = 0L,
        sourceOut = 100L,
        timelineStart = 0L
    )

    @Test
    fun fallbackVideoNumberAndNarrowLabelsUseShortClipLabel() {
        assertEquals("Clip", timelineClipLabel(clip("Video 1.mp4"), null, 100f))
        assertEquals("Clip", timelineClipLabel(clip("A much longer source filename.mp4"), null, 120f))
        assertNull(timelineClipLabel(clip("Scene.mp4"), null, 35f))
    }

    @Test
    fun fullSourceLabelIsShownWhenItFitsWithoutTruncation() {
        assertEquals("Scene 01", timelineClipLabel(clip("Scene 01.mp4"), null, 160f))
    }
}
