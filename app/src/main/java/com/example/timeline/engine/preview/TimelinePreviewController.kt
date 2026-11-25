package com.example.timeline.engine.preview

import android.content.Context
import com.example.timeline.core.TimelineClip
import com.example.timeline.core.TimelineProject
import com.example.timeline.core.TimelineFrameMath
import com.example.timeline.media.MediaAsset
import com.example.timeline.engine.preview.PreviewEngineSettings
import com.example.timeline.engine.preview.PreviewEngineType
import com.example.timeline.engine.preview.PreviewState
import com.example.timeline.engine.preview.PreviewEngine
import com.example.timeline.engine.preview.PreviewEngineFactory
import com.example.model.adjustments.VideoAdjustmentParams
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.Job
import kotlinx.coroutines.delay
import kotlinx.coroutines.cancel

class TimelinePreviewController(private val context: Context) {

    var previewEngine: PreviewEngine = createEngine(context, PreviewEngineType.AUTO)
        private set

    private fun createEngine(context: Context, type: PreviewEngineType): PreviewEngine {
        return PreviewEngineFactory.createEngine(context, type)
    }

    private var activeProject: TimelineProject? = null
    private var lastPlayheadFrame: Long = 0L
    private var preferredEngineType: PreviewEngineType = PreviewEngineType.AUTO

    /** The user's requested engine, which may differ from the automatic MCRAW or grade fallback. */
    fun engineTypeToRestoreAfterTemporaryPreview(): PreviewEngineType = preferredEngineType

    @Volatile private var colorGradeBypassed: Boolean = false
    @Volatile private var configuredPreferProxyPlayback: Boolean = true
    @Volatile private var preferProxyPlayback: Boolean = true
    private val _colorGradingPreviewActive = MutableStateFlow(false)
    val colorGradingPreviewActiveFlow: StateFlow<Boolean> = _colorGradingPreviewActive.asStateFlow()

    private fun evaluatedPreviewParams(project: TimelineProject, frame: Long): VideoAdjustmentParams =
        if (colorGradeBypassed) VideoAdjustmentParams.default()
        else com.example.timeline.engine.GradeEvaluationEngine.evaluateFrame(project, frame)

    /** Preview-only grade bypass; it never edits or saves the project. */
    fun setColorGradeBypassed(bypassed: Boolean) {
        if (colorGradeBypassed == bypassed) return
        colorGradeBypassed = bypassed
        activeProject?.let { project ->
            previewEngine.updatePreviewGrade(evaluatedPreviewParams(project, lastPlayheadFrame))
        }
    }

    /** Selects the source for smooth (proxy-first) versus color-accurate (original-first) preview. */
    fun setPreferProxyPlayback(preferProxy: Boolean) {
        configuredPreferProxyPlayback = preferProxy
        applyPreviewSourcePreference()
    }

    /** Color grading always operates on the original, independent of the editor's preview preset. */
    fun setColorGradingPreviewActive(active: Boolean) {
        if (_colorGradingPreviewActive.value == active) return
        _colorGradingPreviewActive.value = active
        previewEngine.setColorGradePreviewMode(active)
        applyPreviewSourcePreference()
    }

    private fun applyPreviewSourcePreference() {
        val effectivePreference = configuredPreferProxyPlayback && !_colorGradingPreviewActive.value
        if (preferProxyPlayback == effectivePreference) return
        preferProxyPlayback = effectivePreference
        activeProject?.let { project ->
            seekToTimelineFrame(project, lastPlayheadFrame, forceSeek = true)
        }
    }

    private val _activeClip = MutableStateFlow<TimelineClip?>(null)
    val activeClip: StateFlow<TimelineClip?> = _activeClip.asStateFlow()

    private val _activeAsset = MutableStateFlow<MediaAsset?>(null)
    val activeAsset: StateFlow<MediaAsset?> = _activeAsset.asStateFlow()

    private val _activeSourceIsProxy = MutableStateFlow(false)
    /** True only when the engine was actually given the READY proxy URI. */
    val activeSourceIsProxy: StateFlow<Boolean> = _activeSourceIsProxy.asStateFlow()
    private val proxyPlaybackFailedAssetIds = mutableSetOf<String>()
    private var activePreviewSource: PreviewMediaSource? = null

