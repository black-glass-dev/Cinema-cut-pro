package com.example.timeline.engine.preview

import android.view.Surface
import com.example.model.adjustments.VideoAdjustmentParams
import kotlinx.coroutines.flow.StateFlow

enum class PreviewEngineType(val displayName: String) {
    AUTO("Auto (Media3 first)"),
    FFMPEG_NATIVE("FFmpeg native playback"),
    VLC_NATIVE("LibVLC playback fallback"),
    MEDIA3_FALLBACK("Media3 ExoPlayer"),
    BROWSER("WebView/Browser"),
    STILL_FRAME("Still Frame"),
    PROXY_PREVIEW("Proxy Preview"),
    MOTIONCAM_RAW("MotionCam RAW (native)")
}

enum class PreviewState {
    IDLE,
    LOADING,
    READY,
    PLAYING,
    ERROR
}

/** Source information passed from the timeline to a playback engine. */
interface PreviewSource {
    val uri: String
    val isProxy: Boolean
    val mimeType: String?
        get() = null
}

data class PreviewMediaSource(
    override val uri: String,
    override val mimeType: String? = null,
    override val isProxy: Boolean = false
) : PreviewSource

enum class PreviewPlaybackFailureCategory {
    SOURCE_ACCESS,
    UNSUPPORTED_MEDIA,
    DECODER,
    AUDIO,
    DRM,
    PLAYBACK
}

/** Diagnostic context for a playback failure; URI paths are intentionally omitted. */
data class PreviewPlaybackFailure(
    val engineType: PreviewEngineType,
    val uriScheme: String?,
    val uriAuthority: String?,
    val mimeType: String?,
    val errorCode: Int?,
    val message: String,
    val causeType: String? = null,
    val causeMessage: String? = null,
    val category: PreviewPlaybackFailureCategory = PreviewPlaybackFailureCategory.PLAYBACK,
    val errorCodeName: String? = null
) {
    /** Shader/frame-processor errors are not evidence that the selected media/proxy is invalid. */
    val isVideoEffectFailure: Boolean
        get() = listOfNotNull(causeType, causeMessage, message)
            .any { detail ->
                val normalized = detail.lowercase()
                normalized.contains("videoframeprocessing") ||
                    normalized.contains("shader") ||
                    normalized.contains("gpu color grading") ||
                    normalized.contains("frame processing")
            }

    fun toUserMessage(): String {
        val sourceContext = buildList {
            mimeType?.takeIf(String::isNotBlank)?.let(::add)
            val uriContext = when {
                uriScheme.isNullOrBlank() -> null
                uriAuthority.isNullOrBlank() -> "$uriScheme URI"
                else -> "$uriScheme://$uriAuthority"
            }
            uriContext?.let(::add)
        }.joinToString(" from ")

        val opening = when (category) {
            PreviewPlaybackFailureCategory.SOURCE_ACCESS -> "could not access"
            PreviewPlaybackFailureCategory.UNSUPPORTED_MEDIA -> "does not support"
            else -> "could not play"
        }
        return buildString {
            append(engineType.displayName)
            append(' ')
            append(opening)
            if (sourceContext.isNotBlank()) append(" $sourceContext")
            errorCode?.let { code ->
                append(" (")
                append(errorCodeName ?: "error $code")
                append(')')
            }
            if (message.isNotBlank()) append(": $message")
            val causeDescription = listOfNotNull(
                causeType?.takeIf(String::isNotBlank),
                causeMessage?.takeIf(String::isNotBlank)
            ).joinToString(": ")
            if (causeDescription.isNotBlank() && causeDescription != message) {
                append(" — ")
                append(causeDescription)
            }
        }
    }
}

data class PreviewEngineCapabilities(
    val canPlayMovingVideo: Boolean,
    val canScrub: Boolean,
    val canRenderFirstFrame: Boolean,
    val canApplyRealtimeColorGrade: Boolean,
    val canApplyBasicAdjustments: Boolean,
    val canUseProxy: Boolean,
    val supports10Bit: Boolean = false,
    val supportsHDR: Boolean = false,
    val supports4K: Boolean = true,
    val currentFailureReason: String? = null
)

interface PreviewEngine {
    val engineType: PreviewEngineType
    val capabilities: PreviewEngineCapabilities

    val currentState: StateFlow<PreviewState>
    val currentError: StateFlow<String?>
    /** Non-null for engines that expose structured source/error diagnostics. */
    val currentPlaybackFailure: StateFlow<PreviewPlaybackFailure?>?
        get() = null
    val firstFrameRendered: StateFlow<Boolean>

    fun setSurface(surface: Surface?)

    /** Keeps the Media3 grade graph attached while the color panel is open, including at neutral settings. */
    fun setColorGradePreviewMode(enabled: Boolean) = Unit

    /** Updates live effect parameters without seeking or replacing the current source/frame. */
    fun updatePreviewGrade(evaluatedParams: VideoAdjustmentParams) = Unit

    fun loadMedia(
        uriString: String,
        useProxy: Boolean,
        sourcePositionMs: Long,
        evaluatedParams: VideoAdjustmentParams?,
        presentationTimeUs: Long,
        forceSeek: Boolean = false,
        mimeType: String? = null
    )

    /** UI-neutral source entry point; legacy engines can continue implementing [loadMedia]. */
    fun setSource(
        source: PreviewSource,
        sourcePositionMs: Long = 0L,
        evaluatedParams: VideoAdjustmentParams? = null,
        presentationTimeUs: Long = sourcePositionMs.coerceAtLeast(0L) * 1_000L,
        forceSeek: Boolean = false
    ) {
        loadMedia(
            uriString = source.uri,
            useProxy = source.isProxy,
            sourcePositionMs = sourcePositionMs.coerceAtLeast(0L),
            evaluatedParams = evaluatedParams,
            presentationTimeUs = presentationTimeUs.coerceAtLeast(0L),
            forceSeek = forceSeek,
            mimeType = source.mimeType
        )
    }

    fun play()
    fun pause()
    fun seekTo(timeUs: Long)
    fun setPlaybackSpeed(speed: Float)
    /** Volume is normalized to 0.0–1.0; unsupported engines may retain the no-op default. */
    fun setVolume(volume: Float) = Unit
    fun setResolutionFallback(resolutionMode: String)
    fun release()

    val currentPositionUs: Long
    val durationUs: Long
    val isPlaying: Boolean

    /** Callback keeps the timeline synchronized without coupling the player to Compose. */
    var onTimeChanged: ((Long) -> Unit)?

    /** Called once when the current media item reaches end-of-stream. */
    var onPlaybackEnded: (() -> Unit)?
}
