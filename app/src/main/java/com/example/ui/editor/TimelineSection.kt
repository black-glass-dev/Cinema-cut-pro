package com.example.ui.editor

import androidx.compose.foundation.layout.*
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import com.example.timeline.ui.TimelineViewModel

@Composable
fun TimelineSection(
    timelineViewModel: TimelineViewModel,
    editorViewModel: EditorViewModel
) {
    Column(modifier = Modifier.fillMaxSize()) {
        com.example.timeline.ui.TimelineScreen(
            viewModel = timelineViewModel,
            editorViewModel = editorViewModel
        )
    }

}