    private fun resolveSource(asset: MediaAsset): PreviewMediaSource? {
        if (asset.proxyStatus != com.example.timeline.media.ProxyStatus.READY || asset.proxyInfo == null) {
            proxyPlaybackFailedAssetIds.remove(asset.assetId)
        }
        val forceOriginal = _colorGradingPreviewActive.value || asset.assetId in proxyPlaybackFailedAssetIds
        return PreviewSourceResolver.resolveSource(
            asset = asset,
            preferProxy = preferProxyPlayback,
            forceOriginal = forceOriginal
        ).also { source ->
            activePreviewSource = source
            _activeSourceIsProxy.value = source?.isProxy == true
        }
    }

    /**
     * Switches away from a proxy that actually failed, without marking the original media as bad.
     * Returns false when the current source is not the active asset's proxy.
     */
    fun fallbackFromProxy(assetId: String): Boolean {
        val asset = _activeAsset.value ?: return false
        val project = activeProject ?: return false
        if (asset.assetId != assetId || !_activeSourceIsProxy.value) return false
        proxyPlaybackFailedAssetIds += assetId
        loadClipAtPlayhead(project, lastPlayheadFrame, forceSeek = true)
        return true
    }

    private val _engineTypeFlow = MutableStateFlow(previewEngine.engineType)
    val engineTypeFlow: StateFlow<PreviewEngineType> = _engineTypeFlow.asStateFlow()
    
    // Callbacks keep the timeline synchronized without a second UI-thread polling loop.
    var onPlayheadAdvanced: ((Long) -> Unit)? = null
    var onPlaybackEnded: (() -> Unit)? = null
    private var pendingBoundaryClipId: String? = null
    private var handledEndEngine: PreviewEngine? = null
    private var handledEndClipId: String? = null
    @Volatile private var playThroughGaps: Boolean = true
    @Volatile private var gapPlaybackPending: Boolean = false
    private var gapPlaybackJob: Job? = null
    private var stillPlaybackJob: Job? = null

    /** When enabled, intentional empty timeline ranges render black while transport continues. */
    fun setPlayThroughGaps(enabled: Boolean) {
        playThroughGaps = enabled
        if (!enabled && gapPlaybackJob?.isActive == true) {
            gapPlaybackPending = true
            gapPlaybackJob?.cancel()
            gapPlaybackJob = null
            previewEngine.pause()
            gapPlaybackPending = false
            onPlaybackEnded?.invoke()
        }
    }

    private val scope = CoroutineScope(Dispatchers.Main + Job())
    private var playheadJob: Job? = null
    private val pendingEngineReleases = mutableSetOf<PreviewEngine>()
    private var released = false

    private var lastEmittedTimeMs: Long = 0

    init {
        // startPlayheadMonitor() removed
        setupEngineListeners(previewEngine)
    }

    private fun setupEngineListeners(engine: PreviewEngine) {
        lastEmittedTimeMs = 0L
        engine.onTimeChanged = timeChanged@{ currentUs ->
            if (previewEngine !== engine || engine.engineType == PreviewEngineType.STILL_FRAME || currentUs < 0L) {
                return@timeChanged
            }
            val now = System.currentTimeMillis()
            val clip = activeClip.value ?: return@timeChanged
            val project = activeProject ?: return@timeChanged
            val rate = project.settings.getFpsRational()
            if (rate.numerator <= 0 || rate.denominator <= 0) return@timeChanged

            val sourceFrame = TimelineFrameResolver.microsecondsToFrames(currentUs, rate)
                ?: return@timeChanged
            val offsetFrames = try {
                Math.subtractExact(sourceFrame, clip.sourceIn)
            } catch (_: ArithmeticException) {
                return@timeChanged
            }
            val projectFrame = try {
                Math.addExact(clip.timelineStart, offsetFrames)
            } catch (_: ArithmeticException) {
                return@timeChanged
            }
            val clipEnd = try {
                Math.addExact(clip.timelineStart, clip.duration)
            } catch (_: ArithmeticException) {
                return@timeChanged
            }
            val lastFrame = if (clipEnd > clip.timelineStart) clipEnd - 1L else clip.timelineStart
            val boundedFrame = projectFrame.coerceIn(clip.timelineStart, lastFrame)

            if (now - lastEmittedTimeMs >= 30L && boundedFrame != lastPlayheadFrame) {
                lastPlayheadFrame = boundedFrame
                lastEmittedTimeMs = now
                onPlayheadAdvanced?.invoke(boundedFrame)
            }

            val playbackWasActive = engine.isPlaying || engine.currentState.value == PreviewState.PLAYING
            if (projectFrame >= clipEnd && playbackWasActive) {
                handlePlaybackBoundary(engine, project, clip)
            }
        }
        engine.onPlaybackEnded = ended@{
            if (previewEngine !== engine) return@ended
            val project = activeProject ?: return@ended
            val clip = activeClip.value ?: run {
                onPlaybackEnded?.invoke()
                return@ended
            }
            handlePlaybackBoundary(engine, project, clip)
        }
    }

