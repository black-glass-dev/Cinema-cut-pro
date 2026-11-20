package com.example.ui.export

import android.content.Intent
import android.os.Environment
import android.widget.Toast
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Close
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Button
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.TopAppBar
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.lifecycle.viewmodel.compose.viewModel
import com.example.timeline.core.TimelineProject
import com.example.timeline.export.BatchExportManager
import com.example.timeline.export.model.normalizeExportFileBaseName
import com.example.timeline.export.service.ExportState
import java.io.File
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale
import java.util.UUID

private fun createStagingExportFile(context: android.content.Context, outputName: String): File {
    val moviesDirectory = context.getExternalFilesDir(Environment.DIRECTORY_MOVIES)
        ?: File(context.filesDir, Environment.DIRECTORY_MOVIES)
    val stagingDirectory = File(moviesDirectory, ".cinema_cut_pro_staging")
    if (!stagingDirectory.exists() && !stagingDirectory.mkdirs()) {
        throw IllegalStateException("Unable to create the temporary export directory.")
    }
    val timestamp = SimpleDateFormat("yyyyMMdd_HHmmss_SSS", Locale.US).format(Date())
    val suffix = UUID.randomUUID().toString().take(8)
    val safeBaseName = normalizeExportFileBaseName(outputName)
    return File(stagingDirectory, "${safeBaseName}_${timestamp}_${suffix}.mp4")
}

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun ExportScreen(
    project: TimelineProject,
    onDismiss: () -> Unit,
    viewModel: ExportSettingsViewModel = viewModel()
) {
    val context = LocalContext.current
    val compactHeight = androidx.compose.ui.platform.LocalConfiguration.current.screenHeightDp < 480
    val uiState by viewModel.uiState.collectAsState()
    val exportState by BatchExportManager.exportState.collectAsState()
    var showValidationDetails by remember { mutableStateOf(false) }

    val outputFolderPicker = rememberLauncherForActivityResult(
        contract = ActivityResultContracts.OpenDocumentTree()
    ) { uri ->
        if (uri != null) {
            val accessFlags = Intent.FLAG_GRANT_READ_URI_PERMISSION or Intent.FLAG_GRANT_WRITE_URI_PERMISSION
            val persisted = runCatching {
                context.contentResolver.takePersistableUriPermission(uri, accessFlags)
            }.isSuccess || runCatching {
                context.contentResolver.takePersistableUriPermission(
                    uri,
                    Intent.FLAG_GRANT_WRITE_URI_PERMISSION
                )
            }.isSuccess
            if (persisted) {
                viewModel.updateSettings { it.copy(outputDirectoryUri = uri.toString()) }
            } else {
                Toast.makeText(
                    context,
                    "Could not keep permission for that folder. Choose another folder.",
                    Toast.LENGTH_LONG
                ).show()
            }
        }
    }

    androidx.compose.ui.window.Dialog(
        onDismissRequest = onDismiss,
        properties = androidx.compose.ui.window.DialogProperties(usePlatformDefaultWidth = false)
    ) {
        Surface(modifier = Modifier.fillMaxSize(), color = MaterialTheme.colorScheme.background) {
            LaunchedEffect(project) { viewModel.syncProject(project) }

            if (exportState is ExportState.Exporting || exportState is ExportState.Completed || exportState is ExportState.Error) {
                ExportProgressScreen(
                    exportState = exportState,
                    onDismiss = onDismiss,
                    onRetry = { BatchExportManager.setIdle() }
                )
            } else {
                val settings = uiState.currentSettings
                if (settings != null) {
                    Scaffold(
                        topBar = {
                            TopAppBar(
                                title = { Text("Export video") },
                                navigationIcon = {
                                    IconButton(onClick = onDismiss) {
                                        Icon(Icons.Default.Close, contentDescription = "Close")
                                    }
                                }
                            )
                        },
                        bottomBar = {
                            Surface(tonalElevation = 8.dp, shadowElevation = 8.dp) {
                                Column(
                                    modifier = Modifier.padding(
                                        if (compactHeight) 8.dp else 16.dp
                                    )
                                ) {
                                    if (!uiState.validationResult.isValid) {
                                        Row(
                                            modifier = Modifier
                                                .fillMaxWidth()
                                                .padding(bottom = 8.dp),
                                            horizontalArrangement = Arrangement.spacedBy(8.dp)
                                        ) {
                                            Text(
                                                "Cannot export: ${uiState.validationResult.errorMessage ?: "Check the project settings."}",
                                                color = MaterialTheme.colorScheme.error,
                                                style = MaterialTheme.typography.bodySmall,
                                                maxLines = 2,
                                                overflow = TextOverflow.Ellipsis,
                                                modifier = Modifier.weight(1f)
                                            )
                                            TextButton(onClick = { showValidationDetails = true }) {
                                                Text("Details")
                                            }
                                        }
                                    } else if (uiState.validationResult.warnings.isNotEmpty()) {
                                        Row(
                                            modifier = Modifier
                                                .fillMaxWidth()
                                                .padding(bottom = 8.dp),
                                            horizontalArrangement = Arrangement.spacedBy(8.dp)
                                        ) {
                                            Text(
                                                "${uiState.validationResult.warnings.size} export note(s)",
                                                color = MaterialTheme.colorScheme.onSurfaceVariant,
                                                style = MaterialTheme.typography.bodySmall,
                                                maxLines = 1,
                                                overflow = TextOverflow.Ellipsis,
                                                modifier = Modifier.weight(1f)
                                            )
                                            TextButton(onClick = { showValidationDetails = true }) {
                                                Text("Details")
                                            }
                                        }
                                    }

                                    if (uiState.validationResult.isValid) {
                                        Text(
                                            "Estimated ${uiState.estimatedSizeMb.toInt()} MB · about ${uiState.estimatedRenderTimeSec}s",
                                            style = MaterialTheme.typography.labelSmall,
                                            modifier = Modifier.padding(bottom = 8.dp)
                                        )
                                    }
                                    Button(
                                        onClick = {
                                            val output = createStagingExportFile(context, settings.outputFileName)
                                            BatchExportManager.startExport(context, project, settings, output)
                                        },
                                        modifier = Modifier
                                            .fillMaxWidth()
                                            .height(if (compactHeight) 48.dp else 56.dp),
                                        enabled = uiState.validationResult.isValid
                                    ) {
                                        Text("Export MP4", fontSize = MaterialTheme.typography.titleMedium.fontSize)
                                    }
                                }
                            }
                        }
                    ) { padding ->
                        Column(
                            modifier = Modifier
                                .fillMaxSize()
                                .padding(padding)
                                .verticalScroll(rememberScrollState())
                                .padding(16.dp),
                            verticalArrangement = Arrangement.spacedBy(20.dp)
                        ) {
                            Surface(
                                color = MaterialTheme.colorScheme.surfaceVariant,
                                shape = MaterialTheme.shapes.medium
                            ) {
                                Column(modifier = Modifier.padding(16.dp)) {
                                    Text("H.264 · MP4 · 8-bit SDR", fontWeight = FontWeight.Bold)
                                    Spacer(Modifier.height(4.dp))
                                    Text(
                                        "Standard video export. Codec and container are fixed to the formats currently supported by this export path.",
                                        style = MaterialTheme.typography.bodySmall,
                                        color = MaterialTheme.colorScheme.onSurfaceVariant
                                    )
                                }
                            }
                            ExportOutputPanel(
                                settings = settings,
                                onSettingsChanged = { updated -> viewModel.updateSettings { updated } },
                                onChooseFolder = { outputFolderPicker.launch(null) },
                                onUseDefaultFolder = {
                                    viewModel.updateSettings { it.copy(outputDirectoryUri = "") }
                                }
                            )
                            androidx.compose.material3.HorizontalDivider()
                            ExportQualityPanel(
                                settings = settings,
                                onSettingsChanged = { updated -> viewModel.updateSettings { updated } }
                            )
                        }
                    }

                    if (showValidationDetails) {
                        AlertDialog(
                            onDismissRequest = { showValidationDetails = false },
                            title = { Text("Export details") },
                            text = {
                                Text(
                                    uiState.validationResult.formatDetails(),
                                    modifier = Modifier
                                        .height(320.dp)
                                        .verticalScroll(rememberScrollState())
                                )
                            },
                            confirmButton = {
                                TextButton(onClick = { showValidationDetails = false }) {
                                    Text("Close")
                                }
                            }
                        )
                    }
                }
            }
        }
    }
}
