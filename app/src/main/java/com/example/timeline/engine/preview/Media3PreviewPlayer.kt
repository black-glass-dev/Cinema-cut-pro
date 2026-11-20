package com.example.timeline.engine.preview

import android.content.Context
import android.net.Uri
import android.view.Surface
import androidx.media3.common.AudioAttributes
import androidx.media3.common.C
import androidx.media3.common.MediaItem
import androidx.media3.common.PlaybackException
import androidx.media3.common.Player
import androidx.media3.common.VideoFrameProcessor
import androidx.media3.common.util.UnstableApi
import androidx.media3.exoplayer.ExoPlayer
import androidx.media3.exoplayer.analytics.AnalyticsListener
import com.example.model.adjustments.VideoAdjustmentParams
import com.example.model.adjustments.engine.VideoEffectGraph
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.delay
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.update
import java.util.concurrent.atomic.AtomicBoolean

@OptIn(UnstableApi::class)
class Media3FallbackPreviewEngine(context: Context) : PreviewEngine {
    override val engineType = PreviewEngineType.MEDIA3_FALLBACK

    override val capabilities: PreviewEngineCapabilities = PreviewEngineCapabilities(
        canPlayMovingVideo = true,
        canScrub = true,
        canRenderFirstFrame = true,
        canApplyRealtimeColorGrade = true, // Media3 with custom ExoPlayer video effects
        canApplyBasicAdjustments = true,
        canUseProxy = true,
        currentFailureReason = null
    )
    
    private val _currentState = MutableStateFlow(PreviewState.IDLE)
    override val currentState: StateFlow<PreviewState> = _currentState.asStateFlow()
    
    private val _currentError = MutableStateFlow<String?>(null)
    override val currentError: StateFlow<String?> = _currentError.asStateFlow()

    private val _currentPlaybackFailure = MutableStateFlow<PreviewPlaybackFailure?>(null)
    override val currentPlaybackFailure: StateFlow<PreviewPlaybackFailure?> = _currentPlaybackFailure.asStateFlow()

    private val _firstFrameRendered = MutableStateFlow(false)
    override val firstFrameRendered: StateFlow<Boolean> = _firstFrameRendered.asStateFlow()

    private val appContext = context.applicationContext
    val exoPlayer: ExoPlayer = ExoPlayer.Builder(appContext)
        .setHandleAudioBecomingNoisy(true)
        .build()
    private val effectGraph = VideoEffectGraph(appContext)
    private val _playbackMetrics = MutableStateFlow(PreviewPlaybackMetrics())
    val playbackMetrics: StateFlow<PreviewPlaybackMetrics> = _playbackMetrics.asStateFlow()

    // For legacy compat in codebase
    private val _state = MutableStateFlow(PreviewPlayerState())
    val state: StateFlow<PreviewPlayerState> = _state.asStateFlow()

    private var currentUri: String? = null
    private var currentMimeType: String? = null
    private var videoEffectsActive = false
    private var colorGradePreviewMode = false
    private var latestEvaluatedParams: VideoAdjustmentParams = VideoAdjustmentParams.default()
    private var effectFailureFallbackSource: String? = null
    private val released = AtomicBoolean(false)

    override var onTimeChanged: ((Long) -> Unit)? = null
    override var onPlaybackEnded: (() -> Unit)? = null

    private var timeTrackerJob: kotlinx.coroutines.Job? = null
    private var pausedGradeRefreshJob: kotlinx.coroutines.Job? = null
    private var pausedGradeRefreshPending = false
    private var lastPausedGradeRefreshMs = 0L
    private val scope = CoroutineScope(Dispatchers.Main + SupervisorJob())