    private fun handlePlaybackBoundary(engine: PreviewEngine, project: TimelineProject, clip: TimelineClip) {
        if (previewEngine !== engine || activeProject?.id != project.id || activeClip.value?.id != clip.id) return
        if (pendingBoundaryClipId == clip.id || (handledEndEngine === engine && handledEndClipId == clip.id)) return
        handledEndEngine = engine
        handledEndClipId = clip.id

        when (val decision = playbackBoundaryDecision(project, clip)) {
            is PlaybackBoundaryDecision.StopAtLastFrame -> {
                engine.pause()
                lastPlayheadFrame = decision.frame
                onPlayheadAdvanced?.invoke(decision.frame)
                onPlaybackEnded?.invoke()
            }
            is PlaybackBoundaryDecision.AdvanceToNextClip -> {
                pendingBoundaryClipId = clip.id
                lastPlayheadFrame = decision.frame
                onPlayheadAdvanced?.invoke(decision.frame)
                scope.launch {
                    try {
                        if (previewEngine === engine && activeProject?.id == project.id) {
                            seekToTimelineFrame(project, decision.frame, forceSeek = true)
                            if (activeProject?.id == project.id && activeClip.value?.id == decision.clip.id) {
                                play()
                            }
                        }
                    } finally {
                        if (pendingBoundaryClipId == clip.id) pendingBoundaryClipId = null
                    }
                }
            }
            is PlaybackBoundaryDecision.PlayThroughGap -> {
                if (!playThroughGaps) {
                    engine.pause()
                    lastPlayheadFrame = (decision.gapStartFrame - 1L).coerceAtLeast(clip.timelineStart)
                    onPlayheadAdvanced?.invoke(lastPlayheadFrame)
                    onPlaybackEnded?.invoke()
                } else {
                    beginGapPlayback(project, decision.gapStartFrame, decision.nextClip)
                }
            }
        }
    }

    private fun releasePendingEngine(engine: PreviewEngine) {
        if (pendingEngineReleases.remove(engine)) {
            engine.onTimeChanged = null
            engine.onPlaybackEnded = null
            engine.release()
        }
    }

    fun setEngineType(type: PreviewEngineType) {
        if (released) return
        if (type != PreviewEngineType.MOTIONCAM_RAW) preferredEngineType = type
        val activeAsset = _activeAsset.value
        val actualType = if (activeAsset != null && com.example.timeline.media.MotionCamRawFormat.isMcrawAsset(activeAsset)) {
            PreviewEngineType.MOTIONCAM_RAW
        } else {
            type
        }
        switchEngineType(actualType)
    }

