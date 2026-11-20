package com.example.timeline.ui

import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.grid.GridCells
import androidx.compose.foundation.lazy.grid.LazyVerticalGrid
import androidx.compose.foundation.lazy.grid.items
import androidx.compose.material.icons.Icons
import androidx.compose.foundation.lazy.LazyRow
import androidx.compose.foundation.lazy.items
import androidx.compose.material.icons.filled.Warning
import androidx.compose.material.icons.filled.Info
import androidx.compose.material3.*
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.window.Dialog
import com.example.timeline.media.MediaAsset
import com.example.timeline.media.PreviewStatus
import com.example.timeline.media.ProxyStatus
import com.example.timeline.media.MediaErrorStatus
import com.example.timeline.media.MediaAvailabilityStatus

@Composable
fun MediaBinPanel(
    mediaAssets: List<MediaAsset>,
    proxyProgressMap: Map<String, Float>,
    onImportVideoClick: () -> Unit,
    onAssetClick: (MediaAsset) -> Unit,
    onAssetOptionClick: ((MediaAsset, String) -> Unit)? = null,
    onRetryImport: (String) -> Unit,
    onGenerateProxy: (String) -> Unit = {},
    onCancelProxy: (String) -> Unit,
    onRelinkRequest: (String) -> Unit,
    modifier: Modifier = Modifier
) {
    Column(
        modifier = modifier
            .fillMaxWidth()
            .background(MaterialTheme.colorScheme.surface)
            .padding(8.dp)
    ) {
        Row(
            modifier = Modifier.fillMaxWidth(),
            horizontalArrangement = Arrangement.SpaceBetween,
            verticalAlignment = Alignment.CenterVertically
        ) {
            Text("Project Media", style = MaterialTheme.typography.titleMedium)
            
            Button(
                onClick = onImportVideoClick,
                modifier = Modifier.height(36.dp),
                contentPadding = PaddingValues(horizontal = 12.dp, vertical = 0.dp)
            ) {
                Text("Import video")
            }
        }
        Spacer(modifier = Modifier.height(8.dp))
        
        if (mediaAssets.isEmpty()) {
            Box(modifier = Modifier.fillMaxWidth().height(80.dp), contentAlignment = Alignment.Center) {
                Text(
                    text = "No media imported. Tap Import to add video files.",
                    style = MaterialTheme.typography.bodyMedium,
                    color = MaterialTheme.colorScheme.onSurfaceVariant
                )
            }
        } else {
            LazyRow(
                modifier = Modifier.fillMaxWidth().height(80.dp),
                horizontalArrangement = Arrangement.spacedBy(8.dp)
            ) {
                items(mediaAssets) { asset ->
                    Box(modifier = Modifier.width(120.dp).fillMaxHeight()) {
                        MediaAssetItem(
                            asset = asset,
                            proxyProgress = proxyProgressMap[asset.assetId],
                            onRetryImport = { onRetryImport(asset.assetId) },
                            onGenerateProxy = { onGenerateProxy(asset.assetId) },
                            onCancelProxy = { onCancelProxy(asset.assetId) },
                            onRelinkRequest = { onRelinkRequest(asset.assetId) },
                            onClick = { onAssetClick(asset) },
                            onAssetOptionClick = onAssetOptionClick
                        )
                    }
                }
            }
        }
    }
}

