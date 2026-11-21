package com.example.ui.editor

import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import com.example.timeline.engine.preview.TimelinePreviewController
import com.example.timeline.ui.TimelineViewModel
import com.example.ui.ColorMatchViewModel
import com.example.ui.scopes.ScopeViewModel

@Composable
fun EditorBottomPanel(
    selectedTab: String,
    timelineViewModel: TimelineViewModel,
    colorMatchViewModel: ColorMatchViewModel,
    scopeViewModel: ScopeViewModel,
    timelinePreviewController: TimelinePreviewController,
    editorViewModel: EditorViewModel,
    modifier: Modifier = Modifier
) {
    Box(modifier = modifier.fillMaxWidth()) {
        when (selectedTab) {
            "Color" -> com.example.ui.colorgrade.ProfessionalColorGradingScreen(
                timelineViewModel = timelineViewModel,
                colorMatchViewModel = colorMatchViewModel,
                scopeViewModel = scopeViewModel,
                previewController = timelinePreviewController,
                editorViewModel = editorViewModel
            )
            else -> TimelineSection(
                timelineViewModel = timelineViewModel,
                editorViewModel = editorViewModel,
                timelinePreviewController = timelinePreviewController
            )
        }
    }
}