    init {
        exoPlayer.setAudioAttributes(
            AudioAttributes.Builder()
                .setUsage(C.USAGE_MEDIA)
                .setContentType(C.AUDIO_CONTENT_TYPE_MOVIE)
                .build(),
            /* handleAudioFocus= */ true
        )
        exoPlayer.addListener(object : Player.Listener {
            override fun onMediaItemTransition(mediaItem: MediaItem?, reason: Int) {
                _currentPlaybackFailure.value = null
                _currentError.value = null
            }

            override fun onRenderedFirstFrame() {
                if (com.example.BuildConfig.DEBUG) android.util.Log.d("PLAYER_MEDIA3", "onRenderedFirstFrame called")
                _firstFrameRendered.value = true
            }
            override fun onPlaybackStateChanged(playbackState: Int) {
                val failure = exoPlayer.playerError?.let(::toPlaybackFailure)
                if (failure != null) {
                    _currentPlaybackFailure.value = failure
                    _currentError.value = failure.toUserMessage()
                }
                _state.update { it.copy(
                    isReady = playbackState == Player.STATE_READY,
                    error = failure?.toUserMessage() ?: it.error
                )}
                when (playbackState) {
                    Player.STATE_BUFFERING -> _currentState.value = PreviewState.LOADING
                    Player.STATE_READY -> {
                        if (effectFailureFallbackSource == currentUri &&
                            _currentPlaybackFailure.value?.isVideoEffectFailure == true
                        ) {
                            _currentPlaybackFailure.value = null
                            _currentError.value = null
                            _state.update { it.copy(error = null) }
                        }
                        _currentState.value = if (exoPlayer.isPlaying) PreviewState.PLAYING else PreviewState.READY
                        if (pausedGradeRefreshPending && !exoPlayer.isPlaying) requestPausedGradeRefresh()
                    }
                    Player.STATE_ENDED -> {
                        // Emit the final position once; ExoPlayer stops its 30fps tracker as soon
                        // as isPlaying becomes false, but the timeline may still have another clip.
                        _currentState.value = PreviewState.PLAYING
                        onTimeChanged?.invoke(exoPlayer.currentPosition * 1000L)
                        _currentState.value = PreviewState.READY
                        onPlaybackEnded?.invoke()
                    }
                    Player.STATE_IDLE -> {
                        if (exoPlayer.playerError != null) {
                            _currentState.value = PreviewState.ERROR
                        }
                    }
                }
            }

            override fun onIsPlayingChanged(isPlaying: Boolean) {
                _state.update { it.copy(isPlaying = isPlaying) }
                if (isPlaying) {
                    cancelPausedGradeRefresh()
                    _currentState.value = PreviewState.PLAYING
                    startTimeTracker()
                } else {
                    if (exoPlayer.playbackState == Player.STATE_READY) _currentState.value = PreviewState.READY
                    stopTimeTracker()
                }
            }

            override fun onPlayerError(error: PlaybackException) {
                val failure = toPlaybackFailure(error)
                if (failure.isVideoEffectFailure && retryWithoutVideoEffects(failure)) return
                _currentPlaybackFailure.value = failure
                _currentError.value = failure.toUserMessage()
                _state.update { it.copy(error = failure.toUserMessage(), isReady = false, isPlaying = false) }
                _currentState.value = PreviewState.ERROR
                stopTimeTracker()
            }
        })
        exoPlayer.addAnalyticsListener(object : AnalyticsListener {
            override fun onVideoDecoderInitialized(
                eventTime: AnalyticsListener.EventTime,
                decoderName: String,
                initializedTimestampMs: Long,
                initializationDurationMs: Long
            ) {
                _playbackMetrics.update {
                    it.copy(
                        videoDecoderName = decoderName,
                        decoderInitializationDurationMs = initializationDurationMs
                    )
                }
            }

            override fun onDroppedVideoFrames(
                eventTime: AnalyticsListener.EventTime,
                droppedFrames: Int,
                elapsedMs: Long
            ) {
                _playbackMetrics.update {
                    it.copy(
                        droppedVideoFrames = it.droppedVideoFrames + droppedFrames.coerceAtLeast(0),
                        droppedFramesInLastReport = droppedFrames.coerceAtLeast(0).toLong(),
                        droppedFrameReportWindowMs = elapsedMs.coerceAtLeast(0L)
                    )
                }
            }

            override fun onVideoFrameProcessingOffset(
                eventTime: AnalyticsListener.EventTime,
                totalProcessingOffsetUs: Long,
                frameCount: Int
            ) {
                if (frameCount <= 0) return
                val averageOffsetMs = totalProcessingOffsetUs.toDouble()
                    .div(frameCount.toDouble())
                    .div(1_000.0)
                    .toFloat()
                _playbackMetrics.update {
                    it.copy(
                        averageVideoFrameProcessingOffsetMs = averageOffsetMs,
                        processedVideoFrameSamples = it.processedVideoFrameSamples + frameCount,
                        lateFrameProcessingWindows = it.lateFrameProcessingWindows +
                            if (averageOffsetMs < 0f) 1L else 0L
                    )
                }
            }
        })

        // Leave plain playback on ExoPlayer's normal decoder/render path. Calling
        // setVideoEffects(emptyList()) still installs Media3's effects/composition pipeline in
        // current Media3 releases; seeking/replaying through that pipeline can throw
        // "Replaying when enableReplayableCache is set to false" for imported local videos.
        // Attach the GPU effect graph only when the user actually enables a preview effect.
    }

