package com.example.timeline.ui

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Test

class TimelineUiStateTest {
    @Test
    fun appStartsInAnUntitledTimelineReadyForVideoImport() {
        val state = TimelineUiState()

        assertFalse(state.showProjectSetup)
        assertEquals("Untitled", state.project.settings.projectName)
        assertEquals(state.project.id, state.project.settings.projectId)
    }
}
