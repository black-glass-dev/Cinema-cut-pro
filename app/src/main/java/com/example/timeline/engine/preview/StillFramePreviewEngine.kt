package com.example.timeline.engine.preview

import android.content.Context
import android.graphics.Bitmap
import android.graphics.BitmapFactory
import android.graphics.Canvas
import android.graphics.Paint
import android.graphics.Rect
import android.media.MediaMetadataRetriever
import android.net.Uri
import android.view.Surface
import com.example.model.adjustments.VideoAdjustmentParams
import com.example.model.adjustments.engine.CpuFrameProcessor
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

class StillFramePreviewEngine(private val context: Context) : PreviewEngine {
    override val engineType = PreviewEngineType.STILL_FRAME

    override val capabilities: PreviewEngineCapabilities = PreviewEngineCapabilities(
        canPlayMovingVideo = false,
        canScrub = true,
        canRenderFirstFrame = true,
        canApplyRealtimeColorGrade = true, // We can grade a still frame image easily!
        canApplyBasicAdjustments = true,
        canUseProxy = true,
        currentFailureReason = null
    )

    private val _currentState = MutableStateFlow(PreviewState.IDLE)
    override val currentState = _currentState.asStateFlow()

    private val _currentError = MutableStateFlow<String?>(null)
    override val currentError = _currentError.asStateFlow()

    private val _firstFrameRendered = MutableStateFlow(false)
    override val firstFrameRendered = _firstFrameRendered.asStateFlow()

    private var surface: Surface? = null
    private var currentUri: String? = null
    private var currentMimeType: String? = null
    private var currentPositionMs: Long = 0L
    private var durationMs: Long = 0L
    private var currentParams: VideoAdjustmentParams? = null
    
    // Caching for graded still preview
    private var cachedSourceBitmap: Bitmap? = null
    private var cachedUri: String? = null
    private var cachedTimeMs: Long = -1L

    override var onTimeChanged: ((Long) -> Unit)? = null
    override var onPlaybackEnded: (() -> Unit)? = null

    private val scope = CoroutineScope(Dispatchers.Main + Job())
    private var renderJob: Job? = null
    private var isPlayingState = false

    override fun setSurface(surface: Surface?) {
        this.surface = surface
        renderFrame()
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
        val uriChanged = uriString != currentUri
        currentUri = uriString
        currentMimeType = mimeType?.substringBefore(';')?.trim()
        currentPositionMs = sourcePositionMs
        currentParams = evaluatedParams

        if (uriChanged) {
            _currentState.value = PreviewState.LOADING
            // fetch duration
            scope.launch {
                durationMs = withContext(Dispatchers.IO) {
                    val mmr = MediaMetadataRetriever()
                    try {
                        mmr.setDataSource(context, Uri.parse(uriString))
                        val d = mmr.extractMetadata(MediaMetadataRetriever.METADATA_KEY_DURATION)
                        d?.toLongOrNull() ?: 0L
                    } catch (e: Exception) {
                        0L
                    } finally {
                        mmr.release()
                    }
                }
                _currentState.value = PreviewState.READY
            }
        }
        
        renderFrame()
    }

    override fun updatePreviewGrade(evaluatedParams: VideoAdjustmentParams) {
        currentParams = evaluatedParams
        renderFrame()
    }

