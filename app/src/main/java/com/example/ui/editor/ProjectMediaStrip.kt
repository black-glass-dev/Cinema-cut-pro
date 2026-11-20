package com.example.ui.editor

import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import com.example.timeline.media.MediaAsset
import com.example.timeline.ui.TimelineViewModel

@Composable
fun ProjectMediaStrip(
    mediaAssets: List<MediaAsset>,
    proxyProgressMap: Map<String, Float>,
    launchers: com.example.timeline.ui.MediaImportLaunchers,
    timelineViewModel: TimelineViewModel,
    onRelinkRequest: (String) -> Unit,
    onDismiss: () -> Unit,
    modifier: Modifier = Modifier
) {
    com.example.timeline.ui.MediaBinPanel(
        mediaAssets = mediaAssets.filter { it.assetType == com.example.timeline.media.MediaAssetType.VIDEO },
        proxyProgressMap = proxyProgressMap,
        onImportVideoClick = {
            launchers.videoLauncher.launch(
                arrayOf("video/*", com.example.timeline.media.MotionCamRawFormat.MIME_TYPE, "application/octet-stream")
            )
        },
        onAssetClick = { asset ->
            timelineViewModel.addClipFromMedia(asset)
            onDismiss()
        },
        onRetryImport = { timelineViewModel.retryMediaAsset(it) },
        onGenerateProxy = { timelineViewModel.generateProxy(it) },
        onCancelProxy = { timelineViewModel.cancelProxy(it) },
        onRelinkRequest = onRelinkRequest,
        modifier = modifier
    )
}
