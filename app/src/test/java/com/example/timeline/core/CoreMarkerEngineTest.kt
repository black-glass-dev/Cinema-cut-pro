package com.example.timeline.core

import org.junit.Assert.assertEquals
import org.junit.Test

class CoreMarkerEngineTest {
    @Test
    fun addProjectMarkerKeepsTheRequestedTimelineFrame() {
        val project = TimelineProject(
            id = "project",
            settings = ProjectSettings(projectId = "project", projectName = "Test")
        )
        val marker = TimelineMarker(frame = 137L, name = "Marker 1")

        val updated = CoreMarkerEngine.addProjectMarker(project, marker)

        assertEquals(listOf(marker), updated.markers)
        assertEquals(137L, updated.markers.single().frame)
        assertEquals(emptyList<TimelineMarker>(), project.markers)
    }
}