    private fun renderFrame() {
        if (surface == null || !surface!!.isValid || currentUri == null) return

        val uriToLoad = currentUri!!
        val paramsToApply = currentParams
        val timeMsToLoad = currentPositionMs

        renderJob?.cancel()
        renderJob = scope.launch(Dispatchers.IO) {
            try {
                // 1. Fetch raw frame if needed
                if (cachedSourceBitmap == null || cachedUri != uriToLoad || cachedTimeMs != timeMsToLoad) {
                    var rawBitmap: Bitmap? = null
                    if (currentMimeType?.startsWith("image/") == true) {
                        rawBitmap = runCatching {
                            context.contentResolver.openInputStream(Uri.parse(uriToLoad))?.use { stream ->
                                BitmapFactory.decodeStream(stream)
                            }
                        }.getOrNull()
                    } else {
                        val mmr = MediaMetadataRetriever()
                        try {
                            mmr.setDataSource(context, Uri.parse(uriToLoad))
                            rawBitmap = mmr.getFrameAtTime(
                                timeMsToLoad * 1000,
                                MediaMetadataRetriever.OPTION_CLOSEST_SYNC
                            )
                        } catch (e: Exception) {
                            if (com.example.BuildConfig.DEBUG) e.printStackTrace()
                        } finally {
                            mmr.release()
                        }
                    }
                    if (rawBitmap != null) {
                        val scale = minOf(1f, 640f / maxOf(rawBitmap.width, rawBitmap.height).toFloat())
                        val scaledWidth = (rawBitmap.width * scale).toInt().coerceAtLeast(1)
                        val scaledHeight = (rawBitmap.height * scale).toInt().coerceAtLeast(1)
                        val scaledBitmap = if (scale < 1f) {
                            Bitmap.createScaledBitmap(rawBitmap, scaledWidth, scaledHeight, true)
                        } else rawBitmap
                        if (scaledBitmap !== rawBitmap) rawBitmap.recycle()
                        cachedSourceBitmap?.recycle()
                        cachedSourceBitmap = scaledBitmap
                        cachedUri = uriToLoad
                        cachedTimeMs = timeMsToLoad
                    }
                }

                val source = cachedSourceBitmap ?: return@launch
                
                // 2. Apply grade
                val gradedBitmap = if (paramsToApply != null) {
                    CpuFrameProcessor.process(source, paramsToApply)
                } else {
                    source
                }
                
                // 3. Draw to surface
                withContext(Dispatchers.Main) {
                    val s = surface ?: return@withContext
                    if (!s.isValid) return@withContext
                    var canvas: Canvas? = null
                    try {
                        canvas = s.lockCanvas(null)
                        if (canvas != null) {
                            canvas.drawColor(android.graphics.Color.BLACK)
                            val canvasAspect = canvas.width.toFloat() / canvas.height.toFloat()
                            val bmpAspect = gradedBitmap.width.toFloat() / gradedBitmap.height.toFloat()
                            
                            val drawRect = Rect()
                            if (canvasAspect > bmpAspect) {
                                val h = canvas.height
                                val w = (h * bmpAspect).toInt()
                                val x = (canvas.width - w) / 2
                                drawRect.set(x, 0, x + w, h)
                            } else {
                                val w = canvas.width
                                val h = (w / bmpAspect).toInt()
                                val y = (canvas.height - h) / 2
                                drawRect.set(0, y, w, y + h)
                            }
                            canvas.drawBitmap(gradedBitmap, null, drawRect, Paint())
                        }
                    } catch (e: Exception) {
                        if (com.example.BuildConfig.DEBUG) e.printStackTrace()
                    } finally {
                        if (canvas != null) {
                            try {
                                s.unlockCanvasAndPost(canvas)
                            } catch (e: Exception) {
                                if (com.example.BuildConfig.DEBUG) e.printStackTrace()
                            }
                        }
                        _firstFrameRendered.value = true
                    }
                    if (gradedBitmap != source) {
                        gradedBitmap.recycle()
                    }
                }
            } catch (e: Exception) {
                if (com.example.BuildConfig.DEBUG) e.printStackTrace()
            }
        }
    }

    override fun play() {
        isPlayingState = true
        _currentState.value = PreviewState.PLAYING
    }

    override fun pause() {
        isPlayingState = false
        _currentState.value = PreviewState.READY
    }

    override fun seekTo(timeUs: Long) {
        currentPositionMs = timeUs / 1000
        renderFrame()
    }

    override fun setPlaybackSpeed(speed: Float) {
    }

    override fun setResolutionFallback(resolutionMode: String) {
    }

    override fun release() {
        onTimeChanged = null
        onPlaybackEnded = null
        renderJob?.cancel()
        surface = null
        cachedSourceBitmap?.recycle()
        cachedSourceBitmap = null
    }

    override val currentPositionUs: Long
        get() = currentPositionMs * 1000

    override val durationUs: Long
        get() = durationMs * 1000

    override val isPlaying: Boolean
        get() = isPlayingState
}
