package com.example.ui.editor.viewport

import androidx.compose.foundation.background
import androidx.compose.foundation.layout.*
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.collectAsState
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.example.timeline.media.MediaAsset
import com.example.timeline.engine.performance.FrameTimeProfiler
import com.example.timeline.engine.performance.PerformanceMonitor

@Composable
fun DeveloperDebugPanel(
    currentAsset: MediaAsset?,
    playheadFrame: Long,
    engineType: String,
    isProxySource: Boolean = false,
    playerError: String? = null,
    playbackErrorCode: String? = null,
    playbackFailureCategory: String? = null,
    media3Metrics: com.example.timeline.engine.preview.PreviewPlaybackMetrics? = null,
    modifier: Modifier = Modifier
) {
    PerformanceMonitor()
    Column(
        modifier = modifier
            .fillMaxWidth()
            .background(Color.Black.copy(alpha = 0.8f))
            .padding(8.dp)
    ) {
        Text("Developer Debug", color = Color.Yellow, style = MaterialTheme.typography.labelSmall)
        Spacer(modifier = Modifier.height(4.dp))
        Text("Playhead: $playheadFrame", color = Color.White, fontSize = 10.sp)
        Text("Engine: $engineType", color = Color.White, fontSize = 10.sp)
        if (media3Metrics != null) {
            val decoderName = media3Metrics.videoDecoderName ?: "Waiting for video decoder"
            val decoderInit = media3Metrics.decoderInitializationDurationMs?.let { "${it}ms init" } ?: "init n/a"
            val frameOffset = media3Metrics.averageVideoFrameProcessingOffsetMs?.let { "%.2fms avg".format(it) } ?: "n/a"
            Text("Media3 decoder: $decoderName ($decoderInit)", color = Color.Cyan, fontSize = 10.sp)
            Text(
                "Video drops: ${media3Metrics.droppedVideoFrames} total; last report ${media3Metrics.droppedFrameReportWindowMs}ms",
                color = Color.White,
                fontSize = 10.sp
            )
            Text(
                "Frame processing offset: $frameOffset / ${media3Metrics.processedVideoFrameSamples} samples; late windows: ${media3Metrics.lateFrameProcessingWindows}",
                color = Color.White,
                fontSize = 10.sp
            )
        }
        if (currentAsset != null) {
            val sourceMime = if (isProxySource) currentAsset.proxyInfo?.proxyMimeType else currentAsset.mimeType
            Text(
                "Source: ${if (isProxySource) "PROXY" else "ORIGINAL"} | Proxy status: ${currentAsset.proxyStatus}",
                color = Color.White,
                fontSize = 10.sp
            )
            Text("Source MIME: ${sourceMime ?: currentAsset.mimeType}", color = Color.White, fontSize = 10.sp)
            if (currentAsset.proxyStatus == com.example.timeline.media.ProxyStatus.FAILED) {
                currentAsset.errorMessage?.takeIf { it.isNotBlank() }?.let { message ->
                    Text(
                        "Proxy error: ${redactLocalPaths(message)}",
                        color = Color.Yellow,
                        fontSize = 10.sp,
                        maxLines = 2,
                        overflow = androidx.compose.ui.text.style.TextOverflow.Ellipsis
                    )
                }
            }
            currentAsset.proxyPlaybackFailure?.takeIf { it.isNotBlank() }?.let { message ->
                Text(
                    "Proxy playback warning: ${redactLocalPaths(message)}",
                    color = Color.Yellow,
                    fontSize = 10.sp,
                    maxLines = 2,
                    overflow = androidx.compose.ui.text.style.TextOverflow.Ellipsis
                )
            }
        }
        if (playbackErrorCode != null || playbackFailureCategory != null) {
            Text(
                "Playback failure: ${playbackFailureCategory ?: "unknown"} / ${playbackErrorCode ?: "unknown code"}",
                color = Color.Yellow,
                fontSize = 10.sp
            )
        }
        playerError?.takeIf { it.isNotBlank() }?.let { message ->
            Text(
                "Last error: ${redactLocalPaths(message)}",
                color = Color.Red,
                fontSize = 10.sp,
                maxLines = 3,
                overflow = androidx.compose.ui.text.style.TextOverflow.Ellipsis
            )
        }

        val stats = FrameTimeProfiler.stats.collectAsState().value
        Text("FPS: ${"%.1f".format(stats.uiFps)}", color = Color.White, fontSize = 10.sp)
        Text("Frame Time: ${"%.1f".format(stats.frameTimeMs)}ms (avg) / Memory: ${"%.1f".format(stats.memoryUsageMb)}MB", color = Color.White, fontSize = 10.sp)
        Text("Dropped Frames: ${stats.droppedFrames}", color = Color.White, fontSize = 10.sp)
        val layoutTime = com.example.timeline.ui.TimelineUiMetrics.lastLayoutTimeMs.collectAsState().value
        Text("Timeline UI layout: ${layoutTime}ms (Core v2 available: ${com.example.timeline.core.TimelineCoreV2Adapter.isAvailable()})", color = Color.White, fontSize = 10.sp)
    }
}

private fun redactLocalPaths(message: String): String {
    return message
        .replace(Regex("""(?i)(?:file|content)://[^\s]+"""), "[media URI]")
        .replace(Regex("""/(?:data|storage|sdcard)/[^\s,;)]*""", RegexOption.IGNORE_CASE), "[media path]")
        .take(240)
}
