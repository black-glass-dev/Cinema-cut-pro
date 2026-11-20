package com.example.timeline.ui

import com.example.timeline.core.CoreV2EditKind
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

class TimelinePlayheadCommandsTest {
    @Test
    fun trimUsesTheExplicitCurrentPlayheadFrame() {
        val start = TimelinePlayheadCommands.trimAtPlayhead(
            clipId = "selected-clip",
            playheadFrame = 735L,
            start = true
        )
        val end = TimelinePlayheadCommands.trimAtPlayhead(
            clipId = "selected-clip",
            playheadFrame = 735L,
            start = false
        )

        assertEquals(CoreV2EditKind.TRIM_START, start?.kind)
        assertEquals(CoreV2EditKind.TRIM_END, end?.kind)
        assertEquals(735L, start?.targetFrame)
        assertEquals(735L, end?.targetFrame)
    }

    @Test
    fun invalidPlayheadCommandIsNotCreated() {
        assertNull(TimelinePlayheadCommands.trimAtPlayhead("", 1L, start = true))
        assertNull(TimelinePlayheadCommands.trimAtPlayhead("clip", -1L, start = false))
    }
}