    private fun switchEngineType(type: PreviewEngineType) {
        if (released) return
        val requestedEngine = resolveRequestedPreviewEngine(type)
        if (previewEngine.engineType == requestedEngine) return

        val wasPlaying = previewEngine.isPlaying
        val oldEngine = previewEngine
        var candidateEngine: PreviewEngine? = null

        try {
            val newEngine = createEngine(context, type)
            candidateEngine = newEngine
            newEngine.setColorGradePreviewMode(_colorGradingPreviewActive.value)
            // Load the active clip before switching the PlayerView to avoid an empty source flash.
            val project = activeProject
            val clip = activeClip.value
            val asset = activeAsset.value
            if (project != null && clip != null && asset != null) {
                val rate = project.settings.getFpsRational()
                val sourceFrame = TimelineFrameResolver.resolveSourceFrame(lastPlayheadFrame, clip)
                    ?: throw ArithmeticException("Source frame overflows Long")
                val sourcePositionUs = TimelineFrameResolver.convertFrameToMicroseconds(
                    sourceFrame,
                    rate.numerator,
                    rate.denominator
                ) ?: throw ArithmeticException("Source timestamp overflows Long")
                val sourcePositionMs = sourcePositionUs / 1_000L
                val source = resolveSource(asset)
                val evaluatedParams = evaluatedPreviewParams(project, lastPlayheadFrame)

                if (source != null) {
                    newEngine.setVolume(clip.volume)
                    newEngine.setSource(
                        source = source,
                        sourcePositionMs = sourcePositionMs,
                        evaluatedParams = evaluatedParams,
                        presentationTimeUs = sourcePositionUs,
                        forceSeek = true
                    )
                }
            } else {
                _activeSourceIsProxy.value = false
            }

            setupEngineListeners(newEngine)
            previewEngine = newEngine
            candidateEngine = null
            _engineTypeFlow.value = newEngine.engineType

            if (wasPlaying) newEngine.play()

            // Keep the old surface alive for one short handoff; the owner tracks it so a scope
            // cancellation before this coroutine starts cannot leak the old player.
            pendingEngineReleases += oldEngine
            scope.launch {
                try {
                    delay(200)
                } finally {
                    releasePendingEngine(oldEngine)
                }
            }
        } catch (error: LinkageError) {
            recoverFailedEngineSwitch(type, oldEngine, candidateEngine, error)
        } catch (error: Exception) {
            recoverFailedEngineSwitch(type, oldEngine, candidateEngine, error)
        }
    }

    private fun recoverFailedEngineSwitch(
        requestedType: PreviewEngineType,
        oldEngine: PreviewEngine,
        candidateEngine: PreviewEngine?,
        error: Throwable
    ) {
        runCatching { candidateEngine?.release() }
        if (com.example.BuildConfig.DEBUG) {
            android.util.Log.e("TimelinePreviewController", "Failed to switch engine to $requestedType", error)
        }

        // Native/library initialization errors should not escape the UI thread or strand playback.
        // Preserve the old engine when possible; otherwise retry with the platform decoder.
        if (
            previewEngine === oldEngine &&
            (requestedType == PreviewEngineType.FFMPEG_NATIVE || requestedType == PreviewEngineType.VLC_NATIVE)
        ) {
            switchEngineType(PreviewEngineType.MEDIA3_FALLBACK)
        }
    }

    fun loadProject(project: TimelineProject) {
        if (activeProject?.id != project.id) {
            pendingBoundaryClipId = null
            handledEndEngine = null
            handledEndClipId = null
        }
        activeProject = project
    }

    fun seekToTimelineFrame(project: TimelineProject, frame: Long, forceSeek: Boolean = true) {
        gapPlaybackJob?.cancel()
        gapPlaybackJob = null
        stillPlaybackJob?.cancel()
        stillPlaybackJob = null
        if (activeProject?.id != project.id) {
            loadProject(project)
        } else {
            activeProject = project
        }
        lastPlayheadFrame = frame
        loadClipAtPlayhead(project, frame, forceSeek)
    }

