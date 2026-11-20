package com.example.timeline.ui.viewport

import androidx.compose.foundation.background
import androidx.compose.foundation.gestures.detectTapGestures
import androidx.compose.foundation.layout.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.unit.dp
import com.example.timeline.core.TimelineClip
import com.example.timeline.core.TimelineProject
import com.example.timeline.core.transform.ClipTransform
import com.example.timeline.engine.preview.TimelineFrameResolver
import com.example.timeline.engine.preview.TimelinePreviewController
import com.example.timeline.media.MediaAsset
import com.example.timeline.media.MediaAvailabilityStatus
import com.example.timeline.media.MediaErrorStatus
import com.example.timeline.media.PreviewStatus
import com.example.timeline.media.ProxyStatus
import com.example.timeline.ui.TimelineViewModel
import kotlinx.coroutines.launch

@Composable
fun CleanVideoCanvas(
    controller: TimelinePreviewController,
    project: TimelineProject,
    currentAsset: MediaAsset?,
    currentClip: TimelineClip?,
    playheadFrame: Long,
    playerState: com.example.timeline.engine.preview.PreviewState,
    playerError: String?,
    firstFrameRendered: Boolean = false,
    isSamplingColor: Boolean,
    onColorSampled: (Float, Float, Float) -> Unit,
    timelineViewModel: TimelineViewModel,
    showSafeArea: Boolean,
    showGuideOverlay: Boolean,
    modifier: Modifier = Modifier
) {
    val scope = rememberCoroutineScope()
    val failureFlow = remember(controller.previewEngine) {
        controller.previewEngine.currentPlaybackFailure
            ?: kotlinx.coroutines.flow.MutableStateFlow<com.example.timeline.engine.preview.PreviewPlaybackFailure?>(null)
    }
    val playbackFailure by failureFlow.collectAsState()
    val isVideoEffectFailure = playbackFailure?.isVideoEffectFailure == true
    val effectiveTransform = currentClip?.transform ?: ClipTransform()

    val aspectRatioFloat = project.settings.resolutionWidth.toFloat() / project.settings.resolutionHeight.toFloat()
    val projectBgColor = try {
         Color(android.graphics.Color.parseColor(project.settings.canvasBackgroundColor))
    } catch (e: Exception) {
         Color.Black
    }

    Box(
        modifier = modifier
            .background(Color.Black)
            .pointerInput(isSamplingColor) {
                detectTapGestures { offset ->
                    if (isSamplingColor) {
                        val x = offset.x / size.width.toFloat()
                        val y = offset.y / size.height.toFloat()
                        scope.launch {
                            val bitmap = controller.extractCurrentFrameBitmap()
                            if (bitmap != null) {
                                val px = (x * bitmap.width).toInt().coerceIn(0, bitmap.width - 1)
                                val py = (y * bitmap.height).toInt().coerceIn(0, bitmap.height - 1)
                                val pixel = bitmap.getPixel(px, py)
                                val r = android.graphics.Color.red(pixel) / 255f
                                val g = android.graphics.Color.green(pixel) / 255f
                                val b = android.graphics.Color.blue(pixel) / 255f
                                onColorSampled(r, g, b)
                            }
                        }
                    } else {
                        timelineViewModel.togglePlay()
                    }
                }
            }
    ) {
        ProjectCanvas(
            projectBackgroundColor = projectBgColor,
            aspectRatio = aspectRatioFloat,
            modifier = Modifier.fillMaxSize()
        ) {
            if (currentAsset != null && currentClip != null) {
                val isReady = playerState == com.example.timeline.engine.preview.PreviewState.READY || playerState == com.example.timeline.engine.preview.PreviewState.PLAYING
                val isMcraw = com.example.timeline.media.MotionCamRawFormat.isMcrawAsset(currentAsset)
                val requiresProxy = currentAsset.proxyStatus == ProxyStatus.REQUIRED ||
                    currentAsset.previewStatus == PreviewStatus.PROXY_REQUIRED ||
                    currentAsset.previewStatus == PreviewStatus.UNSUPPORTED ||
                    currentAsset.previewStatus == PreviewStatus.STILL_FRAME_ONLY
                val hasProxy = currentAsset.proxyStatus == ProxyStatus.READY && currentAsset.proxyPlaybackFailure.isNullOrBlank()
                val needsStillFrame = !isMcraw && !isReady && (
                    (requiresProxy && !hasProxy) ||
                        currentAsset.previewStatus == PreviewStatus.DIRECT_FAILED_RUNTIME ||
                        (playerError != null && !isVideoEffectFailure)
                    )
                
                val isDirectFailed = currentAsset.previewStatus == PreviewStatus.DIRECT_FAILED_RUNTIME || (hasProxy && currentAsset.previewStatus == PreviewStatus.NOT_TESTED)
                val stillFrameUri = currentAsset.localOriginalUriString?.takeIf(String::isNotBlank)
                    ?: currentAsset.originalUriString
                val stillFrameTimeUs = TimelineFrameResolver.resolveSourceFrame(playheadFrame, currentClip)
                    ?.let { sourceFrame ->
                        val fps = project.settings.getFpsRational()
                        TimelineFrameResolver.convertFrameToMicroseconds(sourceFrame, fps.numerator, fps.denominator)
                    }
                val showPosterUntilVideoFrame = !isMcraw &&
                    currentAsset.mimeType.startsWith("video/") &&
                    !firstFrameRendered &&
                    playerError == null

                if (currentAsset.mediaAvailabilityStatus == MediaAvailabilityStatus.MISSING_NEEDS_RELINK || currentAsset.mediaErrorStatus != MediaErrorStatus.NONE) {
                    Box(modifier = Modifier.fillMaxSize().background(Color.Black))
                } else if (isDirectFailed && hasProxy) {
                    com.example.timeline.engine.preview.PlayerViewContainer(
                        previewEngine = controller.previewEngine,
                        clipTransform = effectiveTransform,
                        clipAdjustments = currentClip.adjustments,
                        playheadFrame = playheadFrame,
                        onSwitchEngine = { type -> controller.setEngineType(type) },
                        modifier = Modifier.fillMaxSize()
                    )
                } else if (needsStillFrame) {
                    if (stillFrameTimeUs != null) {
                        StillFrameFallback(
                            uriString = stillFrameUri,
                            timeUs = stillFrameTimeUs,
                            project = project,
                            projectFrame = playheadFrame,
                            clipTransform = effectiveTransform,
                            modifier = Modifier.fillMaxSize()
                        )
                    } else {
                        Box(modifier = Modifier.fillMaxSize().background(Color.Black))
                    }
                } else {
                    com.example.timeline.engine.preview.PlayerViewContainer(
                        previewEngine = controller.previewEngine,
                        clipTransform = effectiveTransform,
                        clipAdjustments = currentClip.adjustments,
                        playheadFrame = playheadFrame,
                        onSwitchEngine = { type -> controller.setEngineType(type) },
                        modifier = Modifier.fillMaxSize()
                    )
                }

                // Keep a real decoded poster over the black video surface until the selected
                // decoder emits its first frame. This avoids the indefinite black preview shown
                // in the user's screenshots while the source/proxy is opening.
                if (!needsStillFrame && showPosterUntilVideoFrame && stillFrameTimeUs != null) {
                    StillFrameFallback(
                        uriString = stillFrameUri,
                        timeUs = stillFrameTimeUs,
                        project = project,
                        projectFrame = playheadFrame,
                        clipTransform = effectiveTransform,
                        modifier = Modifier.fillMaxSize()
                    )
                }

                if (isMcraw && playerError != null && currentAsset.mediaErrorStatus == MediaErrorStatus.NONE) {
                    Box(
                        modifier = Modifier.fillMaxSize().background(Color.Black.copy(alpha = 0.82f)),
                        contentAlignment = Alignment.Center
                    ) {
                        androidx.compose.material3.Text(
                            text = "MotionCam RAW preview unavailable\n\n$playerError",
                            color = Color.White,
                            modifier = Modifier.padding(24.dp)
                        )
                    }
                }

                
                MotionPathRenderer(
                    clip = currentClip,
                    modifier = Modifier.fillMaxSize()
                )
            } else {
                Box(modifier = Modifier.fillMaxSize().background(Color.Black))
            }
        }

        if (showSafeArea) {
            SafeAreaOverlay()
        }

        if (showGuideOverlay) {
            CanvasGuideOverlay()
        }
    }
}