    private fun updateVideoEffects(uriString: String, mimeType: String?, params: VideoAdjustmentParams?) {
        val currentParams = params ?: VideoAdjustmentParams.default()
        effectGraph.setParams(currentParams)
        val shouldActivate = (colorGradePreviewMode || currentParams.hasActivePreviewEffect()) &&
            effectFailureFallbackSource != uriString
        if (!shouldActivate || videoEffectsActive) return

        try {
            // Install the effect pipeline lazily, never an empty effects list. Media3 treats even
            // setVideoEffects(emptyList()) as an effects pipeline and local-media seeks may then
            // fail when its replayable cache is disabled. Once installed, just update the shader
            // uniforms; do not switch the pipeline on/off while the item is prepared.
            exoPlayer.setVideoEffects(effectGraph.getEffects())
            videoEffectsActive = true
        } catch (error: Exception) {
            effectFailureFallbackSource = uriString
            videoEffectsActive = false
            runCatching { exoPlayer.setVideoEffects(emptyList()) }
            val failure = PreviewPlaybackFailure(
                engineType = engineType,
                uriScheme = runCatching { Uri.parse(uriString).scheme }.getOrNull(),
                uriAuthority = runCatching { Uri.parse(uriString).authority }.getOrNull(),
                mimeType = mimeType,
                errorCode = null,
                message = "GPU color grading could not initialize; continuing without the effect",
                causeType = error.javaClass.simpleName,
                causeMessage = sanitizeSourceDetail(error.message, Uri.parse(uriString)),
                category = PreviewPlaybackFailureCategory.PLAYBACK
            )
            _currentPlaybackFailure.value = failure
            _currentError.value = failure.toUserMessage()
            _state.update { it.copy(error = failure.toUserMessage()) }
        }
    }

    /** Retry an effect-pipeline playback error with the original decoded frames. */
    private fun retryWithoutVideoEffects(failure: PreviewPlaybackFailure): Boolean {
        val uriString = currentUri ?: return false
        if (effectFailureFallbackSource == uriString) return false
        effectFailureFallbackSource = uriString
        return try {
            exoPlayer.setVideoEffects(emptyList())
            videoEffectsActive = false
            _currentPlaybackFailure.value = failure
            _currentError.value = failure.toUserMessage()
            _state.update { it.copy(error = failure.toUserMessage(), isReady = false, isPlaying = false) }
            _currentState.value = PreviewState.LOADING
            stopTimeTracker()
            exoPlayer.seekTo(exoPlayer.currentPosition.coerceAtLeast(0L))
            exoPlayer.prepare()
            true
        } catch (error: Exception) {
            if (com.example.BuildConfig.DEBUG) {
                android.util.Log.w("Media3PreviewPlayer", "Could not retry without video effects", error)
            }
            false
        }
    }

    private fun sanitizeSourceDetail(detail: String?, sourceUri: Uri?): String? {
        var sanitized: String = detail ?: return null
        if (sourceUri != null) {
            val scheme = sourceUri.scheme
            val authority = sourceUri.authority
            val safeContext = when {
                scheme.isNullOrBlank() -> "source"
                authority.isNullOrBlank() -> "$scheme URI"
                else -> "$scheme://$authority"
            }
            sourceUri.toString().takeIf(String::isNotBlank)?.let { rawUri ->
                sanitized = sanitized.replace(rawUri, safeContext)
            }
            sourceUri.path?.let { path ->
                if (path.isNotBlank()) sanitized = sanitized.replace(path, "[source path]")
            }
        }
        return sanitized.take(512)
    }