    private fun loadClipAtPlayhead(project: TimelineProject, playheadFrame: Long, forceSeek: Boolean) {
        val topClip = TimelineFrameResolver.getTopmostVisibleVideoClip(project, playheadFrame)
        if (topClip == null) {
            _activeClip.value = null
            _activeAsset.value = null
            activePreviewSource = null
            _activeSourceIsProxy.value = false
            val eng = previewEngine
            if (eng is Media3FallbackPreviewEngine) {
                eng.clearMedia()
            } else if (eng is MotionCamRawPreviewEngine) {
                eng.clearMedia()
            }
            return
        }
        
        val asset = project.mediaAssets.find { it.assetId == topClip.mediaId }
        val prevClipId = _activeClip.value?.id

        _activeClip.value = topClip
        _activeAsset.value = asset

        if (asset == null) {
            activePreviewSource = null
            _activeSourceIsProxy.value = false
            val eng = previewEngine
            if (eng is Media3FallbackPreviewEngine) {
                eng.clearMedia()
            } else if (eng is MotionCamRawPreviewEngine) {
                eng.clearMedia()
            }
            return
        }

        val isMcraw = com.example.timeline.media.MotionCamRawFormat.isMcrawAsset(asset)
        val isImage = asset.assetType == com.example.timeline.media.MediaAssetType.IMAGE ||
            asset.mimeType.startsWith("image/")
        when {
            isMcraw && previewEngine.engineType != PreviewEngineType.MOTIONCAM_RAW -> {
                switchEngineType(PreviewEngineType.MOTIONCAM_RAW)
                return
            }
            !isMcraw && previewEngine.engineType == PreviewEngineType.MOTIONCAM_RAW -> {
                switchEngineType(if (isImage) PreviewEngineType.STILL_FRAME else preferredEngineType)
                return
            }
            isImage && previewEngine.engineType != PreviewEngineType.STILL_FRAME -> {
                switchEngineType(PreviewEngineType.STILL_FRAME)
                return
            }
            !isImage && previewEngine.engineType == PreviewEngineType.STILL_FRAME -> {
                switchEngineType(preferredEngineType)
                return
            }
        }

        val rat = project.settings.getFpsRational()
        val sourcePosition = TimelineFrameResolver.resolveSourceFrame(playheadFrame, topClip)
            ?.let { frame -> TimelineFrameResolver.convertFrameToMicroseconds(frame, rat.numerator, rat.denominator) }
        if (sourcePosition == null) {
            activePreviewSource = null
            _activeSourceIsProxy.value = false
            val engine = previewEngine
            if (engine is Media3FallbackPreviewEngine) engine.clearMedia()
            return
        }
        val sourcePositionUs = sourcePosition
        val sourcePositionMs = sourcePositionUs / 1_000L

        val evaluatedParams = evaluatedPreviewParams(project, playheadFrame)

        // Prefer a ready proxy for editor playback. Source MIME is passed through so Media3 can
        // select the right extractor for opaque SAF/content URIs and report useful failures.
        val source = resolveSource(asset)

        if (source == null) {
            val engine = previewEngine
            if (engine is Media3FallbackPreviewEngine) engine.clearMedia()
        } else {
            previewEngine.setVolume(topClip.volume)
            previewEngine.setSource(
                source = source,
                sourcePositionMs = sourcePositionMs,
                evaluatedParams = evaluatedParams,
                presentationTimeUs = sourcePositionUs,
                forceSeek = forceSeek || prevClipId != topClip.id
            )
        }
    }

    private fun beginGapPlayback(project: TimelineProject, gapStartFrame: Long, nextClip: TimelineClip) {
        gapPlaybackJob?.cancel()
        gapPlaybackPending = true
        try {
            val engine = previewEngine
            engine.pause()
            lastPlayheadFrame = gapStartFrame
            loadClipAtPlayhead(project, gapStartFrame, forceSeek = true)
            val startElapsedMs = android.os.SystemClock.elapsedRealtime()
            val frameRate = project.settings.getFpsRational()
            gapPlaybackJob = scope.launch {
                try {
                    while (previewEngine === engine && activeProject?.id == project.id && playThroughGaps) {
                        val elapsedMs = android.os.SystemClock.elapsedRealtime() - startElapsedMs
                        val elapsedFrames = try {
                            Math.multiplyExact(elapsedMs, frameRate.numerator.toLong()) /
                                (1_000L * frameRate.denominator.toLong())
                        } catch (_: ArithmeticException) {
                            Long.MAX_VALUE
                        }
                        val frame = try {
                            Math.addExact(gapStartFrame, elapsedFrames)
                        } catch (_: ArithmeticException) {
                            Long.MAX_VALUE
                        }
                        if (frame >= nextClip.timelineStart) break
                        if (frame != lastPlayheadFrame) {
                            lastPlayheadFrame = frame
                            onPlayheadAdvanced?.invoke(frame)
                        }
                        delay(16L)
                    }
                    if (previewEngine === engine && activeProject?.id == project.id && playThroughGaps) {
                        lastPlayheadFrame = nextClip.timelineStart
                        onPlayheadAdvanced?.invoke(nextClip.timelineStart)
                        loadClipAtPlayhead(project, nextClip.timelineStart, forceSeek = true)
                        if (activeProject?.id == project.id && activeClip.value != null) {
                            gapPlaybackJob = null
                            play()
                        }
                    }
                } finally {
                    if (gapPlaybackJob?.isActive != true) gapPlaybackJob = null
                }
            }
        } finally {
            gapPlaybackPending = false
        }
    }

