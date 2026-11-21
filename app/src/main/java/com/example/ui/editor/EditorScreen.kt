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
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.MaterialTheme
import androidx.compose.runtime.Composable
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableFloatStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.layout.onGloballyPositioned
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.semantics.contentDescription
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
    onRelinkRequest: (String) -> Unit,
    modifier: Modifier = Modifier
) {
    val timelineUiState by timelineViewModel.uiState.collectAsState()
    val isPlaying by timelineViewModel.isPlaying.collectAsState()
    val isSampling by colorAdjustmentViewModel.isEyedropperActive.collectAsState()
    var selectedTab by remember { mutableStateOf("Timeline") }
    var viewportWeight by rememberSaveable { mutableFloatStateOf(0.4f) }
    var editorHeightPx by remember { mutableFloatStateOf(1f) }

    Column(modifier = modifier.fillMaxSize().onGloballyPositioned { coordinates ->
        editorHeightPx = coordinates.size.height.toFloat().coerceAtLeast(1f)
    }) {
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
                .height(28.dp)
                .background(MaterialTheme.colorScheme.surfaceVariant)
                .draggable(
                    orientation = Orientation.Vertical,
                    state = rememberDraggableState { delta ->
                        viewportWeight = (viewportWeight + delta / editorHeightPx).coerceIn(0.24f, 0.76f)
                    }
                )
                .semantics { contentDescription = "Drag up or down to resize the preview and timeline" },
            contentAlignment = Alignment.Center
        ) {
            Box(
                modifier = Modifier
                    .width(44.dp)
                    .height(4.dp)
                    .background(MaterialTheme.colorScheme.onSurfaceVariant.copy(alpha = 0.68f), RoundedCornerShape(2.dp))
            )
        }

        EditorBottomPanel(
            selectedTab = selectedTab,
            timelineViewModel = timelineViewModel,
            colorMatchViewModel = colorMatchViewModel,
            scopeViewModel = scopeViewModel,
            timelinePreviewController = timelinePreviewController,
            editorViewModel = editorViewModel,
            modifier = Modifier
                .fillMaxWidth()
                .weight(1f - viewportWeight)
        )

        EditorToolTabs(
            selectedTab = selectedTab,
            onTabSelected = { selectedTab = it }
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
