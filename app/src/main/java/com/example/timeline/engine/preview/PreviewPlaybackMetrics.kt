package com.example.timeline.engine.preview

import com.example.timeline.media.ProxyStatus

/** Playback diagnostics supplied by Media3's renderer analytics callbacks. */
data class PreviewPlaybackMetrics(
    val videoDecoderName: String? = null,
    val decoderInitializationDurationMs: Long? = null,
    val droppedVideoFrames: Long = 0L,
    /** Dropped frames reported in the most recent Media3 analytics window. */
    val droppedFramesInLastReport: Long = 0L,
    val droppedFrameReportWindowMs: Long = 0L,
    val averageVideoFrameProcessingOffsetMs: Float? = null,
    val processedVideoFrameSamples: Long = 0L,
    val lateFrameProcessingWindows: Long = 0L
)

/**
 * Requests a proxy only after Media3 reports meaningful playback drops in proxy-first mode.
 * A ready proxy is already selected by [PreviewSourceResolver], and a failed proxy is left for
 * the user to retry rather than starting an automatic regeneration loop.
 */
internal fun shouldGenerateAdaptiveProxy(
    previewMode: PreviewMode,
    engineType: PreviewEngineType,
    isPlaying: Boolean,
    isProxySource: Boolean,
    proxyStatus: ProxyStatus,
    proxyPlaybackFailure: String?,
    metrics: PreviewPlaybackMetrics
): Boolean {
    if (previewMode != PreviewMode.SMOOTH_PLAYBACK) return false
    if (engineType != PreviewEngineType.MEDIA3_FALLBACK) return false
    if (!isPlaying || isProxySource || !proxyPlaybackFailure.isNullOrBlank()) return false
    if (proxyStatus == ProxyStatus.READY || proxyStatus == ProxyStatus.GENERATING) return false

    return metrics.droppedFrameReportWindowMs >= MIN_DROPPED_FRAME_WINDOW_MS &&
        metrics.droppedFramesInLastReport >= MIN_DROPPED_FRAMES_PER_WINDOW
}

internal fun shouldSeekPreviewPosition(
    positionDeltaMs: Long,
    isPlaying: Boolean,
    forceSeek: Boolean
): Boolean {
    if (forceSeek) return true
    // While paused, retain frame-level scrub response without issuing seeks for sub-frame noise.
    val thresholdMs = if (isPlaying) 300L else 12L
    return positionDeltaMs > thresholdMs
}

private const val MIN_DROPPED_FRAME_WINDOW_MS = 500L
private const val MIN_DROPPED_FRAMES_PER_WINDOW = 5L
