package com.example

import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.padding
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Scaffold
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.unit.dp
import androidx.lifecycle.ViewModel
import androidx.lifecycle.ViewModelProvider
import androidx.lifecycle.compose.LocalLifecycleOwner
import androidx.lifecycle.viewmodel.compose.viewModel
import com.example.timeline.engine.preview.TimelinePreviewController
import com.example.timeline.ui.TimelineViewModel
import com.example.ui.ColorAdjustmentViewModel
import com.example.ui.ColorMatchViewModel
import com.example.ui.EditorTopBar
import com.example.ui.editor.EditorScreen
import com.example.ui.editor.EditorViewModel
import com.example.ui.scopes.ScopeViewModel
import com.example.ui.export.ExportScreen

@Composable
fun ChromaProApp() {
    val activityContext = LocalContext.current
    val context = activityContext.applicationContext
    val timelineViewModel: TimelineViewModel = viewModel(
        factory = object : ViewModelProvider.Factory {
            @Suppress("UNCHECKED_CAST")
            override fun <T : ViewModel> create(modelClass: Class<T>): T =
                TimelineViewModel(context) as T
        }
    )
    val timelineUiState by timelineViewModel.uiState.collectAsState()
    val isPlaying by timelineViewModel.isPlaying.collectAsState()

    val colorAdjustmentViewModel: ColorAdjustmentViewModel = viewModel(
        factory = object : ViewModelProvider.Factory {
            @Suppress("UNCHECKED_CAST")
            override fun <T : ViewModel> create(modelClass: Class<T>): T =
                ColorAdjustmentViewModel(timelineViewModel) as T
        }
    )
    val colorMatchViewModel: ColorMatchViewModel = viewModel(
        factory = object : ViewModelProvider.Factory {
            @Suppress("UNCHECKED_CAST")
            override fun <T : ViewModel> create(modelClass: Class<T>): T =
                ColorMatchViewModel(timelineViewModel, context) as T
        }
    )
    val scopeViewModel: ScopeViewModel = viewModel()
    val editorViewModel: EditorViewModel = viewModel()
    val timelinePreviewController = remember(context) { TimelinePreviewController(context) }
    var showExportDialog by remember { mutableStateOf(false) }

    DisposableEffect(timelinePreviewController, editorViewModel, timelineViewModel) {
        timelinePreviewController.onPlayheadAdvanced = { frame ->
            editorViewModel.updatePlayheadFrame(frame)
            timelineViewModel.setPlaybackPlayheadFrame(frame)
        }
        onDispose {
            timelinePreviewController.onPlayheadAdvanced = null
            timelinePreviewController.release()
        }
    }

    LaunchedEffect(isPlaying, editorViewModel, timelineViewModel) {
        if (!isPlaying) timelineViewModel.setPlayheadFrame(editorViewModel.playheadFrame.value)
    }

    val lifecycleOwner = LocalLifecycleOwner.current
    DisposableEffect(lifecycleOwner, timelinePreviewController, timelineViewModel) {
        val observer = androidx.lifecycle.LifecycleEventObserver { _, event ->
            if (event == androidx.lifecycle.Lifecycle.Event.ON_PAUSE) {
                timelinePreviewController.pause()
                timelineViewModel.pausePlay()
            }
        }
        lifecycleOwner.lifecycle.addObserver(observer)
        onDispose { lifecycleOwner.lifecycle.removeObserver(observer) }
    }

    if (showExportDialog) {
        ExportScreen(
            project = timelineUiState.project,
            onDismiss = { showExportDialog = false }
        )
    }

    Scaffold(
        topBar = {
            EditorTopBar(
                settings = timelineUiState.project.settings,
                onExportClick = { showExportDialog = true }
            )
        },
        containerColor = MaterialTheme.colorScheme.background
    ) { padding ->
        Column(
            modifier = Modifier
                .fillMaxSize()
                .padding(padding)
        ) {
            val launchers = com.example.timeline.ui.rememberMediaImportLaunchers(
                onUrisPicked = { uris -> timelineViewModel.handlePickedUris(uris) }
            )
            var relinkAssetId by remember { mutableStateOf<String?>(null) }
            val relinkLauncher = androidx.activity.compose.rememberLauncherForActivityResult(
                contract = com.example.timeline.media.MediaPickerContract.OpenMultiple
            ) { uris ->
                relinkAssetId?.let { assetId ->
                    uris.firstOrNull()?.let { uri -> timelineViewModel.relinkMedia(assetId, uri) }
                }
                relinkAssetId = null
            }

            EditorScreen(
                timelineViewModel = timelineViewModel,
                colorAdjustmentViewModel = colorAdjustmentViewModel,
                colorMatchViewModel = colorMatchViewModel,
                scopeViewModel = scopeViewModel,
                editorViewModel = editorViewModel,
                timelinePreviewController = timelinePreviewController,
                launchers = launchers,
                onRelinkRequest = { assetId ->
                    relinkAssetId = assetId
                    relinkLauncher.launch(arrayOf("*/*"))
                }
            )
        }
    }
}
