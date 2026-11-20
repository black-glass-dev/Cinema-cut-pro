package com.example.timeline.engine.preview

/**
 * Returns whether an unsupported original source should leave Media3 for the bundled FFmpeg
 * decoder. This is a one-way safety fallback for a source error, not a grade/effect failure.
 */
internal fun shouldFallbackToFfmpegForUnsupportedSource(
    engineType: PreviewEngineType,
    failureCategory: PreviewPlaybackFailureCategory?,
    autoFallbackEnabled: Boolean,
    isProxySource: Boolean,
    colorGradingPreviewActive: Boolean,
    fallbackAlreadyAttempted: Boolean
): Boolean =
    engineType == PreviewEngineType.MEDIA3_FALLBACK &&
        failureCategory == PreviewPlaybackFailureCategory.UNSUPPORTED_MEDIA &&
        autoFallbackEnabled &&
        !isProxySource &&
        !colorGradingPreviewActive &&
        !fallbackAlreadyAttempted

/**
 * Returns whether the selected non-RAW preview route needs the Media3 GPU graph for live grading.
 * This decision is based on the user's requested engine, not the engine currently active after a
 * temporary fallback; otherwise the Media3 handoff would flip the decision and immediately undo
 * itself when Compose re-runs its effect.
 */
internal fun requiresMedia3ForLiveGrade(
    engineToRestore: PreviewEngineType,
    isMotionCamRaw: Boolean
): Boolean {
    if (isMotionCamRaw) return false
    return engineToRestore !in setOf(
        PreviewEngineType.AUTO,
        PreviewEngineType.MEDIA3_FALLBACK,
        PreviewEngineType.PROXY_PREVIEW
    )
}