    private fun toPlaybackFailure(error: PlaybackException): PreviewPlaybackFailure {
        val sourceUri = currentUri?.let { runCatching { Uri.parse(it) }.getOrNull() }
        val cause = generateSequence(error.cause) { it.cause }
            .firstOrNull { nested ->
                val type = nested.javaClass.simpleName.lowercase()
                val message = nested.message.orEmpty().lowercase()
                type.contains("videoframeprocessing") || type.contains("shader") ||
                    message.contains("shader") || message.contains("frame processing")
            } ?: error.cause
        val category = when (error.errorCode) {
            PlaybackException.ERROR_CODE_PARSING_CONTAINER_UNSUPPORTED,
            PlaybackException.ERROR_CODE_PARSING_MANIFEST_UNSUPPORTED,
            PlaybackException.ERROR_CODE_DECODING_FORMAT_EXCEEDS_CAPABILITIES,
            PlaybackException.ERROR_CODE_DECODING_FORMAT_UNSUPPORTED ->
                PreviewPlaybackFailureCategory.UNSUPPORTED_MEDIA
            PlaybackException.ERROR_CODE_DRM_UNSPECIFIED,
            PlaybackException.ERROR_CODE_DRM_SCHEME_UNSUPPORTED,
            PlaybackException.ERROR_CODE_DRM_PROVISIONING_FAILED,
            PlaybackException.ERROR_CODE_DRM_CONTENT_ERROR,
            PlaybackException.ERROR_CODE_DRM_LICENSE_ACQUISITION_FAILED,
            PlaybackException.ERROR_CODE_DRM_DISALLOWED_OPERATION,
            PlaybackException.ERROR_CODE_DRM_SYSTEM_ERROR,
            PlaybackException.ERROR_CODE_DRM_DEVICE_REVOKED,
            PlaybackException.ERROR_CODE_DRM_LICENSE_EXPIRED -> PreviewPlaybackFailureCategory.DRM
            in 2000..2999 -> PreviewPlaybackFailureCategory.SOURCE_ACCESS
            in 4000..4999 -> PreviewPlaybackFailureCategory.DECODER
            in 5000..5999 -> PreviewPlaybackFailureCategory.AUDIO
            else -> PreviewPlaybackFailureCategory.PLAYBACK
        }
        return PreviewPlaybackFailure(
            engineType = engineType,
            uriScheme = sourceUri?.scheme,
            uriAuthority = sourceUri?.authority,
            mimeType = currentMimeType,
            errorCode = error.errorCode,
            message = sanitizeSourceDetail(error.message, sourceUri) ?: error.javaClass.simpleName,
            causeType = cause?.javaClass?.simpleName,
            causeMessage = sanitizeSourceDetail(cause?.message, sourceUri),
            category = category,
            errorCodeName = error.errorCodeName
        )
    }

    private fun startTimeTracker() {
        if (timeTrackerJob?.isActive == true) return
        timeTrackerJob = scope.launch {
            while (isActive && exoPlayer.isPlaying) {
                onTimeChanged?.invoke(exoPlayer.currentPosition * 1000L)
                delay(1000 / 30) // 30fps
            }
        }
    }

    private fun stopTimeTracker() {
        timeTrackerJob?.cancel()
        timeTrackerJob = null
    }

    private fun cancelPausedGradeRefresh() {
        pausedGradeRefreshJob?.cancel()
        pausedGradeRefreshJob = null
        pausedGradeRefreshPending = false
    }

    /**
     * A grade update does not need to seek or disturb the playhead. Media3 exposes REDRAW for
     * re-running the existing effect graph on its held frame while paused.
     */
    private fun requestPausedGradeRefresh() {
        pausedGradeRefreshPending = true
        if (exoPlayer.isPlaying || released.get()) return

        val now = android.os.SystemClock.elapsedRealtime()
        val waitMs = (33L - (now - lastPausedGradeRefreshMs)).coerceAtLeast(0L)
        pausedGradeRefreshJob?.cancel()
        pausedGradeRefreshJob = null

        val redraw = {
            pausedGradeRefreshJob = null
            if (!released.get() && !exoPlayer.isPlaying && pausedGradeRefreshPending &&
                exoPlayer.playbackState == Player.STATE_READY
            ) {
                try {
                    exoPlayer.setVideoEffects(VideoFrameProcessor.REDRAW)
                    pausedGradeRefreshPending = false
                    lastPausedGradeRefreshMs = android.os.SystemClock.elapsedRealtime()
                } catch (error: Exception) {
                    if (com.example.BuildConfig.DEBUG) {
                        android.util.Log.w("Media3PreviewPlayer", "Paused-frame grade redraw failed", error)
                    }
                }
            }
        }

        if (waitMs == 0L) redraw()
        else pausedGradeRefreshJob = scope.launch {
            delay(waitMs)
            redraw()
        }
    }
    