    private fun startStillClipPlayback(project: TimelineProject, clip: TimelineClip) {
        stillPlaybackJob?.cancel()
        val engine = previewEngine
        val startFrame = lastPlayheadFrame.coerceIn(
            clip.timelineStart,
            (clip.timelineEnd - 1L).coerceAtLeast(clip.timelineStart)
        )
        val startElapsedMs = android.os.SystemClock.elapsedRealtime()
        val frameRate = project.settings.getFpsRational()
        engine.play()
        stillPlaybackJob = scope.launch {
            try {
                while (previewEngine === engine && activeProject?.id == project.id && activeClip.value?.id == clip.id) {
                    val elapsedMs = android.os.SystemClock.elapsedRealtime() - startElapsedMs
                    val elapsedFrames = try {
                        Math.multiplyExact(elapsedMs, frameRate.numerator.toLong()) /
                            (1_000L * frameRate.denominator.toLong())
                    } catch (_: ArithmeticException) {
                        Long.MAX_VALUE
                    }
                    val frame = try {
                        Math.addExact(startFrame, elapsedFrames)
                    } catch (_: ArithmeticException) {
                        Long.MAX_VALUE
                    }
                    if (frame >= clip.timelineEnd) break
                    if (frame > lastPlayheadFrame) {
                        lastPlayheadFrame = frame
                        onPlayheadAdvanced?.invoke(frame)
                    }
                    delay(16L)
                }
                if (previewEngine === engine && activeProject?.id == project.id && activeClip.value?.id == clip.id) {
                    handlePlaybackBoundary(engine, project, clip)
                }
            } finally {
                if (stillPlaybackJob?.isActive != true) stillPlaybackJob = null
            }
        }
    }

    fun play() {
        handledEndEngine = null
        handledEndClipId = null
        if (gapPlaybackJob?.isActive == true || stillPlaybackJob?.isActive == true) return
        val project = activeProject
        val clip = _activeClip.value
        if (clip != null && lastPlayheadFrame >= clip.timelineEnd - 1L) {
            val currentProject = project ?: return
            val decision = playbackBoundaryDecision(currentProject, clip)
            if (decision is PlaybackBoundaryDecision.PlayThroughGap) {
                if (playThroughGaps) {
                    beginGapPlayback(currentProject, decision.gapStartFrame, decision.nextClip)
                    return
                }
                seekToTimelineFrame(currentProject, clip.timelineStart)
            } else if (decision is PlaybackBoundaryDecision.StopAtLastFrame) {
                seekToTimelineFrame(currentProject, clip.timelineStart)
            }
        } else if (clip == null && project != null) {
            val nextClip = project.tracks.asSequence()
                .filter { it.type == com.example.timeline.core.TrackType.VIDEO && it.isVisible }
                .flatMap { it.clips.asSequence() }
                .filter { it.isEnabled && it.duration > 0L && it.timelineStart > lastPlayheadFrame }
                .minByOrNull { it.timelineStart }
            if (nextClip != null) {
                val visibleNextClip = TimelineFrameResolver.getTopmostVisibleVideoClip(project, nextClip.timelineStart)
                    ?: nextClip
                if (playThroughGaps) {
                    beginGapPlayback(project, lastPlayheadFrame.coerceAtMost(nextClip.timelineStart), visibleNextClip)
                } else {
                    seekToTimelineFrame(project, nextClip.timelineStart, forceSeek = true)
                    play()
                }
                return
            }
        }
        val activeClip = _activeClip.value
        val activeAsset = _activeAsset.value
        if (project != null && activeClip != null && activeAsset != null &&
            (activeAsset.assetType == com.example.timeline.media.MediaAssetType.IMAGE ||
                activeAsset.mimeType.startsWith("image/"))
        ) {
            startStillClipPlayback(project, activeClip)
            return
        }
        previewEngine.play()
    }

    fun pause() {
        gapPlaybackJob?.cancel()
        gapPlaybackJob = null
        stillPlaybackJob?.cancel()
        stillPlaybackJob = null
        previewEngine.pause()
    }

    fun togglePlayPause() {
        if (previewEngine.isPlaying) pause() else play()
    }

    fun stop() {
        gapPlaybackJob?.cancel()
        gapPlaybackJob = null
        stillPlaybackJob?.cancel()
        stillPlaybackJob = null
        previewEngine.pause()
        val eng = previewEngine
        when (eng) {
            is Media3FallbackPreviewEngine -> eng.clearMedia()
            is MotionCamRawPreviewEngine -> eng.clearMedia()
        }
    }
    
