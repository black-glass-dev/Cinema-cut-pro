package com.example.timeline.ui

import com.example.timeline.core.TimelineProject
import com.example.timeline.core.TimelineSnapSettings
import com.example.timeline.core.ProjectSettings

private fun createInitialEditorProject(): TimelineProject {
    val projectId = java.util.UUID.randomUUID().toString()
    return TimelineProject(
        id = projectId,
        settings = ProjectSettings(projectId = projectId, projectName = "Untitled")
    )
}

data class TimelineUiState(
    val showProjectSetup: Boolean = false,
    val project: TimelineProject = createInitialEditorProject(),
    val pixelsPerFrame: Float = 2f,
    val selectedClipId: String? = null,
    val selectedClipIds: Set<String> = emptySet(),
    val canUndo: Boolean = false,
    val canRedo: Boolean = false,
    val snapSettings: TimelineSnapSettings = TimelineSnapSettings(),
    val showDebugPanel: Boolean = false
)
