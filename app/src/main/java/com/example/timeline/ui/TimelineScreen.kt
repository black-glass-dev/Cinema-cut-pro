package com.example.timeline.ui

import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.layout.*
import androidx.compose.material3.*
import androidx.compose.runtime.Composable
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.setValue
import androidx.compose.runtime.rememberCoroutineScope
import kotlinx.coroutines.launch
import android.widget.Toast
import com.example.timeline.engine.preview.TimelinePreviewController
import androidx.compose.ui.Modifier
import androidx.compose.foundation.background
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.unit.dp
import androidx.lifecycle.ViewModel
import androidx.lifecycle.ViewModelProvider
import androidx.lifecycle.viewmodel.compose.viewModel

@Composable
fun TimelineScreen(
    viewModel: TimelineViewModel,
    editorViewModel: com.example.ui.editor.EditorViewModel,
    timelinePreviewController: TimelinePreviewController
) {
    val uiState by viewModel.uiState.collectAsState()
    val isPlaying by viewModel.isPlaying.collectAsState()
    val playheadFrame by editorViewModel.playheadFrame.collectAsState()
    val selectedVideoClip = uiState.selectedClipId?.let { selectedId ->
        uiState.project.tracks.asSequence()
            .filter { it.type == com.example.timeline.core.TrackType.VIDEO && it.isVisible }
            .flatMap { it.clips.asSequence() }
            .firstOrNull { it.id == selectedId && it.isEnabled }
    }
    val canEditSelectedAtPlayhead = selectedVideoClip?.let { clip ->
        playheadFrame > clip.timelineStart && playheadFrame < clip.timelineEnd
    } == true
    val selectedAssetHasVideo = selectedVideoClip?.let { clip ->
        uiState.project.mediaAssets.firstOrNull { it.assetId == clip.mediaId }?.metadata?.hasVideo == true
    } == true
    val coroutineScope = rememberCoroutineScope()
    val context = LocalContext.current

    var relinkAssetId by androidx.compose.runtime.remember { androidx.compose.runtime.mutableStateOf<String?>(null) }
    val relinkLauncher = rememberLauncherForActivityResult<Array<String>, List<android.net.Uri>>(com.example.timeline.media.MediaPickerContract.OpenMultiple) { uris ->
        relinkAssetId?.let { id ->
            uris.firstOrNull()?.let { uri ->
                viewModel.relinkMedia(id, uri)
            }
        }
        relinkAssetId = null
    }

    if (uiState.showProjectSetup) {
        return // handled in ChromaProApp
    }

    Column(modifier = Modifier.fillMaxSize().background(com.example.ui.theme.CinemaColors.Page)) {
        TimelineToolbar(
            modifier = Modifier.padding(horizontal = 12.dp, vertical = 6.dp),
            canUndo = uiState.canUndo,
            canRedo = uiState.canRedo,
            onUndo = { viewModel.undo() },
            onRedo = { viewModel.redo() },
            onTrimLeft = { viewModel.trimSelectedClipToPlayhead(start = true) },
            onTrimRight = { viewModel.trimSelectedClipToPlayhead(start = false) },
            onSplit = { viewModel.splitAtPlayhead() },
            onSplitAndInsertFreezeFrame = {
                coroutineScope.launch {
                    viewModel.pausePlay()
                    val clipId = uiState.selectedClipId ?: return@launch
                    val selectedTrack = uiState.project.tracks.firstOrNull { track -> track.clips.any { it.id == clipId } }
                    val selectedClip = selectedTrack?.clips?.firstOrNull { it.id == clipId }
                    if (selectedTrack == null || selectedClip == null) return@launch
                    if (selectedTrack.isLocked || selectedClip.isLocked || selectedClip.isGroupLocked) {
                        Toast.makeText(context, "Unlock this track or clip before inserting a freeze frame.", Toast.LENGTH_LONG).show()
                        return@launch
                    }
                    val bitmap = timelinePreviewController.extractCurrentFrameBitmap(clipId, playheadFrame)
                    if (bitmap == null) {
                        Toast.makeText(context, "Could not read this frame from the source or ready proxy. Check the media and playhead position.", Toast.LENGTH_LONG).show()
                    } else {
                        try {
                            if (!viewModel.splitAndInsertFreezeFrame(bitmap)) {
                                Toast.makeText(context, "Freeze frame could not be saved. Check the playhead, track lock, and free storage.", Toast.LENGTH_LONG).show()
                            }
                        } finally {
                            bitmap.recycle()
                        }
                    }
                }
            },
            onDelete = { viewModel.deleteSelectedClip(ripple = false) },
            onZoomIn = { viewModel.zoomIn() },
            onZoomOut = { viewModel.zoomOut() },
            hasSelection = uiState.selectedClipId != null,
            canEditAtPlayhead = canEditSelectedAtPlayhead,
            canInsertFreezeFrame = canEditSelectedAtPlayhead && selectedAssetHasVideo
        )

        Box(modifier = Modifier.weight(1f).fillMaxWidth().padding(horizontal = 12.dp, vertical = 2.dp)) {
            CineTimelineCanvas(
                uiState = uiState,
                playheadFrameFlow = editorViewModel.playheadFrame,
                isPlaying = isPlaying,
                modifier = Modifier.fillMaxSize(),
                onClipSelected = { viewModel.selectClip(it) },
                onPlayheadMoved = { 
                    viewModel.pausePlay()
                    viewModel.setPlayheadFrame(it)
                    editorViewModel.updatePlayheadFrame(it) 
                },
                onNativeEditCommit = { commands, label -> viewModel.commitTimelineEdit(commands, label) },
                onZoomChange = { viewModel.setZoom(it) },
                onClipMoveDrop = { id, frame ->
                    val sourceTrack = uiState.project.tracks.firstOrNull { track -> track.clips.any { it.id == id } }
                    val sourceClip = sourceTrack?.clips?.firstOrNull { it.id == id }
                    when {
                        sourceTrack?.isLocked == true -> Toast.makeText(context, "Unlock this track using its lock icon before moving the clip.", Toast.LENGTH_LONG).show()
                        sourceClip?.isLocked == true || sourceClip?.isGroupLocked == true -> Toast.makeText(context, "Unlock this clip before moving it.", Toast.LENGTH_LONG).show()
                        else -> {
                            val targetFrame = if (uiState.snapSettings.isSnappingEnabled) {
                                TimelineGestureController.snapFrame(
                                    frame, uiState.project, playheadFrame, uiState.pixelsPerFrame, id,
                                    uiState.snapSettings.copy(snapThresholdPixels = minOf(uiState.snapSettings.snapThresholdPixels, 2f))
                                )
                            } else frame
                            if (targetFrame == null) {
                                Toast.makeText(context, "Could not calculate the clip's drop position. Try turning Snap off.", Toast.LENGTH_LONG).show()
                            } else {
                                viewModel.selectClip(id)
                                viewModel.moveClipToFrame(id, targetFrame)
                            }
                        }
                    }
                },
                onSplit = { viewModel.splitAtPlayhead() },
                onDelete = { viewModel.deleteSelectedClip(ripple = false) },
                onSetGroupLocked = { clipId, locked -> viewModel.setGroupLocked(clipId, locked) },
                onRenameTrack = { id, name -> viewModel.renameTrack(id, name) },
                onDeleteTrack = { id -> viewModel.deleteTrack(id) },
                onMoveTrack = { id, index -> viewModel.moveTrack(id, index) },
                onTrackLockChange = { id, locked -> viewModel.lockTrack(id, locked) }
            )

            // Developer debug overlay hidden by default
            /*
            Box(
                modifier = Modifier
                    .align(androidx.compose.ui.Alignment.BottomStart)
                    .padding(8.dp)
                    .background(Color.Black.copy(alpha = 0.7f))
                    .padding(8.dp)
            ) {
                val tracksCount = uiState.project.tracks.size
                val clipsCount = uiState.project.tracks.sumOf { it.clips.size }
                val mediaCount = uiState.project.mediaAssets.size
                
                val playheadFrame by editorViewModel.playheadFrame.collectAsState()
                Text(
                    text = "DEBUG | Tracks: $tracksCount | Clips: $clipsCount | Media: $mediaCount | Playhead: $playheadFrame",
                    color = Color.White,
                    style = MaterialTheme.typography.labelSmall
                )
            }
            */
        }
    }
}