    override fun setSurface(surface: Surface?) {
        if (!released.get()) exoPlayer.setVideoSurface(surface)
    }

    override fun setColorGradePreviewMode(enabled: Boolean) {
        if (released.get() || colorGradePreviewMode == enabled) return
        colorGradePreviewMode = enabled
        val uri = currentUri ?: return
        val wasActive = videoEffectsActive
        updateVideoEffects(uri, currentMimeType, latestEvaluatedParams)
        if (!wasActive && videoEffectsActive && !exoPlayer.isPlaying &&
            exoPlayer.playbackState == Player.STATE_READY
        ) {
            // Changing the effect list while paused does not reliably redraw the held decoder
            // frame on every Media3/device combination. A same-position seek forces one frame
            // through the now-attached graph; later slider changes use the existing REDRAW path.
            cancelPausedGradeRefresh()
            val positionMs = exoPlayer.currentPosition.coerceAtLeast(0L)
            exoPlayer.seekTo(positionMs)
            lastPausedGradeRefreshMs = android.os.SystemClock.elapsedRealtime()
        }
    }

    override fun updatePreviewGrade(evaluatedParams: VideoAdjustmentParams) {
        if (released.get()) return
        latestEvaluatedParams = evaluatedParams
        val uri = currentUri ?: return
        updateVideoEffects(uri, currentMimeType, evaluatedParams)
        if (!exoPlayer.isPlaying && videoEffectsActive && exoPlayer.playbackState == Player.STATE_READY) {
            requestPausedGradeRefresh()
        }
    }

    override fun loadMedia(
        uriString: String,
        useProxy: Boolean,
        sourcePositionMs: Long,
        evaluatedParams: VideoAdjustmentParams?,
        presentationTimeUs: Long,
        forceSeek: Boolean,
        mimeType: String?
    ) {
        if (released.get()) return
        val safePositionMs = sourcePositionMs.coerceAtLeast(0L)
        latestEvaluatedParams = evaluatedParams ?: VideoAdjustmentParams.default()
        val normalizedMimeType = mimeType?.substringBefore(';')?.trim()?.takeIf(String::isNotBlank)
        if (com.example.BuildConfig.DEBUG) {
            android.util.Log.d("PLAYER_MEDIA3", "Loading ${normalizedMimeType ?: "unknown media"} at $safePositionMs ms; proxy=$useProxy")
        }
        
        if (currentUri != uriString) effectFailureFallbackSource = null
        val sameSource = currentUri == uriString && currentMimeType == normalizedMimeType
        if (sameSource && exoPlayer.playerError == null) {
            // Keep the graph detached outside the color panel when no grade is active. While the
            // panel is open, keep it attached so paused-frame slider updates only change uniforms.
            updateVideoEffects(uriString, normalizedMimeType, latestEvaluatedParams)
            val isPlaying = exoPlayer.isPlaying
            val positionDeltaMs = kotlin.math.abs(exoPlayer.currentPosition - safePositionMs)
            // Project/grade state can change many times per second while playing. Only seek for
            // a real playhead jump then; routine slider updates just replace shader uniforms.
            val positionNeedsSeek = shouldSeekPreviewPosition(
                positionDeltaMs = positionDeltaMs,
                isPlaying = isPlaying,
                forceSeek = forceSeek
            )
            val needsPausedGradeRefresh = !isPlaying && evaluatedParams != null
            if (positionNeedsSeek) {
                cancelPausedGradeRefresh()
                exoPlayer.seekTo(safePositionMs)
                lastPausedGradeRefreshMs = android.os.SystemClock.elapsedRealtime()
            } else if (needsPausedGradeRefresh) {
                // Coalesce high-frequency slider updates to at most ~30 paused-frame redraws/sec.
                // The shader parameters update immediately; the redraw uses the latest values.
                requestPausedGradeRefresh()
            }
            val isReady = exoPlayer.playbackState == Player.STATE_READY
            _state.update { it.copy(usingProxy = useProxy, isReady = isReady) }
            if (isReady) {
                _currentState.value = if (exoPlayer.isPlaying) PreviewState.PLAYING else PreviewState.READY
            }
            return
        }
        cancelPausedGradeRefresh()
        lastPausedGradeRefreshMs = 0L
        _playbackMetrics.value = PreviewPlaybackMetrics()
        _currentState.value = PreviewState.LOADING
        currentUri = uriString
        currentMimeType = normalizedMimeType
        _currentError.value = null
        _currentPlaybackFailure.value = null
        _firstFrameRendered.value = false
        _state.update { it.copy(error = null, usingProxy = useProxy, isReady = false) }
        // The GlEffect reads these values on Media3's GL thread; non-default grades opt into it.
        updateVideoEffects(uriString, normalizedMimeType, latestEvaluatedParams)

        try {
            val mediaItemBuilder = MediaItem.Builder().setUri(Uri.parse(uriString))
            normalizedMimeType?.let(mediaItemBuilder::setMimeType)
            exoPlayer.setMediaItem(mediaItemBuilder.build(), safePositionMs)
            exoPlayer.prepare()
        } catch (error: Exception) {
            val sourceUri = runCatching { Uri.parse(uriString) }.getOrNull()
            val failure = PreviewPlaybackFailure(
                engineType = engineType,
                uriScheme = sourceUri?.scheme,
                uriAuthority = sourceUri?.authority,
                mimeType = normalizedMimeType,
                errorCode = null,
                message = "Media source could not be opened",
                causeType = error.javaClass.simpleName,
                causeMessage = sanitizeSourceDetail(error.message, sourceUri),
                category = if (sourceUri?.scheme == "content" || sourceUri?.scheme == "file") {
                    PreviewPlaybackFailureCategory.SOURCE_ACCESS
                } else {
                    PreviewPlaybackFailureCategory.PLAYBACK
                }
            )
            _currentPlaybackFailure.value = failure
            _currentError.value = failure.toUserMessage()
            _state.update { it.copy(error = failure.toUserMessage(), isReady = false, isPlaying = false) }
            _currentState.value = PreviewState.ERROR
            stopTimeTracker()
        }
    }

