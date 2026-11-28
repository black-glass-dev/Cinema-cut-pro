package com.example.timeline.ui

import org.junit.Assert.assertEquals
import org.junit.Test

class TimelineSelectionStateTest {
    @Test
    fun multiSelectToggleAddsAndRemovesWithoutDroppingOtherClips() {
        val initial = TimelineSelectionState(setOf("a"))

        val withB = initial.toggleClip("b")
        assertEquals(setOf("a", "b"), withB.selectedClipIds)
        assertEquals(setOf("b"), withB.toggleClip("a").selectedClipIds)
        assertEquals(emptySet<String>(), withB.toggleClip("a").toggleClip("b").selectedClipIds)
        assertEquals(emptySet<String>(), withB.clearSelection().selectedClipIds)
        assertEquals(setOf("a"), initial.toggleClip("a").selectedClipIds)
    }
}
