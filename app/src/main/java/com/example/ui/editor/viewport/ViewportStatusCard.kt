package com.example.ui.editor.viewport

import androidx.compose.foundation.layout.*
import androidx.compose.material3.*
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import com.example.timeline.media.MediaAsset
import com.example.timeline.media.MediaAvailabilityStatus
import com.example.timeline.media.MediaErrorStatus
import com.example.timeline.media.PreviewStatus
import com.example.timeline.media.ProxyStatus

@Composable
fun ViewportStatusCard(
    currentAsset: MediaAsset?,
    playerError: String?,
    engineType: com.example.timeline.engine.preview.PreviewEngineType,
    isProxySource: Boolean = false,
    onRequestProxy: (String) -> Unit,
    onRequestRelink: (String) -> Unit,
    onRetryPlayback: () -> Unit,
    modifier: Modifier = Modifier
) {
    if (currentAsset == null && playerError == null) return

    Column(modifier = modifier.fillMaxWidth(), horizontalAlignment = Alignment.CenterHorizontally) {
        if (currentAsset != null) {
            if (currentAsset.proxyStatus == ProxyStatus.GENERATING) {
                Text("Proxy Generating...", color = MaterialTheme.colorScheme.onSurfaceVariant, modifier = Modifier.padding(2.dp), style = MaterialTheme.typography.bodySmall)
            } else if (currentAsset.proxyStatus == ProxyStatus.READY && currentAsset.proxyPlaybackFailure == null) {
                Text(
                    if (isProxySource) "Proxy Ready • Previewing proxy" else "Proxy Ready • Previewing original",
                    color = MaterialTheme.colorScheme.primary,
                    modifier = Modifier.padding(2.dp),
                    style = MaterialTheme.typography.bodySmall
                )
            } else if (currentAsset.proxyStatus == ProxyStatus.READY && !currentAsset.proxyPlaybackFailure.isNullOrBlank()) {
                Text(
                    "Proxy generated, but playback failed; using original",
                    color = MaterialTheme.colorScheme.error,
                    modifier = Modifier.padding(2.dp),
                    style = MaterialTheme.typography.bodySmall
                )
                Text(
                    "Proxy details: ${redactProxyError(currentAsset.proxyPlaybackFailure)}",
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                    modifier = Modifier.padding(horizontal = 2.dp),
                    style = MaterialTheme.typography.labelSmall,
                    maxLines = 2,
                    overflow = TextOverflow.Ellipsis
                )
            } else if (currentAsset.proxyStatus == ProxyStatus.REQUIRED) {
                Text("Proxy Required", color = MaterialTheme.colorScheme.error, modifier = Modifier.padding(2.dp), style = MaterialTheme.typography.bodySmall)
            } else if (currentAsset.proxyStatus == ProxyStatus.FAILED) {
                Text("Proxy Failed", color = MaterialTheme.colorScheme.error, modifier = Modifier.padding(2.dp), style = MaterialTheme.typography.bodySmall)
                currentAsset.errorMessage?.takeIf { it.isNotBlank() }?.let { message ->
                    Text(
                        "Proxy details: ${redactProxyError(message)}",
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                        modifier = Modifier.padding(horizontal = 2.dp),
                        style = MaterialTheme.typography.labelSmall,
                        maxLines = 2,
                        overflow = TextOverflow.Ellipsis
                    )
                }
            }

            if (currentAsset.mediaErrorStatus != MediaErrorStatus.NONE) {
                Text("Media Error", color = MaterialTheme.colorScheme.error, modifier = Modifier.padding(2.dp), style = MaterialTheme.typography.bodySmall)
            } else if (currentAsset.mediaAvailabilityStatus == MediaAvailabilityStatus.MISSING_NEEDS_RELINK) {
                Row(verticalAlignment = Alignment.CenterVertically, modifier = Modifier.padding(2.dp)) {
                    Text("Media Offline", color = MaterialTheme.colorScheme.error, style = MaterialTheme.typography.bodySmall)
                    Spacer(modifier = Modifier.width(8.dp))
                    Button(onClick = { onRequestRelink(currentAsset.assetId) }, modifier = Modifier.height(32.dp), contentPadding = PaddingValues(horizontal = 8.dp, vertical = 0.dp)) { Text("Relink") }
                }
            }

            val hasProxy = currentAsset.proxyStatus == ProxyStatus.READY && currentAsset.proxyPlaybackFailure.isNullOrBlank()
            val canRetryProxy = currentAsset.proxyStatus == ProxyStatus.FAILED ||
                currentAsset.proxyPlaybackFailure != null ||
                currentAsset.proxyStatus == ProxyStatus.REQUIRED ||
                currentAsset.previewStatus == PreviewStatus.DIRECT_FAILED_RUNTIME ||
                currentAsset.previewStatus == PreviewStatus.PROXY_REQUIRED
            if (!hasProxy && currentAsset.proxyStatus != ProxyStatus.GENERATING && canRetryProxy) {
                Button(
                    onClick = { onRequestProxy(currentAsset.assetId) },
                    modifier = Modifier.height(32.dp).padding(top = 2.dp),
                    contentPadding = PaddingValues(horizontal = 8.dp, vertical = 0.dp)
                ) {
                    Text(
                        if (currentAsset.proxyStatus == ProxyStatus.FAILED || currentAsset.proxyPlaybackFailure != null) {
                            "Regenerate Proxy"
                        } else {
                            "Generate Proxy"
                        }
                    )
                }
            }
            if (currentAsset.previewStatus == PreviewStatus.DIRECT_FAILED_RUNTIME && !hasProxy) {
                Text("Direct Playback Failed", color = MaterialTheme.colorScheme.error, modifier = Modifier.padding(2.dp), style = MaterialTheme.typography.bodySmall)
            }
        }
        
        if (playerError != null) {
            val msg = if (isProxySource) "Proxy Playback Error: $playerError" else "${engineType.displayName} Playback Error: $playerError"
            Row(verticalAlignment = Alignment.CenterVertically, modifier = Modifier.padding(2.dp)) {
                Text(msg, color = MaterialTheme.colorScheme.error, style = MaterialTheme.typography.bodySmall)
                Spacer(modifier = Modifier.width(8.dp))
                Button(
                    onClick = onRetryPlayback,
                    modifier = Modifier.height(32.dp),
                    contentPadding = PaddingValues(horizontal = 8.dp, vertical = 0.dp)
                ) { Text("Retry") }
            }
        }
    }
}

private fun redactProxyError(message: String): String {
    return message
        .replace(Regex("""(?i)(?:file|content)://[^\s]+"""), "[media URI]")
        .replace(Regex("""/(?:data|storage|sdcard)/[^\s,;)]*""", RegexOption.IGNORE_CASE), "[media path]")
        .take(240)
}
