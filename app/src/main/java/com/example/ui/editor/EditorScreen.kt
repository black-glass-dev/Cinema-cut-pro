package com.example.ui.editor

import androidx.compose.foundation.background
import androidx.compose.foundation.gestures.Orientation
import androidx.compose.foundation.gestures.draggable
import androidx.compose.foundation.gestures.rememberDraggableState
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.MaterialTheme
import androidx.compose.runtime.Composable
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableFloatStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp
import com.example.timeline.engine.preview.TimelinePreviewController
import com.example.timeline.ui.TimelineViewModel
import com.example.ui.ColorAdjustmentViewModel
import com.example.ui.ColorMatchViewModel
import com.example.ui.scopes.ScopeViewModel

@Composable
fun EditorScreen(
    timelineViewModel: TimelineViewModel,
    colorAdjustmentViewModel: ColorAdjustmentViewModel,
    colorMatchViewModel: ColorMatchViewModel,
    scopeViewModel: ScopeViewModel,
    editorViewModel: EditorViewModel,
    timelinePreviewController: TimelinePreviewController,
    launchers: com.example.timeline.ui.MediaImportLaunchers,
    onRelinkRequest: (String) -> Unit
) {
    val timelineUiState by timelineViewModel.uiState.collectAsState()
    val isPlaying by timelineViewModel.isPlaying.collectAsState()
    val isSampling by colorAdjustmentViewModel.isEyedropperActive.collectAsState()
    var selectedTab by remember { mutableStateOf("Timeline") }
    var viewportWeight by remember { mutableFloatStateOf(0.4f) }

    Column(modifier = Modifier.fillMaxSize()) {
        PlaybackAwareVideoViewport(
            timelineUiState = timelineUiState,
            timelineViewModel = timelineViewModel,
            editorViewModel = editorViewModel,
            timelinePreviewController = timelinePreviewController,
            isPlaying = isPlaying,
            isSampling = isSampling,
            onColorSampled = { red, green, blue ->
                colorAdjustmentViewModel.applyEyedropperColor(red, green, blue)
            },
            onRelinkRequest = onRelinkRequest,
            modifier = Modifier.weight(viewportWeight)
        )

        Box(
            modifier = Modifier
                .fillMaxWidth()
                .height(8.dp)
                .background(MaterialTheme.colorScheme.surfaceVariant)
                .draggable(
                    orientation = Orientation.Vertical,
                    state = rememberDraggableState { delta ->
                        viewportWeight = (viewportWeight + delta * 0.001f).coerceIn(0.2f, 0.8f)
                    }
                ),
            contentAlignment = Alignment.Center
        ) {
            Box(
                modifier = Modifier
                    .width(40.dp)
                    .height(4.dp)
                    .background(MaterialTheme.colorScheme.onSurfaceVariant, RoundedCornerShape(2.dp))
            )
        }

        EditorToolTabs(
            selectedTab = selectedTab,
            onTabSelected = { selectedTab = it }
        )

        EditorBottomPanel(
            selectedTab = selectedTab,
            timelineViewModel = timelineViewModel,
            colorMatchViewModel = colorMatchViewModel,
            scopeViewModel = scopeViewModel,
            timelinePreviewController = timelinePreviewController,
            editorViewModel = editorViewModel,
            launchers = launchers,
            onRelinkRequest = onRelinkRequest,
            modifier = Modifier
                .fillMaxWidth()
                .weight(1f - viewportWeight)
        )
    }
}

/** Collects the playhead only within the video viewport subtree. */
@Composable
private fun PlaybackAwareVideoViewport(
    timelineUiState: com.example.timeline.ui.TimelineUiState,
    timelineViewModel: TimelineViewModel,
    editorViewModel: EditorViewModel,
    timelinePreviewController: TimelinePreviewController,
    isPlaying: Boolean,
    isSampling: Boolean,
    onColorSampled: (Float, Float, Float) -> Unit,
    onRelinkRequest: (String) -> Unit,
    modifier: Modifier = Modifier
) {
    val playheadFrame by editorViewModel.playheadFrame.collectAsState()
    VideoViewportSection(
        timelineUiState = timelineUiState,
        timelineViewModel = timelineViewModel,
        editorViewModel = editorViewModel,
        timelinePreviewController = timelinePreviewController,
        playheadFrame = playheadFrame,
        isPlaying = isPlaying,
        isSampling = isSampling,
        onColorSampled = onColorSampled,
        onRelinkRequest = onRelinkRequest,
        modifier = modifier
    )
}
