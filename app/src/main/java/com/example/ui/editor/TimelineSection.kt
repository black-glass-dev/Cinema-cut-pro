package com.example.ui.editor

import androidx.compose.foundation.layout.*
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import com.example.timeline.ui.TimelineViewModel
import com.example.timeline.engine.preview.TimelinePreviewController

@Composable
fun TimelineSection(
    timelineViewModel: TimelineViewModel,
    editorViewModel: EditorViewModel,
    timelinePreviewController: TimelinePreviewController
) {
    Column(modifier = Modifier.fillMaxSize()) {
        com.example.timeline.ui.TimelineScreen(
            viewModel = timelineViewModel,
            editorViewModel = editorViewModel,
            timelinePreviewController = timelinePreviewController
        )
    }

}