    fun clearMedia() {
        if (released.get()) return
        cancelPausedGradeRefresh()
        currentUri = null
        currentMimeType = null
        effectFailureFallbackSource = null
        // Keep the player pipeline unchanged between media items; see updateVideoEffects().
        effectGraph.setParams(VideoAdjustmentParams.default())
        _currentError.value = null
        _currentPlaybackFailure.value = null
        _firstFrameRendered.value = false
        exoPlayer.clearMediaItems()
        _state.update { it.copy(error = null, usingProxy = false, isReady = false, isPlaying = false) }
        _currentState.value = PreviewState.IDLE
    }

    override fun play() {
        if (released.get()) return
        cancelPausedGradeRefresh()
        if (currentUri != null) exoPlayer.play()
    }

    override fun pause() {
        if (!released.get()) exoPlayer.pause()
    }

    fun togglePlayPause() {
        if (exoPlayer.isPlaying) pause() else play()
    }
    
    override fun seekTo(timeUs: Long) {
        if (released.get()) return
        cancelPausedGradeRefresh()
        exoPlayer.seekTo((timeUs.coerceAtLeast(0L)) / 1000L)
    }

    override fun setPlaybackSpeed(speed: Float) {
        require(speed.isFinite() && speed > 0f) {
            "Playback speed must be finite and greater than zero."
        }
        if (!released.get()) exoPlayer.setPlaybackSpeed(speed)
    }

    override fun setVolume(volume: Float) {
        if (released.get()) return
        exoPlayer.volume = if (volume.isFinite()) volume.coerceIn(0f, 1f) else 1f
    }

    override fun setResolutionFallback(resolutionMode: String) {
        // Resolution selection is handled by the timeline's proxy/source policy.
    }

    override fun release() {
        if (!released.compareAndSet(false, true)) return
        onTimeChanged = null
        onPlaybackEnded = null
        cancelPausedGradeRefresh()
        stopTimeTracker()
        scope.cancel()
        exoPlayer.release()
        effectGraph.release()
        _currentState.value = PreviewState.IDLE
    }

    override val currentPositionUs: Long
        get() = if (released.get()) 0L else exoPlayer.currentPosition.coerceAtLeast(0L) * 1000L
    override val durationUs: Long
        get() = if (released.get()) 0L else exoPlayer.duration.takeIf { it >= 0L }?.times(1000L) ?: 0L
    override val isPlaying: Boolean get() = !released.get() && exoPlayer.isPlaying
}