@Composable
fun MediaAssetItem(
    asset: MediaAsset,
    proxyProgress: Float?,
    onRetryImport: () -> Unit,
    onGenerateProxy: () -> Unit,
    onCancelProxy: () -> Unit,
    onRelinkRequest: () -> Unit,
    onClick: () -> Unit,
    onAssetOptionClick: ((MediaAsset, String) -> Unit)? = null
) {
    var showMenu by androidx.compose.runtime.remember { androidx.compose.runtime.mutableStateOf(false) }
    var showTechReport by androidx.compose.runtime.remember { androidx.compose.runtime.mutableStateOf(false) }
    var showProxyError by androidx.compose.runtime.remember { androidx.compose.runtime.mutableStateOf(false) }

    Card(
        modifier = Modifier
            .fillMaxWidth()
            .aspectRatio(1f)
            .clickable(onClick = onClick),
        colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surfaceVariant)
    ) {
        Box(modifier = Modifier.fillMaxSize()) {
            Column(
                modifier = Modifier
                    .fillMaxSize()
                    .padding(4.dp),
                verticalArrangement = Arrangement.SpaceBetween
            ) {
                Text(
                    text = asset.displayName,
                    style = MaterialTheme.typography.labelMedium,
                    maxLines = 2,
                    overflow = TextOverflow.Ellipsis
                )

                if (asset.mediaErrorStatus != MediaErrorStatus.NONE) {
                     Column(verticalArrangement = Arrangement.spacedBy(4.dp)) {
                         Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(4.dp)) {
                             Icon(Icons.Default.Warning, contentDescription = "Error", tint = Color.Red, modifier = Modifier.size(16.dp))
                             Text(
                                 text = "Error",
                                 color = Color.Red,
                                 style = MaterialTheme.typography.labelSmall
                             )
                         }
                         Text(text = "Retry", style = MaterialTheme.typography.labelSmall, modifier = Modifier.clickable { onRetryImport() }, color = MaterialTheme.colorScheme.primary)
                     }
                } else if (asset.mediaAvailabilityStatus == MediaAvailabilityStatus.MISSING_NEEDS_RELINK) {
                     Column(verticalArrangement = Arrangement.spacedBy(4.dp)) {
                         Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(4.dp)) {
                             Icon(Icons.Default.Warning, contentDescription = "Missing", tint = Color.Red, modifier = Modifier.size(16.dp))
                             Text(
                                 text = "Missing",
                                 color = Color.Red,
                                 style = MaterialTheme.typography.labelSmall
                             )
                         }
                         Text(text = "Relink", style = MaterialTheme.typography.labelSmall, modifier = Modifier.clickable { onRelinkRequest() }, color = MaterialTheme.colorScheme.primary)
                     }
                } else if (asset.proxyStatus == ProxyStatus.GENERATING) {
                     Column(verticalArrangement = Arrangement.spacedBy(4.dp)) {
                         Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(4.dp)) {
                             CircularProgressIndicator(modifier = Modifier.size(12.dp), strokeWidth = 2.dp)
                             Text(
                                 text = "Proxying ${(proxyProgress?.times(100))?.toInt() ?: 0}%",
                                 color = Color.Yellow,
                                 style = MaterialTheme.typography.labelSmall
                             )
                         }
                         Text(text = "Cancel", style = MaterialTheme.typography.labelSmall, modifier = Modifier.clickable { onCancelProxy() }, color = MaterialTheme.colorScheme.error)
                     }
                } else if (asset.proxyStatus == ProxyStatus.FAILED) {
                     Column(verticalArrangement = Arrangement.spacedBy(2.dp)) {
                         Text("Proxy Failed", color = MaterialTheme.colorScheme.error, style = MaterialTheme.typography.labelSmall)
                         Text(
                             text = "Retry Proxy",
                             style = MaterialTheme.typography.labelSmall,
                             modifier = Modifier.clickable { onGenerateProxy() },
                             color = MaterialTheme.colorScheme.primary
                         )
                         asset.errorMessage?.takeIf { it.isNotBlank() }?.let { message ->
                             Text(
                                 text = message,
                                 color = MaterialTheme.colorScheme.onSurfaceVariant,
                                 style = MaterialTheme.typography.labelSmall,
                                 maxLines = 2,
                                 overflow = TextOverflow.Ellipsis,
                                 modifier = Modifier.clickable { showProxyError = true }
                             )
                             Text(
                                 text = "Details",
                                 color = MaterialTheme.colorScheme.primary,
                                 style = MaterialTheme.typography.labelSmall,
                                 modifier = Modifier.clickable { showProxyError = true }
                             )
                         }
                     }
                } else if (asset.proxyStatus == ProxyStatus.READY && !asset.proxyPlaybackFailure.isNullOrBlank()) {
                     Column(verticalArrangement = Arrangement.spacedBy(2.dp)) {
                         Text("Proxy playback failed", color = MaterialTheme.colorScheme.error, style = MaterialTheme.typography.labelSmall)
                         Text(
                             text = "Regenerate Proxy",
                             style = MaterialTheme.typography.labelSmall,
                             modifier = Modifier.clickable { onGenerateProxy() },
                             color = MaterialTheme.colorScheme.primary
                         )
                         asset.proxyPlaybackFailure?.let { message ->
                             Text(
                                 text = message,
                                 color = MaterialTheme.colorScheme.onSurfaceVariant,
                                 style = MaterialTheme.typography.labelSmall,
                                 maxLines = 2,
                                 overflow = TextOverflow.Ellipsis
                             )
                         }
                     }
                } else if (asset.proxyStatus == ProxyStatus.READY) {
                     Text("Proxy Ready", color = Color.Green, style = MaterialTheme.typography.labelSmall)
                } else if (asset.proxyStatus == ProxyStatus.REQUIRED ||
                    asset.previewStatus == PreviewStatus.DIRECT_FAILED_RUNTIME ||
                    asset.previewStatus == PreviewStatus.PROXY_REQUIRED
                ) {
                     Column(verticalArrangement = Arrangement.spacedBy(4.dp)) {
                         Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(4.dp)) {
                             Icon(Icons.Default.Warning, contentDescription = "Proxy Required", tint = Color.Yellow, modifier = Modifier.size(16.dp))
                             Text(
                                 text = "Needs Proxy",
                                 color = Color.Yellow,
                                 style = MaterialTheme.typography.labelSmall
                             )
                         }
                         Text(
                             text = "Generate Proxy",
                             style = MaterialTheme.typography.labelSmall,
                             modifier = Modifier.clickable { onGenerateProxy() },
                             color = MaterialTheme.colorScheme.primary
                         )
                     }
                } else if (asset.proxyStatus == ProxyStatus.RECOMMENDED) {
                     Column(verticalArrangement = Arrangement.spacedBy(2.dp)) {
                         Text("Proxy Rec.", color = Color.Yellow, style = MaterialTheme.typography.labelSmall)
                         Text(
                             text = "Generate Proxy",
                             style = MaterialTheme.typography.labelSmall,
                             modifier = Modifier.clickable { onGenerateProxy() },
                             color = MaterialTheme.colorScheme.primary
                         )
                     }
                } else if (asset.previewStatus == PreviewStatus.UNSUPPORTED) {
                     Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(4.dp)) {
                         Icon(Icons.Default.Warning, contentDescription = "Unsupported", tint = Color.Red, modifier = Modifier.size(16.dp))
                         Text(
                             text = "Unsupported",
                             color = Color.Red,
                             style = MaterialTheme.typography.labelSmall
                         )
                     }
                } else if (asset.previewStatus == PreviewStatus.NOT_TESTED) {
                     Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(4.dp)) {
                         CircularProgressIndicator(modifier = Modifier.size(12.dp), strokeWidth = 2.dp)
                         Text(
                             text = "Analyzing...",
                             color = MaterialTheme.colorScheme.onSurfaceVariant,
                             style = MaterialTheme.typography.labelSmall
                         )
                     }
                }

                Column {
                    val fpsLabel = asset.metadata.exactFrameRate?.let { rate ->
                        "${rate.numerator}/${rate.denominator}fps"
                    } ?: asset.metadata.estimatedFrameRate?.let { rate ->
                        "${rate.numerator}/${rate.denominator}fps (estimated)"
                    } ?: "fps unknown"
                    val w = asset.metadata.width
                    val h = asset.metadata.height
                    val codec = asset.metadata.videoStreams.firstOrNull()?.codecName ?: asset.metadata.audioStreams.firstOrNull()?.codecMime ?: "Unknown"

                    Text(
                        text = "${w}x${h} $fpsLabel",
                        style = MaterialTheme.typography.labelSmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant
                    )
                    Text(
                        text = codec,
                        style = MaterialTheme.typography.labelSmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant
                    )
                }
            }
            
            Box(modifier = Modifier.align(Alignment.TopEnd)) {
                DropdownMenu(expanded = showMenu, onDismissRequest = { showMenu = false }) {
                    DropdownMenuItem(text = { Text("Add to Timeline (Active Track)") }, onClick = { showMenu = false; onAssetOptionClick?.invoke(asset, "active") ?: onClick() })
                    DropdownMenuItem(text = { Text("Add as New Layer") }, onClick = { showMenu = false; onAssetOptionClick?.invoke(asset, "new") })
                    DropdownMenuItem(text = { Text("Add Above Selected Layer") }, onClick = { showMenu = false; onAssetOptionClick?.invoke(asset, "above") })
                    DropdownMenuItem(text = { Text("Add Below Selected Layer") }, onClick = { showMenu = false; onAssetOptionClick?.invoke(asset, "below") })
                }
            }

            if (asset.technicalReport != null) {
                IconButton(
                    onClick = { showTechReport = true },
                    modifier = Modifier.align(Alignment.TopEnd).size(24.dp)
                ) {
                    Icon(Icons.Default.Info, contentDescription = "Technical Report", modifier = Modifier.size(16.dp))
                }
            }
        }
    }

    if (showTechReport && asset.technicalReport != null) {
        AlertDialog(
            onDismissRequest = { showTechReport = false },
            title = { Text("Technical Report") },
            text = {
                val report = asset.technicalReport
                Column {
                    Text("Format: ${report.detectedFormat}", style = MaterialTheme.typography.bodyMedium)
                    Text("Direct Playback: ${if(report.directPlaybackSupport) "Yes" else "No"}", style = MaterialTheme.typography.bodyMedium)
                    Text("Proxy Recommended: ${if(report.proxyRecommendation) "Yes" else "No"}", style = MaterialTheme.typography.bodyMedium)
                    Text("Transcode Recommended: ${if(report.transcodeRecommendation) "Yes" else "No"}", style = MaterialTheme.typography.bodyMedium)
                    Spacer(modifier = Modifier.height(8.dp))
                    Text(report.userFriendlyMessage, style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.primary)
                    if (report.warnings.isNotEmpty()) {
                        Spacer(modifier = Modifier.height(8.dp))
                        Text("Warnings:", style = MaterialTheme.typography.labelMedium)
                        report.warnings.forEach { warning ->
                            Text("- $warning", style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.error)
                        }
                    }
                }
            },
            confirmButton = {
                TextButton(onClick = { showTechReport = false }) {
                    Text("OK")
                }
            }
        )
    }

    if (showProxyError && asset.proxyStatus == ProxyStatus.FAILED) {
        AlertDialog(
            onDismissRequest = { showProxyError = false },
            title = { Text("Proxy Generation Failed") },
            text = {
                Column(Modifier.heightIn(max = 320.dp).verticalScroll(rememberScrollState())) {
                    Text(asset.errorMessage ?: "Proxy generation failed without an error message.")
                }
            },
            confirmButton = {
                TextButton(onClick = { showProxyError = false }) {
                    Text("OK")
                }
            }
        )
    }
}
