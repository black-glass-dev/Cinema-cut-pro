package com.example.timeline.engine.preview

import android.content.Context

/** Keeps the route policy pure and testable without constructing Android players. */
internal fun resolveRequestedPreviewEngine(type: PreviewEngineType): PreviewEngineType =
    if (type == PreviewEngineType.AUTO) PreviewEngineType.MEDIA3_FALLBACK else type

object PreviewEngineFactory {
    fun createEngine(context: Context, type: PreviewEngineType = PreviewEngineType.AUTO): PreviewEngine {
        val targetType = resolveRequestedPreviewEngine(type)

        when (targetType) {
            PreviewEngineType.FFMPEG_NATIVE -> {
                if (isRunningUnderRobolectric(context)) {
                    return Media3FallbackPreviewEngine(context)
                }
                return FfmpegNativePreviewEngine(context)
            }
            PreviewEngineType.VLC_NATIVE -> {
                if (isRunningUnderRobolectric(context)) {
                    if (com.example.BuildConfig.DEBUG) {
                        android.util.Log.d("PreviewEngineFactory", "Using Media3 in Robolectric; LibVLC is not loaded in JVM tests")
                    }
                    return Media3FallbackPreviewEngine(context)
                }
                val libVlcEngine = createLibVlcEngine(context, targetType)
                if (libVlcEngine != null) return libVlcEngine
                if (com.example.BuildConfig.DEBUG) {
                    android.util.Log.d("PreviewEngineFactory", "LibVLC is unavailable; using Media3 ExoPlayer")
                }
                return Media3FallbackPreviewEngine(context)
            }
            PreviewEngineType.MEDIA3_FALLBACK -> {
                return Media3FallbackPreviewEngine(context)
            }
            PreviewEngineType.STILL_FRAME -> {
                return StillFramePreviewEngine(context)
            }
            PreviewEngineType.BROWSER -> {
                return com.example.timeline.engine.preview.browser.BrowserPreviewEngine(context)
            }
            PreviewEngineType.MOTIONCAM_RAW -> {
                return MotionCamRawPreviewEngine(context)
            }
            PreviewEngineType.PROXY_PREVIEW -> {
                // Proxy is a source-selection policy, not a distinct decoder. AUTO/Media3 handles it.
                return Media3FallbackPreviewEngine(context)
            }
            PreviewEngineType.AUTO -> error("AUTO must be resolved to a concrete engine")
        }
    }

    private fun isRunningUnderRobolectric(context: Context): Boolean = runCatching {
        Class.forName("org.robolectric.RobolectricTestRunner", false, context.classLoader)
    }.isSuccess

    private fun createLibVlcEngine(context: Context, type: PreviewEngineType): PreviewEngine? {
        val engine = runCatching {
            Class.forName("org.videolan.libvlc.LibVLC")
            com.example.timeline.engine.preview.native.NativeVlcPreviewEngine(context, type)
        }.onFailure { error ->
            if (com.example.BuildConfig.DEBUG) {
                android.util.Log.e("PreviewEngineFactory", "LibVLC playback fallback initialization failed", error)
            }
        }.getOrNull() ?: return null

        if (engine.currentState.value == PreviewState.ERROR) {
            engine.release()
            return null
        }
        return engine
    }
}
