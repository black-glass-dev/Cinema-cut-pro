package com.example.ui.editor

import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.unit.dp
import com.example.timeline.ui.TimelineUiState
import com.example.timeline.engine.preview.TimelinePreviewController
import com.example.timeline.engine.preview.shouldFallbackToFfmpegForUnsupportedSource
import com.example.timeline.engine.preview.shouldGenerateAdaptiveProxy
import com.example.timeline.ui.TimelineViewModel
import com.example.ui.editor.viewport.*
import com.example.timeline.ui.viewport.CleanVideoCanvas

private data class HandledPlaybackError(
    val assetId: String?,
    val engineType: com.example.timeline.engine.preview.PreviewEngineType,
    val message: String,
    val category: com.example.timeline.engine.preview.PreviewPlaybackFailureCategory?
)

@Composable
fun VideoViewportSection(
    timelineUiState: TimelineUiState,
    timelineViewModel: TimelineViewModel,
    editorViewModel: EditorViewModel,
    timelinePreviewController: TimelinePreviewController,
    playheadFrame: Long,
    isPlaying: Boolean,
    isSampling: Boolean,
    onColorSampled: (Float, Float, Float) -> Unit,
    onRelinkRequest: (String) -> Unit,
    modifier: Modifier = Modifier
) {
    val project = timelineUiState.project
    val settings = project.settings
    
    val engineType by timelinePreviewController.engineTypeFlow.collectAsState()
    val previewSettings by editorViewModel.previewSettings.collectAsState()
    val currentClip by timelinePreviewController.activeClip.collectAsState()
    val currentAsset by timelinePreviewController.activeAsset.collectAsState()
    val currentProjectAsset = currentClip?.mediaId?.let { mediaId ->
        project.mediaAssets.firstOrNull { it.assetId == mediaId }
    } ?: currentAsset
    val activeSourceIsProxy by timelinePreviewController.activeSourceIsProxy.collectAsState()
    val colorGradingPreviewActive by timelinePreviewController.colorGradingPreviewActiveFlow.collectAsState()
    val playerState by timelinePreviewController.previewEngine.currentState.collectAsState()
    val playerError by timelinePreviewController.previewEngine.currentError.collectAsState()
    val firstFrameRendered by timelinePreviewController.previewEngine.firstFrameRendered.collectAsState()
    val playbackFailureFlow = remember(engineType, timelinePreviewController) {
        timelinePreviewController.previewEngine.currentPlaybackFailure
            ?: kotlinx.coroutines.flow.MutableStateFlow<com.example.timeline.engine.preview.PreviewPlaybackFailure?>(null)
    }
    val playbackFailure by playbackFailureFlow.collectAsState()
    val media3MetricsFlow = remember(engineType, timelinePreviewController) {
        (timelinePreviewController.previewEngine as? com.example.timeline.engine.preview.Media3FallbackPreviewEngine)
            ?.playbackMetrics
            ?: kotlinx.coroutines.flow.MutableStateFlow(com.example.timeline.engine.preview.PreviewPlaybackMetrics())
    }
    val media3Metrics by media3MetricsFlow.collectAsState()
    val automaticProxyRequests = remember(project.id) { mutableSetOf<String>() }
    val attemptedFfmpegFallbackAssets = remember(project.id) { mutableSetOf<String>() }
    val attemptedProxyNoFrameFallbacks = remember(project.id) { mutableSetOf<String>() }
    val attemptedDecoderNoFrameFallbacks = remember(project.id) { mutableSetOf<String>() }
    val latestIsPlaying by rememberUpdatedState(isPlaying)

    LaunchedEffect(project) {
        timelinePreviewController.onProjectChanged(project)
    }

    LaunchedEffect(previewSettings.autoGenerateProxy, project.mediaAssets) {
        if (previewSettings.autoGenerateProxy) {
            project.mediaAssets
                .filter { it.proxyStatus == com.example.timeline.media.ProxyStatus.RECOMMENDED }
                .forEach { asset -> timelineViewModel.generateRecommendedProxy(asset.assetId) }
        }
    }

    // Import metadata cannot predict every device decoder's real-time performance. If Media3
    // reports repeated dropped frames while smooth playback is using the original, generate one
    // proxy for this asset; the existing source resolver switches to it as soon as it is READY.
    LaunchedEffect(
        currentProjectAsset?.assetId,
        currentProjectAsset?.proxyStatus,
        currentProjectAsset?.proxyPlaybackFailure,
        previewSettings.previewMode,
        previewSettings.autoGenerateProxy,
        isPlaying,
        activeSourceIsProxy,
        engineType,
        colorGradingPreviewActive,
        media3Metrics.droppedFramesInLastReport,
        media3Metrics.droppedFrameReportWindowMs
    ) {
        val asset = currentProjectAsset ?: return@LaunchedEffect
        if (!previewSettings.autoGenerateProxy || colorGradingPreviewActive) return@LaunchedEffect
        val shouldGenerate = shouldGenerateAdaptiveProxy(
            previewMode = previewSettings.previewMode,
            engineType = engineType,
            isPlaying = isPlaying,
            isProxySource = activeSourceIsProxy,
            proxyStatus = asset.proxyStatus,
            proxyPlaybackFailure = asset.proxyPlaybackFailure,
            metrics = media3Metrics
        )
        val requestKey = "${project.id}:${asset.assetId}"
        if (shouldGenerate && automaticProxyRequests.add(requestKey)) {
            timelineViewModel.generateProxy(asset.assetId)
        }
    }

    LaunchedEffect(previewSettings.previewMode) {
        timelinePreviewController.setPreferProxyPlayback(
            preferProxy = previewSettings.previewMode != com.example.timeline.engine.preview.PreviewMode.COLOR_ACCURATE
        )
    }

    LaunchedEffect(isPlaying, project, timelinePreviewController, editorViewModel) {
        if (!isPlaying) {
            editorViewModel.playheadFrame.collect { frame ->
                // Dragging the visible timeline cursor can emit one frame per pointer event. Let the
                // player coalesce sub-frame movements instead of forcing an ExoPlayer seek each tick.
                timelinePreviewController.seekToTimelineFrame(project, frame, forceSeek = false)
            }
        }
    }

    LaunchedEffect(isPlaying) {
        if (isPlaying) {
            timelinePreviewController.play()
        } else {
            timelinePreviewController.pause()
        }
    }

    DisposableEffect(timelinePreviewController, timelineViewModel) {
        // ChromaProApp owns the shared playhead callback. Keep this viewport-local effect limited
        // to the transport state so it cannot replace or clear the shared callback on disposal.
        timelinePreviewController.onPlaybackEnded = { timelineViewModel.pausePlay() }
        onDispose { timelinePreviewController.onPlaybackEnded = null }
    }

    var handledPlaybackError by remember { mutableStateOf<HandledPlaybackError?>(null) }
    LaunchedEffect(
        currentAsset?.assetId,
        playerError,
        engineType,
        activeSourceIsProxy,
        colorGradingPreviewActive,
        playbackFailure,
        previewSettings.autoFallbackOnFailure
    ) {
        val error = playerError
        val asset = currentAsset
        if (error == null) {
            handledPlaybackError = null
            return@LaunchedEffect
        }
        val errorKey = HandledPlaybackError(asset?.assetId, engineType, error, playbackFailure?.category)
        if (handledPlaybackError == errorKey) return@LaunchedEffect
        handledPlaybackError = errorKey

        val failureIsFromVideoEffects = playbackFailure?.isVideoEffectFailure == true
        val shouldRecordSourceFailure = !colorGradingPreviewActive && !failureIsFromVideoEffects
        val fellBackFromProxy = shouldRecordSourceFailure &&
            asset != null && activeSourceIsProxy &&
            timelinePreviewController.fallbackFromProxy(asset.assetId)
        if (fellBackFromProxy && asset != null) {
            timelineViewModel.markAssetProxyPlaybackFailed(asset.assetId)
        }

        val shouldTryFfmpeg = asset != null && !fellBackFromProxy && shouldFallbackToFfmpegForUnsupportedSource(
            engineType = engineType,
            failureCategory = playbackFailure?.category,
            autoFallbackEnabled = previewSettings.autoFallbackOnFailure,
            isProxySource = activeSourceIsProxy,
            colorGradingPreviewActive = colorGradingPreviewActive,
            fallbackAlreadyAttempted = asset.assetId in attemptedFfmpegFallbackAssets
        )
        val isFallbackEngine = engineType == com.example.timeline.engine.preview.PreviewEngineType.FFMPEG_NATIVE ||
            engineType == com.example.timeline.engine.preview.PreviewEngineType.VLC_NATIVE
        val nativeFallbackWasAlreadyTried = asset?.assetId?.let { assetId ->
            assetId in attemptedFfmpegFallbackAssets || assetId in attemptedDecoderNoFrameFallbacks
        } == true
        when {
            shouldTryFfmpeg && asset != null -> {
                attemptedFfmpegFallbackAssets += asset.assetId
                timelinePreviewController.setEngineType(com.example.timeline.engine.preview.PreviewEngineType.FFMPEG_NATIVE)
                editorViewModel.updateSelectedEngine(com.example.timeline.engine.preview.PreviewEngineType.FFMPEG_NATIVE)
                if (isPlaying) timelinePreviewController.play()
            }
            isFallbackEngine && previewSettings.autoFallbackOnFailure && !nativeFallbackWasAlreadyTried -> {
                timelinePreviewController.setEngineType(com.example.timeline.engine.preview.PreviewEngineType.MEDIA3_FALLBACK)
                editorViewModel.updateSelectedEngine(com.example.timeline.engine.preview.PreviewEngineType.MEDIA3_FALLBACK)
                if (isPlaying) timelinePreviewController.play()
            }
            asset != null && !fellBackFromProxy && shouldRecordSourceFailure -> {
                timelineViewModel.markAssetPlaybackFailed(asset.assetId)
            }
        }
    }

    LaunchedEffect(
        timelinePreviewController,
        engineType,
        currentProjectAsset?.assetId,
        currentProjectAsset?.mimeType,
        activeSourceIsProxy,
        playerState,
        firstFrameRendered,
        previewSettings.autoFallbackOnFailure
    ) {
        val asset = currentProjectAsset ?: return@LaunchedEffect
        if (!previewSettings.autoFallbackOnFailure ||
            !asset.mimeType.startsWith("video/") || firstFrameRendered ||
            playerState == com.example.timeline.engine.preview.PreviewState.ERROR
        ) return@LaunchedEffect

        val assetIdAtStart = asset.assetId
        val engineAtStart = timelinePreviewController.previewEngine
        kotlinx.coroutines.delay(4_000L)
        if (
            timelinePreviewController.activeAsset.value?.assetId != assetIdAtStart ||
            timelinePreviewController.previewEngine !== engineAtStart ||
            timelinePreviewController.previewEngine.firstFrameRendered.value ||
            timelinePreviewController.previewEngine.currentState.value == com.example.timeline.engine.preview.PreviewState.ERROR
        ) return@LaunchedEffect

        if (timelinePreviewController.activeSourceIsProxy.value && attemptedProxyNoFrameFallbacks.add(assetIdAtStart)) {
            if (com.example.BuildConfig.DEBUG) android.util.Log.w("VideoViewportSection", "Proxy produced no first frame; retrying the original source")
            if (timelinePreviewController.fallbackFromProxy(assetIdAtStart)) {
                timelineViewModel.markAssetProxyPlaybackFailed(assetIdAtStart)
            }
        } else if (
            engineType == com.example.timeline.engine.preview.PreviewEngineType.MEDIA3_FALLBACK &&
            attemptedDecoderNoFrameFallbacks.add(assetIdAtStart)
        ) {
            if (com.example.BuildConfig.DEBUG) android.util.Log.w("VideoViewportSection", "Media3 produced no first frame; trying LibVLC")
            timelinePreviewController.setEngineType(com.example.timeline.engine.preview.PreviewEngineType.VLC_NATIVE)
            editorViewModel.updateSelectedEngine(com.example.timeline.engine.preview.PreviewEngineType.VLC_NATIVE)
            if (latestIsPlaying) timelinePreviewController.play()
        }
    }

    LaunchedEffect(
        timelinePreviewController,
        engineType,
        currentAsset?.assetId,
        playerState,
        firstFrameRendered,
        previewSettings.autoFallbackOnFailure
    ) {
        val nativeFallbackEngine = engineType == com.example.timeline.engine.preview.PreviewEngineType.FFMPEG_NATIVE ||
            engineType == com.example.timeline.engine.preview.PreviewEngineType.VLC_NATIVE
        val engineAtStart = timelinePreviewController.previewEngine
        val assetIdAtStart = currentAsset?.assetId
        if (
            previewSettings.autoFallbackOnFailure &&
            nativeFallbackEngine &&
            currentAsset?.mimeType?.startsWith("video/") == true &&
            currentAsset?.assetId?.let { it !in attemptedDecoderNoFrameFallbacks } == true &&
            !firstFrameRendered &&
            playerState != com.example.timeline.engine.preview.PreviewState.ERROR
        ) {
            // Some decoder/device combinations stall before emitting an error. Avoid leaving a
            // black viewport indefinitely: give native decode time, then try Media3 hardware decode.
            kotlinx.coroutines.delay(5_000L)
            if (
                timelinePreviewController.previewEngine === engineAtStart &&
                timelinePreviewController.previewEngine.currentState.value != com.example.timeline.engine.preview.PreviewState.ERROR &&
                !timelinePreviewController.previewEngine.firstFrameRendered.value &&
                timelinePreviewController.activeAsset.value?.assetId == assetIdAtStart
            ) {
                if (com.example.BuildConfig.DEBUG) {
                    android.util.Log.d("VideoViewportSection", "Native preview produced no first frame; switching to Media3")
                }
                timelinePreviewController.setEngineType(com.example.timeline.engine.preview.PreviewEngineType.MEDIA3_FALLBACK)
                editorViewModel.updateSelectedEngine(com.example.timeline.engine.preview.PreviewEngineType.MEDIA3_FALLBACK)
                if (latestIsPlaying) timelinePreviewController.play()
            }
        }
    }

    val aspectRatioFloat = settings.resolutionWidth.toFloat() / settings.resolutionHeight.toFloat()

    Box(modifier = modifier.fillMaxWidth()) {
        Column(modifier = Modifier.fillMaxWidth()) {
            
            // 2. ViewportTitleRow (optional)
            Row(
                modifier = Modifier.fillMaxWidth().padding(horizontal = 12.dp, vertical = 4.dp),
                horizontalArrangement = Arrangement.SpaceBetween,
                verticalAlignment = Alignment.CenterVertically
            ) {
                Text(
                    text = "${settings.resolutionWidth}x${settings.resolutionHeight}  ·  ${settings.getFpsRational()}fps",
                    style = MaterialTheme.typography.labelSmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant
                )
            }

            BoxWithConstraints(
                modifier = Modifier.fillMaxWidth().padding(horizontal = 12.dp),
                contentAlignment = Alignment.Center
            ) {
                val availableWidth = maxWidth
                val screenHeight = androidx.compose.ui.platform.LocalConfiguration.current.screenHeightDp.dp
                // Leave room for the viewport title, transport controls and spacing. In landscape,
                // the parent viewport can be shorter than the old hard-coded 180dp minimum.
                val availableHeight = if (maxHeight != androidx.compose.ui.unit.Dp.Infinity && maxHeight > 0.dp) maxHeight else screenHeight
                val canvasMaxHeight = (availableHeight - 72.dp).coerceAtLeast(64.dp)
                    .coerceAtMost(420.dp)

                val (canvasWidth, canvasHeight) = if (aspectRatioFloat > 1.0f) {
                    val calculatedHeight = availableWidth / aspectRatioFloat
                    val h = minOf(calculatedHeight, canvasMaxHeight, 280.dp)
                    val w = minOf(availableWidth, h * aspectRatioFloat)
                    Pair(w, h)
                } else if (aspectRatioFloat < 1.0f) {
                    val h = minOf(420.dp, availableHeight * 0.38f, canvasMaxHeight)
                    val w = h * aspectRatioFloat
                    Pair(w, h)
                } else {
                    val size = minOf(availableWidth, 320.dp, canvasMaxHeight)
                    Pair(size, size)
                }

                CleanVideoCanvas(
                    controller = timelinePreviewController,
                    project = project,
                    currentAsset = currentAsset,
                    currentClip = currentClip,
                    playheadFrame = playheadFrame,
                    playerState = playerState,
                    playerError = playerError,
                    firstFrameRendered = firstFrameRendered,
                    isSamplingColor = isSampling,
                    onColorSampled = onColorSampled,
                    timelineViewModel = timelineViewModel,
                    showSafeArea = false,
                    showGuideOverlay = false,
                    modifier = Modifier.size(canvasWidth, canvasHeight)
                )
            }

            Spacer(modifier = Modifier.height(8.dp))
            ViewportControlBar(
                isPlaying = isPlaying,
                onTogglePlay = { timelineViewModel.togglePlay() },
                timecodeText = com.example.timeline.core.FrameTimecode.frameToTimecode(
                    playheadFrame,
                    settings.getFpsRational().toFloat().toInt()
                )
            )
            // Resolve status from the current project snapshot by active clip id so the viewport
            // and Project Media do not briefly report different proxy states after an update.
            ViewportStatusCard(
                currentAsset = currentProjectAsset,
                playerError = playerError,
                engineType = engineType,
                isProxySource = activeSourceIsProxy,
                onRequestProxy = { timelineViewModel.generateProxy(it) },
                onRequestRelink = onRelinkRequest,
                onRetryPlayback = {
                    timelinePreviewController.loadProject(project)
                    timelinePreviewController.seekToTimelineFrame(project, playheadFrame)
                    if (isPlaying) timelinePreviewController.play()
                }
            )
        }

    }
}
