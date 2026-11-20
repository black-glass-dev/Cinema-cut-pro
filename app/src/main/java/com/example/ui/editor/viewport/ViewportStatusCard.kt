package com.example.ui.editor.viewport

import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import com.example.timeline.engine.preview.PreviewEngineType
import com.example.timeline.media.MediaAsset
import com.example.timeline.media.MediaAvailabilityStatus
import com.example.timeline.media.MediaErrorStatus
import com.example.timeline.media.PreviewStatus
import com.example.timeline.media.ProxyStatus

@Composable
fun ViewportStatusCard(
    currentAsset: MediaAsset?,
    playerError: String?,
    engineType: PreviewEngineType,
    isProxySource: Boolean = false,
    onRequestProxy: (String) -> Unit,
    onRequestRelink: (String) -> Unit,
    onRetryPlayback: () -> Unit,
    modifier: Modifier = Modifier
) {
    if (currentAsset == null && playerError == null) return

    var showDetails by remember(playerError) { mutableStateOf(false) }
    val proxyMessage = when {
        currentAsset == null -> null
        currentAsset.mediaAvailabilityStatus == MediaAvailabilityStatus.MISSING_NEEDS_RELINK -> "Media offline"
        currentAsset.mediaErrorStatus != MediaErrorStatus.NONE -> "Media needs attention"
        currentAsset.proxyStatus == ProxyStatus.GENERATING -> "Proxy generating…"
        currentAsset.proxyStatus == ProxyStatus.READY && currentAsset.proxyPlaybackFailure.isNullOrBlank() ->
            if (isProxySource) "Proxy ready · Previewing proxy" else "Proxy ready · Previewing original"
        currentAsset.proxyStatus == ProxyStatus.READY -> "Proxy playback failed · using original"
        currentAsset.proxyStatus == ProxyStatus.REQUIRED -> "Proxy required for this media"
        currentAsset.proxyStatus == ProxyStatus.FAILED -> "Proxy generation failed"
        currentAsset.previewStatus == PreviewStatus.DIRECT_FAILED_RUNTIME -> "Original playback failed"
        else -> null
    }
    val statusMessage = if (playerError != null) "Video playback failed · tap for details" else proxyMessage
    if (statusMessage != null) {
        Surface(
            modifier = modifier.fillMaxWidth().heightIn(min = 25.dp, max = 34.dp),
            color = MaterialTheme.colorScheme.surfaceVariant.copy(alpha = 0.56f),
            contentColor = if (playerError != null || currentAsset?.mediaAvailabilityStatus == MediaAvailabilityStatus.MISSING_NEEDS_RELINK) MaterialTheme.colorScheme.error else MaterialTheme.colorScheme.primary
        ) {
            Row(
                modifier = Modifier.fillMaxWidth().padding(horizontal = 10.dp, vertical = 2.dp),
                verticalAlignment = Alignment.CenterVertically,
                horizontalArrangement = Arrangement.spacedBy(4.dp)
            ) {
                Text(
                    statusMessage,
                    modifier = Modifier.weight(1f).then(if (playerError != null) Modifier.clickable { showDetails = true } else Modifier),
                    style = MaterialTheme.typography.labelSmall,
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis
                )
                if (playerError != null) {
                    Text("Details", modifier = Modifier.clickable { showDetails = true }.padding(horizontal = 4.dp), style = MaterialTheme.typography.labelSmall, color = MaterialTheme.colorScheme.primary)
                    Text("Retry", modifier = Modifier.clickable(onClick = onRetryPlayback).padding(horizontal = 4.dp), style = MaterialTheme.typography.labelSmall, fontWeight = FontWeight.SemiBold, color = MaterialTheme.colorScheme.primary)
                } else if (currentAsset?.mediaAvailabilityStatus == MediaAvailabilityStatus.MISSING_NEEDS_RELINK) {
                    Text("Relink", modifier = Modifier.clickable { onRequestRelink(currentAsset.assetId) }.padding(horizontal = 4.dp), style = MaterialTheme.typography.labelSmall, color = MaterialTheme.colorScheme.primary)
                } else if (currentAsset != null && currentAsset.proxyStatus != ProxyStatus.GENERATING &&
                    currentAsset.proxyStatus != ProxyStatus.READY &&
                    (currentAsset.proxyStatus == ProxyStatus.REQUIRED || currentAsset.proxyStatus == ProxyStatus.FAILED ||
                        currentAsset.previewStatus == PreviewStatus.DIRECT_FAILED_RUNTIME || currentAsset.previewStatus == PreviewStatus.PROXY_REQUIRED)
                ) {
                    Text(
                        if (currentAsset.proxyStatus == ProxyStatus.FAILED) "Retry proxy" else "Make proxy",
                        modifier = Modifier.clickable { onRequestProxy(currentAsset.assetId) }.padding(horizontal = 4.dp),
                        style = MaterialTheme.typography.labelSmall,
                        color = MaterialTheme.colorScheme.primary
                    )
                }
            }
        }
    }

    if (showDetails && playerError != null) {
        AlertDialog(
            onDismissRequest = { showDetails = false },
            title = { Text("Playback error · ${engineType.displayName}") },
            text = {
                Column(modifier = Modifier.heightIn(max = 260.dp).verticalScroll(rememberScrollState())) {
                    Text(redactPlaybackError(playerError), style = MaterialTheme.typography.bodySmall)
                }
            },
            confirmButton = {
                TextButton(onClick = { showDetails = false }) { Text("Close") }
            },
            dismissButton = {
                TextButton(onClick = { showDetails = false; onRetryPlayback() }) { Text("Retry playback") }
            }
        )
    }
}

private fun redactPlaybackError(message: String): String = message
    .replace(Regex("""(?i)(?:file|content)://[^\s]+"""), "[media URI]")
    .replace(Regex("""/(?:data|storage|sdcard)/[^\s,;)]*""", RegexOption.IGNORE_CASE), "[media path]")
    .take(2400)