    /** Extract the selected timeline clip at the requested frame, independent of preview-engine
     * readiness or a stale active-player clip. This makes freeze-frame editing deterministic. */
    suspend fun extractCurrentFrameBitmap(clipId: String, timelineFrame: Long): android.graphics.Bitmap? {
        val project = activeProject ?: return null
        val clip = project.tracks.asSequence()
            .filter { it.type == com.example.timeline.core.TrackType.VIDEO && it.isVisible }
            .flatMap { it.clips.asSequence() }
            .firstOrNull { it.id == clipId && it.isEnabled && timelineFrame >= it.timelineStart && timelineFrame < it.timelineEnd }
            ?: return null
        val asset = project.mediaAssets.firstOrNull { it.assetId == clip.mediaId } ?: return null
        if (!asset.metadata.hasVideo) return null
        val uriStr = PreviewSourceResolver.resolvePlayableUri(asset, false, forceOriginal = true).first ?: return null
        val rat = project.settings.getFpsRational()
        val sourceFrame = TimelineFrameResolver.resolveSourceFrame(timelineFrame, clip) ?: return null
        val sourceTimeUs = TimelineFrameResolver.convertFrameToMicroseconds(sourceFrame, rat.numerator, rat.denominator)
            ?: return null
        return kotlinx.coroutines.withContext(kotlinx.coroutines.Dispatchers.IO) {
            if (com.example.timeline.media.MotionCamRawFormat.isMcrawAsset(asset)) {
                var handle = 0L
                try {
                    handle = com.example.timeline.media.NativeMcrawDecoder.open(context, android.net.Uri.parse(uriStr))
                    val frameCount = com.example.timeline.media.NativeMcrawDecoder.frameCount(handle)
                    if (frameCount <= 0) return@withContext null
                    val targetNs = sourceTimeUs.coerceAtLeast(0L).coerceAtMost(Long.MAX_VALUE / 1_000L) * 1_000L
                    val rawTimestamps = LongArray(frameCount) { index ->
                        com.example.timeline.media.NativeMcrawDecoder.frameTimestampNs(handle, index)
                    }
                    val timestampOrigin = rawTimestamps.firstOrNull() ?: 0L
                    val timestamps = LongArray(frameCount) { index ->
                        (rawTimestamps[index] - timestampOrigin).coerceAtLeast(0L)
                    }
                    var low = 0
                    var high = timestamps.lastIndex
                    while (low < high) {
                        val middle = (low + high) ushr 1
                        if (timestamps[middle] < targetNs) low = middle + 1 else high = middle
                    }
                    val next = low
                    val previous = (next - 1).coerceAtLeast(0)
                    val selected = if (kotlin.math.abs(targetNs - timestamps[previous]) <= kotlin.math.abs(timestamps[next] - targetNs)) previous else next
                    // The native RAW renderer already applies the MotionCam frame rotation.
                    // Rotating this extracted bitmap again would double-rotate scope/current-frame output.
                    com.example.timeline.media.NativeMcrawDecoder.decodeFrame(handle, selected, 960)
                } catch (_: Exception) {
                    null
                } finally {
                    if (handle != 0L) com.example.timeline.media.NativeMcrawDecoder.close(handle)
                }
            } else {
                val mmr = android.media.MediaMetadataRetriever()
                try {
                    mmr.setDataSource(context, android.net.Uri.parse(uriStr))
                    mmr.getFrameAtTime(sourceTimeUs, android.media.MediaMetadataRetriever.OPTION_CLOSEST)
                } catch (e: Exception) {
                    null
                } finally {
                    mmr.release()
                }
            }
        }
    }

    fun release() {
        if (released) return
        released = true
        onPlayheadAdvanced = null
        onPlaybackEnded = null
        playheadJob?.cancel()
        playheadJob = null
        gapPlaybackJob?.cancel()
        gapPlaybackJob = null
        stillPlaybackJob?.cancel()
        stillPlaybackJob = null
        scope.cancel()
        pendingEngineReleases.toList().forEach(::releasePendingEngine)
        previewEngine.onTimeChanged = null
        previewEngine.onPlaybackEnded = null
        previewEngine.release()
    }

    fun onTimelineChanged(project: TimelineProject) {
        seekToTimelineFrame(project, lastPlayheadFrame)
    }

