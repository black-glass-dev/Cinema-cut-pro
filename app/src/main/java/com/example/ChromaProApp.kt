package com.example

import androidx.activity.compose.BackHandler
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.Switch
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
import com.example.timeline.ui.NewProjectScreen
import com.example.timeline.ui.TimelineViewModel
import com.example.ui.ColorAdjustmentViewModel
import com.example.ui.ColorMatchViewModel
import com.example.ui.EditorTopBar
import com.example.ui.editor.EditorScreen
import com.example.ui.editor.EditorViewModel
import com.example.ui.export.ExportScreen
import com.example.ui.home.HomeScreen
import com.example.ui.scopes.ScopeViewModel
import com.example.ui.theme.CinemaColors

private enum class AppRoute { HOME, CREATE_PROJECT, EDITOR }

@Composable
fun ChromaProApp() {
    val appContext = LocalContext.current.applicationContext
    val timelineViewModel: TimelineViewModel = viewModel(
        factory = object : ViewModelProvider.Factory {
            @Suppress("UNCHECKED_CAST")
            override fun <T : ViewModel> create(modelClass: Class<T>): T = TimelineViewModel(appContext) as T
        }
    )
    val timelineUiState by timelineViewModel.uiState.collectAsState()
    val recentProjects by timelineViewModel.recentProjects.collectAsState()
    val isPlaying by timelineViewModel.isPlaying.collectAsState()

    val colorAdjustmentViewModel: ColorAdjustmentViewModel = viewModel(
        factory = object : ViewModelProvider.Factory {
            @Suppress("UNCHECKED_CAST")
            override fun <T : ViewModel> create(modelClass: Class<T>): T = ColorAdjustmentViewModel(timelineViewModel) as T
        }
    )
    val colorMatchViewModel: ColorMatchViewModel = viewModel(
        factory = object : ViewModelProvider.Factory {
            @Suppress("UNCHECKED_CAST")
            override fun <T : ViewModel> create(modelClass: Class<T>): T = ColorMatchViewModel(timelineViewModel, appContext) as T
        }
    )
    val scopeViewModel: ScopeViewModel = viewModel()
    val editorViewModel: EditorViewModel = viewModel()
    val timelinePreviewController = remember(appContext) { TimelinePreviewController(appContext) }
    val playbackPreferences = remember(appContext) {
        appContext.getSharedPreferences("playback_preferences", android.content.Context.MODE_PRIVATE)
    }
    var playThroughGaps by remember(playbackPreferences) {
        mutableStateOf(playbackPreferences.getBoolean("play_through_gaps", true))
    }
    LaunchedEffect(timelinePreviewController, playThroughGaps) {
        timelinePreviewController.setPlayThroughGaps(playThroughGaps)
        playbackPreferences.edit().putBoolean("play_through_gaps", playThroughGaps).apply()
    }
    val importLaunchers = com.example.timeline.ui.rememberMediaImportLaunchers(
        onUrisPicked = { uris -> timelineViewModel.handlePickedUris(uris) }
    )

    // Cold launches always start at Home instead of restoring the editor as the landing screen.
    var route by remember { mutableStateOf(AppRoute.HOME.name) }
    var showExportDialog by remember { mutableStateOf(false) }
    var showSettingsDialog by remember { mutableStateOf(false) }
    var homeError by remember { mutableStateOf<String?>(null) }

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

    LaunchedEffect(route) {
        if (route == AppRoute.HOME.name) timelineViewModel.refreshRecentProjects()
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

    fun returnHome() {
        timelineViewModel.pausePlay()
        timelineViewModel.forceAutosaveProject()
        route = AppRoute.HOME.name
    }

    BackHandler(enabled = showExportDialog || showSettingsDialog || route != AppRoute.HOME.name) {
        when {
            showExportDialog -> showExportDialog = false
            showSettingsDialog -> showSettingsDialog = false
            route == AppRoute.EDITOR.name -> returnHome()
            else -> route = AppRoute.HOME.name
        }
    }

    val projectImportLauncher = rememberLauncherForActivityResult(ActivityResultContracts.OpenDocument()) { uri ->
        if (uri != null) {
            timelineViewModel.importProject(uri) { error ->
                if (error == null) {
                    editorViewModel.updatePlayheadFrame(0L)
                    route = AppRoute.EDITOR.name
                } else {
                    homeError = error
                }
            }
        }
    }

    Scaffold(
        topBar = {
            if (route == AppRoute.EDITOR.name) {
                EditorTopBar(
                    settings = timelineUiState.project.settings,
                    onExportClick = { showExportDialog = true },
                    onImportClick = { importLaunchers.videoLauncher.launch(arrayOf("video/*")) },
                    onBack = ::returnHome
                )
            }
        },
        containerColor = CinemaColors.Page
    ) { padding ->
        when (route) {
            AppRoute.HOME.name -> HomeScreen(
                recentProjects = recentProjects,
                onCreateProject = { route = AppRoute.CREATE_PROJECT.name },
                onImportProject = { projectImportLauncher.launch(arrayOf("application/json", "*/*")) },
                onOpenProject = { projectId ->
                    timelineViewModel.openRecentProject(projectId) { opened ->
                        if (opened) {
                            editorViewModel.updatePlayheadFrame(0L)
                            route = AppRoute.EDITOR.name
                        } else {
                            homeError = "This project could not be opened. Its files may be damaged."
                        }
                    }
                },
                onSettings = { showSettingsDialog = true },
                modifier = Modifier.fillMaxSize().padding(padding)
            )

            AppRoute.CREATE_PROJECT.name -> NewProjectScreen(
                onCreateProject = { settings ->
                    timelineViewModel.createNewProject(settings)
                    editorViewModel.updatePlayheadFrame(0L)
                    route = AppRoute.EDITOR.name
                },
                onCancel = { route = AppRoute.HOME.name },
                modifier = Modifier.fillMaxSize().padding(padding)
            )

            else -> {
                var relinkAssetId by remember { mutableStateOf<String?>(null) }
                val relinkLauncher = rememberLauncherForActivityResult(
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
                    onRelinkRequest = { assetId ->
                        relinkAssetId = assetId
                        relinkLauncher.launch(arrayOf("*/*"))
                    },
                    modifier = Modifier.fillMaxSize().padding(padding)
                )
            }
        }
    }

    if (showExportDialog && route == AppRoute.EDITOR.name) {
        ExportScreen(project = timelineUiState.project, onDismiss = { showExportDialog = false })
    }
    if (showSettingsDialog) {
        AlertDialog(
            onDismissRequest = { showSettingsDialog = false },
            title = { Text("Settings") },
            text = {
                Row(
                    modifier = Modifier.fillMaxWidth(),
                    horizontalArrangement = Arrangement.spacedBy(16.dp),
                    verticalAlignment = androidx.compose.ui.Alignment.CenterVertically
                ) {
                    Column(modifier = Modifier.weight(1f)) {
                        Text("Play through timeline gaps", style = MaterialTheme.typography.titleSmall)
                        Text(
                            "When on, playback shows black in empty timeline spaces and continues to the next clip.",
                            style = MaterialTheme.typography.bodySmall
                        )
                    }
                    Switch(checked = playThroughGaps, onCheckedChange = { playThroughGaps = it })
                }
            },
            confirmButton = { TextButton(onClick = { showSettingsDialog = false }) { Text("Done") } }
        )
    }
    if (homeError != null) {
        AlertDialog(
            onDismissRequest = { homeError = null },
            title = { Text("Could not continue") },
            text = { Text(homeError.orEmpty()) },
            confirmButton = { TextButton(onClick = { homeError = null }) { Text("OK") } }
        )
    }
}
