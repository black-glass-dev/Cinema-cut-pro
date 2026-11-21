package com.example.timeline.ui

import android.app.Activity
import android.content.pm.ActivityInfo
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.*
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.unit.dp
import com.example.ui.editor.EditorViewModel
import com.example.timeline.core.CoreV2RippleScope
import kotlinx.coroutines.launch
import android.widget.Toast

import com.example.timeline.engine.preview.TimelinePreviewController

@Composable
fun AdvancedTimelineScreen(
    timelineViewModel: TimelineViewModel,
    editorViewModel: EditorViewModel,
    timelinePreviewController: TimelinePreviewController,
    onRelinkRequest: (String) -> Unit,
    onClose: () -> Unit
) {
    val context = LocalContext.current
    val uiState by timelineViewModel.uiState.collectAsState()
    var showMiniViewport by remember { mutableStateOf(true) }
    
    // Tools State
    val selectionController = remember(timelineViewModel, editorViewModel) { SelectionToolController(timelineViewModel, editorViewModel) }
    val bladeController = remember(timelineViewModel) { BladeToolController(timelineViewModel) }
    val trimController = remember(timelineViewModel, editorViewModel) { TrimToolController(timelineViewModel, editorViewModel) }
    val trackController = remember(timelineViewModel) { TrackControlController(timelineViewModel) }
    var rippleAllTracks by remember { mutableStateOf(false) }
    var activeTool by remember { mutableStateOf(TimelineTool.SELECTION) }
    var snapEnabled by remember { mutableStateOf(uiState.snapSettings.isSnappingEnabled) }
    var linkedSelection by remember { mutableStateOf(true) }
    val miniViewportController = remember { MiniViewportController() }
    val density = androidx.compose.ui.platform.LocalDensity.current.density

    val playheadFrameFlow = editorViewModel.playheadFrame
    val playheadFrame by playheadFrameFlow.collectAsState()
    val isPlaying by timelineViewModel.isPlaying.collectAsState()
    val actionScope = rememberCoroutineScope()
    val selectedVideoClip = uiState.selectedClipId?.let { selectedId ->
        uiState.project.tracks.asSequence()
            .filter { it.type == com.example.timeline.core.TrackType.VIDEO && it.isVisible }
            .flatMap { it.clips.asSequence() }
            .firstOrNull { it.id == selectedId && it.isEnabled }
    }
    val canEditAtPlayhead = selectedVideoClip?.let { clip ->
        playheadFrame > clip.timelineStart && playheadFrame < clip.timelineEnd
    } == true
    val selectedAssetHasVideo = selectedVideoClip?.let { clip ->
        uiState.project.mediaAssets.firstOrNull { it.assetId == clip.mediaId }?.metadata?.hasVideo == true
    } == true

    Surface(
        modifier = Modifier.fillMaxSize(),
        color = Color(0xFF141414)
    ) {
        Column(modifier = Modifier.fillMaxSize()) {
            AdvancedTimelineTopBar(
                projectName = uiState.project.name,
                playheadFrameFlow = playheadFrameFlow,
                onClose = onClose,
                onUndo = { timelineViewModel.undo() },
                onRedo = { timelineViewModel.redo() },
                canUndo = uiState.canUndo,
                canRedo = uiState.canRedo,
                canEditAtPlayhead = canEditAtPlayhead,
                canInsertFreezeFrame = canEditAtPlayhead && selectedAssetHasVideo,
                onTrimLeft = { timelineViewModel.trimSelectedClipToPlayhead(start = true) },
                onTrimRight = { timelineViewModel.trimSelectedClipToPlayhead(start = false) },
                onSplit = { timelineViewModel.splitAtPlayhead() },
                onSplitAndInsertFreezeFrame = {
                    actionScope.launch {
                        val bitmap = timelinePreviewController.extractCurrentFrameBitmap()
                        if (bitmap == null) {
                            Toast.makeText(context, "Could not capture the frame at the playhead.", Toast.LENGTH_SHORT).show()
                        } else {
                            try {
                                if (!timelineViewModel.splitAndInsertFreezeFrame(bitmap)) {
                                    Toast.makeText(context, "Move the playhead inside the selected video clip and try again.", Toast.LENGTH_SHORT).show()
                                }
                            } finally {
                                bitmap.recycle()
                            }
                        }
                    }
                },
                snapEnabled = snapEnabled,
                onToggleSnap = { 
                    snapEnabled = !snapEnabled
                    timelineViewModel.updateSnapSettings(uiState.snapSettings.copy(isSnappingEnabled = snapEnabled))
                },
                linkedSelection = linkedSelection,
                onToggleLinked = { linkedSelection = !linkedSelection },
                rippleAllTracks = rippleAllTracks,
                onToggleRippleAllTracks = { rippleAllTracks = !rippleAllTracks },
                showMiniViewport = showMiniViewport,
                onToggleMiniViewport = { showMiniViewport = !showMiniViewport }
            )
            
            Row(modifier = Modifier.weight(1f)) {
                AdvancedTimelineToolPalette(
                    activeTool = activeTool,
                    onToolSelect = { activeTool = it }
                )

                Box(modifier = Modifier.weight(1f)) {
                    AdvancedTimelineCanvas(
                        uiState = uiState,
                        playheadFrameFlow = playheadFrameFlow,
                        isPlaying = isPlaying,
                        activeTool = activeTool,
                        snapEnabled = snapEnabled,
                        linkedSelection = linkedSelection,
                        onClipSelect = { id -> selectionController.handleTap(id) },
                        onNativeEditCommit = { commands, label ->
                            timelineViewModel.commitTimelineEdit(commands, label)
                        },
                        onZoomChange = { zoom -> timelineViewModel.setZoom(zoom) },
                        rippleScope = if (rippleAllTracks) CoreV2RippleScope.ALL_TRACKS else CoreV2RippleScope.SAME_TRACK,
                        onTrackLock = { id, locked -> trackController.toggleLock(id, locked) },
                        onTrackEnable = { id, enabled -> trackController.toggleVisibility(id, enabled) },
                        onTrackMute = { id, muted -> trackController.toggleMute(id, muted) },
                        onTrackSolo = { id, solo -> trackController.toggleSolo(id, solo) },
                        onRenameTrack = { id, name -> timelineViewModel.renameTrack(id, name) },
                        onDeleteTrack = { id -> timelineViewModel.deleteTrack(id) },
                        onMoveTrack = { id, index -> timelineViewModel.moveTrack(id, index) },
                        onPlayheadMoved = { frame -> editorViewModel.updatePlayheadFrame(frame); timelineViewModel.setPlayheadFrame(frame) },
                        onBladeCut = { id, frame -> bladeController.handleTap(id, frame, linkedSelection) },
                        onSetGroupLocked = { clipId, locked -> timelineViewModel.setGroupLocked(clipId, locked) },
                        onTrimStartToPlayhead = { trimController.trimSelectedStartToPlayhead() },
                        onTrimEndToPlayhead = { trimController.trimSelectedEndToPlayhead() }
                    )

                    if (showMiniViewport) {
                        Box(
                            modifier = Modifier
                                .align(androidx.compose.ui.Alignment.TopEnd)
                                .padding(16.dp)
                                .size(width = miniViewportController.width, height = miniViewportController.height)
                        ) {
                            AdvancedTimelineMiniViewport(
                                timelineUiState = uiState,
                                timelineViewModel = timelineViewModel,
                                editorViewModel = editorViewModel,
                                timelinePreviewController = timelinePreviewController,
                                playheadFrameFlow = playheadFrameFlow,
                                isPlaying = isPlaying,
                                onRelinkRequest = onRelinkRequest,
                                onClose = { showMiniViewport = false }
                            )
                            ViewportResizeHandle(
                                modifier = Modifier.align(androidx.compose.ui.Alignment.BottomStart),
                                onDrag = { dx, dy ->
                                    miniViewportController.resize(dx, dy, density)
                                }
                            )
                        }
                    }
                }

                AdvancedTimelineInspector(
                    activeTool = activeTool,
                    selectedClipId = uiState.selectedClipId,
                    project = uiState.project
                )
            }
        }
    }
}
