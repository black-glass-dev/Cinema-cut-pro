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
                    val bitmap = timelinePreviewController.extractCurrentFrameBitmap(clipId, playheadFrame)
                    if (bitmap == null) {
                        Toast.makeText(context, "Could not capture the frame at the playhead.", Toast.LENGTH_SHORT).show()
                    } else {
                        try {
                            if (!viewModel.splitAndInsertFreezeFrame(bitmap)) {
                                Toast.makeText(context, "Move the playhead inside the selected video clip and try again.", Toast.LENGTH_SHORT).show()
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
                onClipReorderDrop = { id, frame -> viewModel.reorderClipAtFrame(id, frame) },
                onSplit = { viewModel.splitAtPlayhead() },
                onDelete = { viewModel.deleteSelectedClip(ripple = false) },
                onSetGroupLocked = { clipId, locked -> viewModel.setGroupLocked(clipId, locked) },
                onRenameTrack = { id, name -> viewModel.renameTrack(id, name) },
                onDeleteTrack = { id -> viewModel.deleteTrack(id) },
                onMoveTrack = { id, index -> viewModel.moveTrack(id, index) }
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