    fun onMediaAssetUpdated(project: TimelineProject) {
        seekToTimelineFrame(project, lastPlayheadFrame)
    }

    private fun updatePreviewForProjectChangeWithoutSeek(project: TimelineProject): Boolean {
        val previousProject = activeProject ?: return false
        if (previousProject.id != project.id) return false
        val previousClip = _activeClip.value ?: return false
        val previousSource = activePreviewSource
        val updatedClip = TimelineFrameResolver.getTopmostVisibleVideoClip(project, lastPlayheadFrame)
            ?: return false
        val updatedAsset = project.mediaAssets.firstOrNull { it.assetId == updatedClip.mediaId }
            ?: return false
        val updatedSource = resolveSource(updatedAsset)

        if (!canUpdatePreviewGradeWithoutSeek(
                previousClip = previousClip,
                updatedClip = updatedClip,
                previousSource = previousSource,
                updatedSource = updatedSource,
                previousFrameRate = previousProject.settings.getFpsRational(),
                updatedFrameRate = project.settings.getFpsRational(),
                engineType = previewEngine.engineType,
                updatedAssetIsMcraw = com.example.timeline.media.MotionCamRawFormat.isMcrawAsset(updatedAsset)
            )
        ) return false

        activeProject = project
        _activeClip.value = updatedClip
        _activeAsset.value = updatedAsset
        previewEngine.setVolume(updatedClip.volume)
        previewEngine.updatePreviewGrade(evaluatedPreviewParams(project, lastPlayheadFrame))
        return true
    }

    fun onProjectChanged(project: TimelineProject) {
        if (activeProject == null || activeProject?.id != project.id) {
            loadProject(project)
            seekToTimelineFrame(project, 0L)
        } else if (!updatePreviewForProjectChangeWithoutSeek(project)) {
            loadProject(project)
            seekToTimelineFrame(project, lastPlayheadFrame, forceSeek = false)
        }
    }

    fun updatePlayheadFromPlayer(
        onUpdateTimelineFrame: (Long, Long, Long) -> Unit,
        onPlaybackEnded: () -> Unit = {}
    ) {
        if (previewEngine.currentState.value == PreviewState.LOADING || previewEngine.currentState.value == PreviewState.IDLE) {
            return
        }
        val clip = _activeClip.value
        if (clip == null) {
            onPlaybackEnded()
            return
        }

        val playerPositionUs = previewEngine.currentPositionUs.coerceAtLeast(0L)
        val rat = activeProject?.settings?.getFpsRational() ?: return
        val currentSourceFrame = TimelineFrameMath.framesFromMicroseconds(playerPositionUs, rat) ?: return
        val newProjFrame = try {
            Math.addExact(
                clip.timelineStart,
                Math.subtractExact(currentSourceFrame, clip.sourceIn)
            )
        } catch (_: ArithmeticException) {
            return
        }
        val playerPosMs = playerPositionUs / 1_000L
        // only callback if valid
        if (newProjFrame >= clip.timelineStart && newProjFrame < clip.timelineEnd) {
             val changed = newProjFrame != lastPlayheadFrame
             lastPlayheadFrame = newProjFrame
             onUpdateTimelineFrame(playerPosMs, clip.timelineStart, clip.sourceIn)
             if (changed) {
                 onPlayheadAdvanced?.invoke(newProjFrame)
             }
        } else {
             // Reached end of clip, need to seek to next clip...
             if (newProjFrame >= clip.timelineEnd) {
                 activeProject?.let { p ->
                     val nextClip = TimelineFrameResolver.getTopmostVisibleVideoClip(p, clip.timelineEnd)
                     if (nextClip == null) {
                         lastPlayheadFrame = clip.timelineEnd - 1
                         onPlayheadAdvanced?.invoke(lastPlayheadFrame)
                         seekToTimelineFrame(p, clip.timelineEnd - 1)
                         pause()
                         onPlaybackEnded()
                     } else {
                         lastPlayheadFrame = clip.timelineEnd
                         onPlayheadAdvanced?.invoke(lastPlayheadFrame)
                         seekToTimelineFrame(p, clip.timelineEnd)
                         play()
                     }
                 }
             } else if (newProjFrame < clip.timelineStart) {
                 // Ignore stale positions from before the clip starts
                 // They will resolve once the player catches up
             }
        }
    }
}
