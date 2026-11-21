package com.example.timeline.export.backend

import android.content.Context
import android.media.MediaCodecInfo
import android.media.MediaCodecList
import android.media.MediaExtractor
import android.media.MediaFormat
import android.net.Uri
import android.os.Build
import android.os.Handler
import android.os.Looper
import androidx.media3.common.Effect
import androidx.media3.common.MediaItem
import androidx.media3.common.MimeTypes
import androidx.media3.common.util.UnstableApi
import androidx.media3.effect.Presentation
import androidx.media3.transformer.AudioEncoderSettings
import androidx.media3.transformer.Composition
import androidx.media3.transformer.DefaultEncoderFactory
import androidx.media3.transformer.EditedMediaItem
import androidx.media3.transformer.EditedMediaItemSequence
import androidx.media3.transformer.Effects
import androidx.media3.transformer.ExportException
import androidx.media3.transformer.ExportResult
import androidx.media3.transformer.ProgressHolder
import androidx.media3.transformer.Transformer
import androidx.media3.transformer.VideoEncoderSettings
import com.example.model.adjustments.VideoAdjustmentParams
import com.example.model.colorgrade.ColorMatchParams
import com.example.timeline.core.ClipType
import com.example.timeline.core.ProjectColorSpace
import com.example.timeline.core.Rational
import com.example.timeline.core.TimelineClip
import com.example.timeline.core.TimelineFrameMath
import com.example.timeline.core.TimelineProject
import com.example.timeline.core.TimelineTrack
import com.example.timeline.core.TrackType
import com.example.timeline.core.transform.ClipTransform
import com.example.timeline.engine.GradeEvaluationEngine
import com.example.timeline.export.model.ExportBackendType
import com.example.timeline.export.model.ExportCodec
import com.example.timeline.export.model.ExportContainer
import com.example.timeline.export.model.ExportJob
import com.example.timeline.export.model.ExportJobStatus
import com.example.timeline.export.model.ExportProgress
import com.example.timeline.export.model.ExportSettings
import com.example.timeline.export.model.ExportValidationResult
import com.example.timeline.media.FrameRate
import com.example.timeline.media.MediaAsset
import com.example.timeline.media.MotionCamRawFormat
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.NonCancellable
import kotlinx.coroutines.currentCoroutineContext
import kotlinx.coroutines.delay
import kotlinx.coroutines.ensureActive
import kotlinx.coroutines.withContext
import java.io.File
import java.math.BigInteger
import java.nio.ByteBuffer
import java.util.UUID
import java.util.concurrent.ConcurrentHashMap
import java.util.concurrent.atomic.AtomicBoolean
import kotlin.math.abs
import kotlin.math.min
import kotlin.math.roundToLong

/**
 * A deliberately bounded real export backend.
 *
 * Media3 owns Android demux/decode/encode/mux orchestration. This adapter accepts a single visible,
 * unlayered video track containing one or more contiguous CFR clips whose edits can be represented
 * exactly by trims and a presentation resize. Gaps must be fixed on the timeline before export.
 * Everything else is rejected before export; it is never handed to a placeholder exporter.
 */
internal fun isWithinMedia3ResolutionLimit(
    width: Int,
    height: Int,
    maximumLongEdge: Int,
    maximumShortEdge: Int
): Boolean {
    if (width <= 0 || height <= 0) return false
    return maxOf(width, height) <= maximumLongEdge && minOf(width, height) <= maximumShortEdge
}

internal fun shouldIncludeEmbeddedAudio(
    settings: ExportSettings,
    trackMuted: Boolean,
    clipHasAudio: List<Boolean>
): Boolean = settings.includeAudio && !trackMuted && clipHasAudio.isNotEmpty() && clipHasAudio.all { it }

@OptIn(UnstableApi::class)
internal fun hdrModeForExport(hasHdrInput: Boolean): Int = if (hasHdrInput) {
    Composition.HDR_MODE_TONE_MAP_HDR_TO_SDR_USING_OPEN_GL
} else {
    Composition.HDR_MODE_KEEP_HDR
}

internal interface Media3CodecSupportProbe {
    fun hasVideoEncoder(settings: ExportSettings, requireHardware: Boolean): Boolean
    fun hasAudioEncoder(sampleRate: Int, channelCount: Int, bitrate: Int): Boolean
    fun hasMcrawIntermediateEncoder(width: Int, height: Int, frameRate: Float, bitrate: Int): Boolean = false
}

@OptIn(UnstableApi::class)
class Media3TransformerBackend internal constructor(
    context: Context,
    private val codecSupportProbe: Media3CodecSupportProbe = AndroidMedia3CodecSupportProbe
) : ExportBackend {
    override val backendType: ExportBackendType = ExportBackendType.MEDIA3_TRANSFORMER

    private val appContext = context.applicationContext
    private val mainHandler = Handler(Looper.getMainLooper())
    private val activeExports = ConcurrentHashMap<String, ActiveExport>()
    private val activeMcrawCancellation = ConcurrentHashMap<String, AtomicBoolean>()
    @Volatile private var projectRef: TimelineProject? = null

    private data class ActiveClip(
        val clip: TimelineClip,
        val track: TimelineTrack,
        val asset: MediaAsset
    )

    private data class ExportClipPlan(
        val clipId: String,
        val sourceUri: Uri,
        val sourceStartFrame: Long,
        val sourceEndFrameExclusive: Long,
        val startPositionUs: Long,
        val endPositionUs: Long,
        val adjustmentParams: VideoAdjustmentParams?,
        val asset: MediaAsset,
        val isImage: Boolean
    )

    private data class McrawIntermediateClip(
        val sourceUri: Uri,
        val startPositionUs: Long,
        val endPositionUs: Long
    )

    private data class ExportPlan(
        val clips: List<ExportClipPlan>,
        val outputFrameCount: Long,
        val includeAudio: Boolean,
        val hasHdrInput: Boolean
    )

    private data class ActiveExport(
        val transformer: Transformer,
        val listener: Transformer.Listener
    )

    override fun getCapabilities(): ExportBackendCapability = ExportBackendCapability(
        backendAvailable = true,
        // Media3 delegates codec choice to the Android device; hardware acceleration is not
        // guaranteed on every supported device, so do not advertise it as a promise.
        hardwareAccelerated = false,
        supportedCodecs = listOf(ExportCodec.H264),
        supportedContainers = listOf(ExportContainer.MP4),
        maxResolutionWidth = 3840,
        maxResolutionHeight = 2160,
        maxFps = 120f,
        supportsHDR = false,
        supportsAlpha = false,
        supportsAudioMix = false,
        // Supports sequential single-track compositions, but not overlays or multiple active tracks.
        supportsVideoComposition = true,
        supportsProRes = false,
        supportsAV1 = false,
        supports10Bit = false,
        supportsBatch = true,
        supportsCancel = true
    )

    override fun validate(settings: ExportSettings, project: TimelineProject): ExportValidationResult {
        projectRef = project
        val errors = collectValidationErrors(settings, project)
        return ExportValidationResult(
            isValid = errors.isEmpty(),
            unsupportedFeatures = errors,
            warnings = collectValidationWarnings(settings, project),
            errorMessage = errors.firstOrNull()
        )
    }

    override suspend fun prepare(job: ExportJob): Boolean {
        val project = projectRef ?: return false
        if (job.projectId != project.id || !validate(job.settings, project).isValid) return false
        val outputPath = job.outputUri ?: return false
        val output = File(outputPath)
        if (output.isDirectory) return false
        val parent = output.parentFile ?: return false
        if (!parent.exists() && !parent.mkdirs()) return false
        if (!parent.isDirectory || !parent.canWrite()) return false

        // Transformer creates/truncates its output itself. Remove only the target file, never a
        // parent directory; a unique timestamped name is used by the export UI.
        if (output.exists() && !output.delete()) return false
        return prepareLutData(project)
    }

    /**
     * The shared preview/export shader samples LUT textures from the repository cache. Load
     * every LUT referenced by the composed export params so exported pixels match preview
     * instead of silently dropping an uncached LUT; a missing LUT file fails the export.
     */
    private suspend fun prepareLutData(project: TimelineProject): Boolean {
        com.example.model.colorgrade.lut.LutRepository.initialize(appContext)
        val lutIds = project.tracks.asSequence()
            .filter { it.type == TrackType.VIDEO && it.isVisible }
            .flatMap { it.clips.asSequence() }
            .filter { it.isEnabled }
            .map { clip -> effectiveAdjustmentParams(clip).lutId }
            .filterNotNull()
            .distinct()
            .toList()
        for (lutId in lutIds) {
            if (com.example.model.colorgrade.lut.LutRepository.getCachedData(lutId) == null &&
                com.example.model.colorgrade.lut.LutRepository.loadLutData(lutId) == null
            ) {
                return false
            }
        }
        return true
    }

    override suspend fun render(job: ExportJob, onProgress: (ExportProgress) -> Unit) {
        val startedAtMs = System.currentTimeMillis()
        var activeExport: ActiveExport? = null
        var succeeded = false
        var outputFile: File? = null
        var mcrawTempDirectory: File? = null
        var totalFrames = 0L

        try {
            val project = projectRef ?: error("The project must be validated before export.")
            require(job.projectId == project.id) { "Export job project does not match the validated project." }
            val cancellationFlag = AtomicBoolean(false)
            activeMcrawCancellation[job.jobId] = cancellationFlag
            val renderJob = currentCoroutineContext()[Job]
            val isCancelled = { cancellationFlag.get() || renderJob?.isActive == false }
            val plan = createPlanOrThrow(job.settings, project)
            totalFrames = plan.outputFrameCount
            val targetOutputFile = File(job.outputUri ?: error("No output file was provided."))
            outputFile = targetOutputFile
            require(targetOutputFile.parentFile?.isDirectory == true) { "The export output directory is unavailable." }
            if (targetOutputFile.exists() && !targetOutputFile.delete()) {
                error("The existing output file could not be replaced.")
            }

            val mcrawSources = plan.clips.filter { MotionCamRawFormat.isMcrawAsset(it.asset) }
            val mcrawIntermediates = mutableMapOf<String, McrawIntermediateClip>()
            val mcrawProgressWeight = if (mcrawSources.isEmpty()) 0 else MCRAW_PREPARE_PROGRESS_PERCENT
            if (mcrawSources.isNotEmpty()) {
                mcrawTempDirectory = File(appContext.cacheDir, "mcraw-export-${UUID.randomUUID()}")
                require(mcrawTempDirectory?.mkdirs() == true) { "Could not create the temporary MotionCam export directory." }
                mcrawSources.forEachIndexed { sourceIndex, sourcePlan ->
                    if (isCancelled()) throw CancellationException("MotionCam RAW export was cancelled.")
                    val intermediateFile = File(mcrawTempDirectory!!, "clip-${sourceIndex + 1}.mp4")
                    val preparationStartPercent =
                        (sourceIndex.toDouble() / mcrawSources.size.toDouble() * mcrawProgressWeight)
                            .roundToLong().toInt().coerceIn(0, mcrawProgressWeight)
                    var lastPreparationPercent = preparationStartPercent
                    onProgress(
                        progress(
                            jobId = job.jobId,
                            status = ExportJobStatus.ENCODING,
                            percent = preparationStartPercent,
                            totalFrames = totalFrames,
                            startedAtMs = startedAtMs,
                            stage = "Developing MotionCam RAW"
                        )
                    )
                    val conversionResult = withContext(Dispatchers.IO) {
                        McrawVideoTranscoder(appContext).transcode(
                            sourceUri = sourcePlan.sourceUri,
                            outputFile = intermediateFile,
                            maximumEdge = min(
                                McrawVideoTranscoder.MAX_RENDER_EDGE,
                                maxOf(job.settings.resolutionWidth, job.settings.resolutionHeight)
                            ),
                            sourceStartFrame = sourcePlan.sourceStartFrame,
                            sourceEndFrameExclusive = sourcePlan.sourceEndFrameExclusive,
                            sourceStartUs = sourcePlan.startPositionUs,
                            sourceEndUs = sourcePlan.endPositionUs,
                            frameRate = Rational(job.settings.frameRate.numerator, job.settings.frameRate.denominator),
                            bitrate = job.settings.videoBitrate,
                            isCancelled = isCancelled
                        ) { completedFrames, sourceFrameCount ->
                            val sourceFraction = if (sourceFrameCount > 0) {
                                completedFrames.toDouble() / sourceFrameCount.toDouble()
                            } else {
                                0.0
                            }
                            val totalFraction = (sourceIndex + sourceFraction) / mcrawSources.size.toDouble()
                            val percent = (totalFraction * mcrawProgressWeight).roundToLong().toInt()
                                .coerceIn(0, mcrawProgressWeight)
                            if (percent != lastPreparationPercent) {
                                lastPreparationPercent = percent
                                onProgress(
                                    progress(
                                        jobId = job.jobId,
                                        status = ExportJobStatus.ENCODING,
                                        percent = percent,
                                        totalFrames = totalFrames,
                                        startedAtMs = startedAtMs,
                                        stage = "Developing MotionCam RAW"
                                    )
                                )
                            }
                        }
                    }
                    if (isCancelled()) throw CancellationException("MotionCam RAW export was cancelled.")
                    mcrawIntermediates[sourcePlan.clipId] = McrawIntermediateClip(
                        sourceUri = Uri.fromFile(intermediateFile),
                        startPositionUs = conversionResult.trimStartUs,
                        endPositionUs = conversionResult.trimEndUs
                    )
                }
            }

            val completion = CompletableDeferred<Throwable?>()
            val listener = object : Transformer.Listener {
                override fun onCompleted(composition: Composition, exportResult: ExportResult) {
                    completion.complete(null)
                }

                override fun onError(
                    composition: Composition,
                    exportResult: ExportResult,
                    exportException: ExportException
                ) {
                    completion.complete(exportException)
                }
            }

            val editedItems = plan.clips.map { clipPlan ->
                if (isCancelled()) throw CancellationException("Export was cancelled.")
                val mcrawIntermediate = mcrawIntermediates[clipPlan.clipId]
                val exportSourceUri = mcrawIntermediate?.sourceUri ?: clipPlan.sourceUri
                val exportStartUs = mcrawIntermediate?.startPositionUs ?: clipPlan.startPositionUs
                val exportEndUs = mcrawIntermediate?.endPositionUs ?: clipPlan.endPositionUs
                val sourceItemBuilder = MediaItem.Builder().setUri(exportSourceUri)
                if (clipPlan.isImage) {
                    sourceItemBuilder.setImageDurationMs(
                        (Math.subtractExact(exportEndUs, exportStartUs) / 1_000L).coerceAtLeast(1L)
                    )
                } else {
                    sourceItemBuilder.setClippingConfiguration(
                        MediaItem.ClippingConfiguration.Builder()
                            .setStartPositionUs(exportStartUs)
                            .setEndPositionUs(exportEndUs)
                            .build()
                    )
                }
                val sourceItem = sourceItemBuilder.build()
                val videoEffects = buildList<Effect> {
                    clipPlan.adjustmentParams?.let { add(createMedia3ClipAdjustmentEffect(it)) }
                    add(
                        Presentation.createForWidthAndHeight(
                            job.settings.resolutionWidth,
                            job.settings.resolutionHeight,
                            Presentation.LAYOUT_SCALE_TO_FIT
                        )
                    )
                }
                val editedItemBuilder = EditedMediaItem.Builder(sourceItem)
                    .setRemoveAudio(!plan.includeAudio || clipPlan.isImage)
                    .setEffects(Effects(emptyList(), videoEffects))
                if (clipPlan.isImage) {
                    editedItemBuilder
                        .setDurationUs(Math.subtractExact(exportEndUs, exportStartUs).coerceAtLeast(1L))
                        .setFrameRate(job.settings.frameRate.floatValue.roundToInt().coerceAtLeast(1))
                }
                editedItemBuilder.build()
            }
            val composition = Composition.Builder(
                EditedMediaItemSequence.Builder(editedItems).build()
            )
                .setHdrMode(hdrModeForExport(plan.hasHdrInput))
                .build()

            val videoBitrateMode = when (job.settings.bitrateMode) {
                ExportSettings.BitrateMode.CBR -> android.media.MediaCodecInfo.EncoderCapabilities.BITRATE_MODE_CBR
                ExportSettings.BitrateMode.AUTO,
                ExportSettings.BitrateMode.VBR -> android.media.MediaCodecInfo.EncoderCapabilities.BITRATE_MODE_VBR
                ExportSettings.BitrateMode.CRF -> error("CRF bitrate mode is not supported by this Android encoder adapter.")
            }
            val videoEncoderSettings = VideoEncoderSettings.Builder()
                .setBitrate(job.settings.videoBitrate)
                .setBitrateMode(videoBitrateMode)
                .setiFrameIntervalSeconds(job.settings.keyframeInterval.toFloat())
                .build()
            val encoderFactoryBuilder = DefaultEncoderFactory.Builder(appContext)
                .setRequestedVideoEncoderSettings(videoEncoderSettings)
            if (plan.includeAudio) {
                encoderFactoryBuilder.setRequestedAudioEncoderSettings(
                    AudioEncoderSettings.Builder().setBitrate(job.settings.audioBitrate).build()
                )
            }
            val encoderFactory = encoderFactoryBuilder.build()

            onProgress(
                progress(
                    jobId = job.jobId,
                    status = ExportJobStatus.ENCODING,
                    percent = mcrawProgressWeight,
                    totalFrames = totalFrames,
                    startedAtMs = startedAtMs,
                    stage = if (mcrawSources.isEmpty()) "Preparing Android encoder" else "Preparing final MP4 encode"
                )
            )

            val transformer = withContext(Dispatchers.Main.immediate) {
                val builtTransformer = Transformer.Builder(appContext)
                    .setVideoMimeType(MimeTypes.VIDEO_H264)
                    .apply {
                        if (plan.includeAudio) setAudioMimeType(MimeTypes.AUDIO_AAC)
                    }
                    .setEncoderFactory(encoderFactory)
                    .addListener(listener)
                    .build()
                val active = ActiveExport(builtTransformer, listener)
                activeExports[job.jobId] = active
                activeExport = active
                builtTransformer.start(composition, targetOutputFile.absolutePath)
                builtTransformer
            }

            val progressHolder = ProgressHolder()
            var lastReportedPercent = -1
            while (!completion.isCompleted) {
                currentCoroutineContext().ensureActive()
                delay(PROGRESS_POLL_INTERVAL_MS)
                val progressState = withContext(Dispatchers.Main.immediate) {
                    transformer.getProgress(progressHolder)
                }
                if (progressState == Transformer.PROGRESS_STATE_AVAILABLE) {
                    val transformerPercent = progressHolder.progress.coerceIn(0, 99)
                    val percent = mcrawProgressWeight +
                        ((100 - mcrawProgressWeight) * transformerPercent / 100f).roundToLong().toInt()
                    val boundedPercent = percent.coerceIn(mcrawProgressWeight, 99)
                    if (boundedPercent != lastReportedPercent) {
                        lastReportedPercent = boundedPercent
                        onProgress(
                            progress(
                                jobId = job.jobId,
                                status = ExportJobStatus.ENCODING,
                                percent = boundedPercent,
                                totalFrames = totalFrames,
                                startedAtMs = startedAtMs,
                                stage = "Encoding MP4"
                            )
                        )
                    }
                }
            }

            val exportFailure = completion.await()
            if (exportFailure != null) throw exportFailure
            if (!outputFile.isFile || outputFile.length() <= 0L) {
                error("Media3 completed without producing a non-empty MP4 file.")
            }
            verifyPlayableMp4(outputFile, plan.includeAudio)

            succeeded = true
            onProgress(
                progress(
                    jobId = job.jobId,
                    status = ExportJobStatus.COMPLETED,
                    percent = 100,
                    totalFrames = totalFrames,
                    startedAtMs = startedAtMs,
                    stage = "MP4 export complete"
                )
            )
        } catch (cancelled: CancellationException) {
            outputFile?.delete()
            throw cancelled
        } catch (failure: Exception) {
            outputFile?.delete()
            onProgress(
                ExportProgress(
                    jobId = job.jobId,
                    status = ExportJobStatus.FAILED,
                    progressPercent = 0f,
                    renderedFrames = 0L,
                    totalFrames = totalFrames,
                    elapsedMs = System.currentTimeMillis() - startedAtMs,
                    estimatedRemainingMs = 0L,
                    currentStage = "Export failed",
                    errorMessage = describeTransformerFailure(failure)
                )
            )
        } finally {
            val active = activeExport ?: activeExports[job.jobId]
            activeExports.remove(job.jobId)
            activeMcrawCancellation.remove(job.jobId)
            withContext(NonCancellable + Dispatchers.Main.immediate) {
                active?.let {
                    if (!succeeded) runCatching { it.transformer.cancel() }
                    runCatching { it.transformer.removeListener(it.listener) }
                }
            }
            mcrawTempDirectory?.deleteRecursively()
            if (!succeeded) outputFile?.delete()
        }
    }

    override fun cancel(jobId: String) {
        activeMcrawCancellation[jobId]?.set(true)
        val active = activeExports[jobId] ?: return
        if (Looper.myLooper() == Looper.getMainLooper()) {
            runCatching { active.transformer.cancel() }
        } else {
            mainHandler.post { runCatching { active.transformer.cancel() } }
        }
    }

    override fun release() {
        activeMcrawCancellation.values.forEach { it.set(true) }
        activeExports.keys.toList().forEach(::cancel)
        projectRef = null
    }

    private fun verifyPlayableMp4(outputFile: File, expectAudio: Boolean) {
        val extractor = MediaExtractor()
        try {
            extractor.setDataSource(outputFile.absolutePath)
            val trackFormats = (0 until extractor.trackCount).map(extractor::getTrackFormat)
            val videoFormats = trackFormats.filter {
                it.getString(MediaFormat.KEY_MIME)?.startsWith("video/") == true
            }
            check(videoFormats.size == 1) {
                "Media3 reported success, but the MP4 does not contain exactly one readable video track."
            }
            val videoFormat = videoFormats.single()
            check(videoFormat.getInteger(MediaFormat.KEY_WIDTH) > 0 && videoFormat.getInteger(MediaFormat.KEY_HEIGHT) > 0) {
                "The exported MP4 video track has invalid dimensions."
            }
            check(!videoFormat.containsKey(MediaFormat.KEY_DURATION) || videoFormat.getLong(MediaFormat.KEY_DURATION) > 0L) {
                "The exported MP4 video track has no usable duration."
            }
            val videoTrackIndex = trackFormats.indexOf(videoFormat)
            extractor.selectTrack(videoTrackIndex)
            check(hasReadableSample(extractor, videoFormat)) {
                "The exported MP4 video track contains no readable encoded samples."
            }
            if (expectAudio) {
                val audioTrackIndex = trackFormats.indexOfFirst {
                    it.getString(MediaFormat.KEY_MIME)?.startsWith("audio/") == true
                }
                check(audioTrackIndex >= 0) {
                    "Audio was requested, but the exported MP4 contains no readable audio track."
                }
                extractor.unselectTrack(videoTrackIndex)
                val audioFormat = trackFormats[audioTrackIndex]
                extractor.selectTrack(audioTrackIndex)
                check(hasReadableSample(extractor, audioFormat)) {
                    "Audio was requested, but the exported MP4 audio track contains no readable samples."
                }
            }
        } catch (failure: Exception) {
            throw IllegalStateException(
                failure.message?.takeIf(String::isNotBlank)
                    ?: "The output cannot be reopened as a playable MP4.",
                failure
            )
        } finally {
            extractor.release()
        }
    }

    private fun describeTransformerFailure(failure: Throwable): String = buildString {
        if (failure is ExportException) {
            append(failure.errorCodeName)
            append(" (code ")
            append(failure.errorCode)
            append(')')
        }
        failure.message?.takeIf(String::isNotBlank)?.let { message ->
            if (isNotEmpty()) append(": ")
            append(message)
        }
        val causeMessage = failure.cause?.message?.takeIf(String::isNotBlank)
        if (causeMessage != null && causeMessage != failure.message) {
            if (isNotEmpty()) append(" — ")
            append(causeMessage)
        }
        if (isEmpty()) append(failure.javaClass.simpleName)
    }.take(MAX_FAILURE_MESSAGE_LENGTH)

    private fun hasReadableSample(extractor: MediaExtractor, format: MediaFormat): Boolean {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.P) return extractor.sampleSize > 0L
        val maximumInputSize = runCatching { format.getInteger(MediaFormat.KEY_MAX_INPUT_SIZE) }
            .getOrDefault(0)
            .coerceAtLeast(0)
        val bufferCapacity = maxOf(MIN_SAMPLE_BUFFER_BYTES, maximumInputSize)
            .coerceAtMost(MAX_SAMPLE_BUFFER_BYTES)
        return runCatching {
            extractor.readSampleData(ByteBuffer.allocateDirect(bufferCapacity), 0) > 0
        }.getOrDefault(false)
    }

    private fun collectValidationErrors(settings: ExportSettings, project: TimelineProject): List<String> {
        val errors = mutableListOf<String>()
        val capabilities = getCapabilities()

        if (!capabilities.backendAvailable) errors += "Media3 Transformer is unavailable."
        if (settings.outputFileName.isBlank()) errors += "Enter a file name for the exported MP4."
        if (settings.codec != ExportCodec.H264) errors += "This export path supports H.264 video only."
        if (settings.container != ExportContainer.MP4) errors += "This export path supports MP4 containers only."
        if (settings.resolutionWidth <= 0 || settings.resolutionHeight <= 0 ||
            settings.resolutionWidth % 2 != 0 || settings.resolutionHeight % 2 != 0
        ) {
            errors += "H.264 output dimensions must be positive, even pixel dimensions."
        }
        if (!isWithinMedia3ResolutionLimit(
                settings.resolutionWidth,
                settings.resolutionHeight,
                maximumLongEdge = capabilities.maxResolutionWidth,
                maximumShortEdge = capabilities.maxResolutionHeight
            )
        ) {
            errors += "The requested output resolution exceeds the supported adapter limit (4K long edge, 2160-pixel short edge)."
        }
        if (settings.frameRate.numerator <= 0 || settings.frameRate.denominator <= 0 ||
            !settings.frameRate.floatValue.isFinite() || settings.frameRate.floatValue > capabilities.maxFps
        ) {
            errors += "The requested output frame rate is invalid or exceeds the adapter limit."
        }
        if (settings.videoBitrate <= 0) errors += "The target video bitrate must be positive."
        if (settings.maxBitrate <= 0) errors += "The maximum video bitrate must be positive."
        else if (settings.maxBitrate != settings.videoBitrate) {
            errors += "This export path exposes one target bitrate only; set maximum bitrate equal to the target bitrate."
        }
        if (settings.includeAudio) {
            if (settings.audioBitrate <= 0) errors += "The target audio bitrate must be positive."
            if (settings.audioSampleRate <= 0) errors += "The AAC sample rate must be positive."
            if (settings.audioChannels !in 1..2) errors += "This export path supports mono or stereo AAC output only."
        }
        if (settings.keyframeInterval !in 1..120) {
            errors += "The H.264 keyframe interval must be between 1 and 120 seconds."
        }
        if (settings.bitrateMode == ExportSettings.BitrateMode.CRF) {
            errors += "CRF bitrate mode is not supported by this Android encoder adapter."
        }
        if (settings.profile != null || settings.level != null) {
            errors += "Custom H.264 profile and level selection is not supported by this export path."
        }
        if (!settings.useOriginalMedia || settings.allowProxyExport) {
            errors += "This export path reads original media only; proxy selection is not implemented."
        }
        if (settings.renderAlpha) errors += "H.264 MP4 export does not support the requested alpha channel."
        if (settings.exportHDR || settings.colorOutput != com.example.timeline.export.model.ExportColorSettings.ColorOutput.Rec709 ||
            settings.toneMappingMode != com.example.timeline.export.model.ExportColorSettings.ToneMappingMode.STANDARD
        ) {
            errors += "This first export path supports Rec.709 SDR output only."
        }
        if (settings.includeWatermark) errors += "Watermark compositing is not supported by this export path yet."
        if (settings.includeAudio && settings.audioCodec != com.example.timeline.export.model.ExportAudioSettings.AudioCodec.AAC) {
            errors += "This export path supports AAC audio only."
        }
        if (settings.includeAudio && settings.normalizeAudio) errors += "Audio normalization is not supported by this export path yet."

        val activeVideoTracks = project.tracks.filter { track ->
            track.type == TrackType.VIDEO && track.isVisible && track.clips.any { it.isEnabled }
        }
        if (activeVideoTracks.size != 1) {
            errors += "Exactly one enabled visible video track is supported by this export path."
        }
        val activeVideoTrack = activeVideoTracks.singleOrNull()
        val enabledVideoClips = activeVideoTrack?.clips?.filter { it.isEnabled }.orEmpty()
        if (enabledVideoClips.isEmpty()) {
            errors += "At least one enabled clip on a visible video track is required."
        }
        val activeClips = enabledVideoClips.mapNotNull { clip ->
            val asset = project.mediaAssets.firstOrNull { it.assetId == clip.mediaId }
            asset?.let { ActiveClip(clip, activeVideoTrack!!, it) }
        }
        if (activeClips.size != enabledVideoClips.size && enabledVideoClips.isNotEmpty()) {
            errors += "One or more active video clips are missing their imported media asset."
        }

        val orderedClips = activeClips.sortedBy { it.clip.timelineStart }
        if (orderedClips.isNotEmpty()) {
            if (orderedClips.first().clip.timelineStart != 0L) {
                errors += "The supported clip must start at timeline frame zero (the first enabled video clip)."
            }
            var expectedTimelineStart = 0L
            orderedClips.forEach { active ->
                val clip = active.clip
                if (clip.timelineStart > expectedTimelineStart) {
                    errors += "Video gaps are not supported by Media3; use Fix automatically to close the gap."
                } else if (clip.timelineStart < expectedTimelineStart) {
                    errors += "Overlapping clips, crossfades, and other NLE transitions are not supported; this export path is cut-only."
                }
                expectedTimelineStart = maxOf(expectedTimelineStart, clip.timelineEnd)
            }
        }

        val visibleAudioClips = project.tracks
            .filter { it.type == TrackType.AUDIO && !it.isMuted }
            .flatMap { it.clips.filter { clip -> clip.isEnabled } }
        if (settings.includeAudio && visibleAudioClips.isNotEmpty()) {
            errors += "Separate audio-track clips and audio mixes are not supported when audio is included; turn off Include audio to export a silent video."
        }
        if (project.tracks.any { track ->
                track.type !in setOf(TrackType.VIDEO, TrackType.AUDIO) && track.clips.any { it.isEnabled }
            }
        ) {
            errors += "Text, adjustment, nested-sequence, and overlay tracks are not supported by this export path."
        }

        if (project.settings.colorSpace !in setOf(ProjectColorSpace.REC_709, ProjectColorSpace.AUTOMATIC) ||
            project.settings.workingColorSpace !in setOf(ProjectColorSpace.REC_709, ProjectColorSpace.AUTOMATIC) ||
            project.settings.bitDepthPreference > 8
        ) {
            errors += "This export path supports an 8-bit Rec.709 SDR project only."
        }

        val projectFps = project.settings.getFpsRational()
        if (projectFps.numerator <= 0 || projectFps.denominator <= 0) {
            errors += "The project frame rate must be valid before exporting."
        }

        var embeddedAudioPresent: Boolean? = null
        val audioFormats = mutableSetOf<Pair<Int, Int>>()
        activeClips.forEach { active ->
            val (clip, track, asset) = active
            val isMcraw = MotionCamRawFormat.isMcrawAsset(asset)
            val isImage = asset.assetType == com.example.timeline.media.MediaAssetType.IMAGE ||
                asset.mimeType.startsWith("image/")
            if (clip.type != ClipType.MEDIA) errors += "Only regular media clips can be exported by this path."
            if (clip.duration <= 0L) errors += "An active video clip has no duration."
            if (!isImage && (!asset.metadata.hasVideo || asset.assetType != com.example.timeline.media.MediaAssetType.VIDEO)) {
                errors += "Every active timeline clip must reference imported video media or a supported still image."
            }
            if (asset.metadata.durationUs <= 0L) errors += "An imported video or still image has no usable duration metadata."
            if (isMcraw) {
                val sourceFrameCount = asset.metadata.durationFramesInSourceRate
                val trimRange = runCatching {
                    framesToUs(clip.sourceIn, projectFps) to framesToUs(clip.sourceOut, projectFps)
                }.getOrNull()
                val sourceFrameRate = asset.metadata.exactFrameRate ?: asset.metadata.estimatedFrameRate
                val sourceFrameRange = if (trimRange != null && sourceFrameRate != null) {
                    mcrawFrameRangeForTimeUs(trimRange.first, trimRange.second, sourceFrameRate)
                } else {
                    null
                }
                if (sourceFrameCount <= 0L || sourceFrameRange == null ||
                    sourceFrameRange.startFrame < 0L || sourceFrameRange.endFrameExclusive > sourceFrameCount
                ) {
                    errors += "The selected MotionCam time range cannot be mapped to source frames inside the decoded recording."
                }
                val rawPixelCount = asset.metadata.width.toLong() * asset.metadata.height.toLong()
                if (asset.metadata.width < 2 || asset.metadata.height < 2 ||
                    rawPixelCount > McrawVideoTranscoder.MAX_SOURCE_RAW_PIXELS
                ) {
                    errors += "MotionCam RAW frames above 24 megapixels or with invalid dimensions are blocked to bound export memory."
                }
                val maximumEdge = min(
                    McrawVideoTranscoder.MAX_RENDER_EDGE,
                    maxOf(settings.resolutionWidth, settings.resolutionHeight)
                )
                val intermediateSize = mcrawRenderSize(
                    sourceWidth = asset.metadata.width,
                    sourceHeight = asset.metadata.height,
                    rotationDegrees = asset.metadata.rotationDegrees,
                    maximumEdge = maximumEdge
                )
                if (maximumEdge < 64 || intermediateSize == null) {
                    errors += "MotionCam RAW dimensions or rotation cannot be rendered to an even-sized H.264 frame."
                } else if (!codecSupportProbe.hasMcrawIntermediateEncoder(
                        intermediateSize.width,
                        intermediateSize.height,
                        settings.frameRate.floatValue,
                        settings.videoBitrate
                    )
                ) {
                    errors += "No Android H.264 encoder with flexible YUV input supports the MotionCam intermediate frame size, frame rate, and bitrate on this device."
                }
                if (settings.includeAudio && !track.isMuted && asset.metadata.hasAudio) {
                    errors += "MotionCam RAW embedded PCM audio is not connected to the H.264/MP4 export path yet; turn off Include audio to export its video."
                }
            }
            if (!isImage && asset.metadata.isVariableFrameRate) {
                errors += "Variable-frame-rate media cannot be conformed to project FPS by this Media3 path yet; export is blocked and the original remains unchanged."
            } else if (!isImage && !asset.metadata.variableFrameRateDetectionComplete) {
                errors += "Video timestamp cadence is unverified; reanalyze the source before export."
            }
            // MotionCam metadata describes high-bit-depth Bayer sensor samples, not an encoded
            // HDR/log video track. Its explicit bridge develops those samples to 8-bit SDR before
            // Media3 sees them, so the ordinary encoded-video transfer checks do not apply here.
            if (!isMcraw) {
                if (asset.metadata.videoStreams.any { it.isLog }) {
                    errors += "Log-encoded source video is not supported by this Rec.709 export path; convert the source to SDR first."
                }
                if (asset.metadata.videoStreams.any { it.isHdr } && Build.VERSION.SDK_INT < Build.VERSION_CODES.Q) {
                    errors += "HDR-to-SDR export requires Android 10 or newer on this Media3 path."
                }
                if (asset.metadata.videoStreams.any {
                        (it.bitDepth ?: 8) > 8 && !it.isHdr &&
                            it.colorTransfer != MediaFormat.COLOR_TRANSFER_SDR_VIDEO
                    }
                ) {
                    errors += "A greater-than-8-bit source has no verified SDR transfer metadata; export is blocked to avoid incorrect color conversion."
                }
            }
            if (clip.transform != ClipTransform.defaultTransform()) {
                errors += "Clip transforms, crops, perspective changes, and transform keyframes are not supported by this path yet."
            }
            if (settings.includeAudio && clip.volume != 1f && !track.isMuted) {
                errors += "Per-clip volume changes are not supported by this export path yet."
            }
            val effectiveAdjustmentParams = effectiveAdjustmentParams(clip)
            if (clip.adjustments.enabled) {
                errors += Media3ClipAdjustmentSupport.validationErrors(
                    clip.adjustments.copy(params = effectiveAdjustmentParams)
                )
            } else if (Media3ClipAdjustmentSupport.hasRenderableAdjustments(effectiveAdjustmentParams)) {
                errors += Media3ClipAdjustmentSupport.validationErrors(effectiveAdjustmentParams)
            }

            // The shared composer renders primary corrections, curves, LUTs, skin-tone
            // protection, qualifiers, selective HSL, grain, vignette, and channel mixing through
            // the same effect as preview. What cannot be represented by the static per-clip
            // parameter block is rejected here instead of being silently dropped.
            if (clip.colorGrade.keyframes.isNotEmpty()) {
                errors += "Animated clip color grades are not supported by this export path yet."
            }
            if (clip.colorGrade.blendMode != "NORMAL") {
                errors += "Clip color grade blend modes are not supported by this export path yet."
            }
            if (clip.colorGrade.colorMatch != ColorMatchParams()) {
                errors += "Color Match is not supported by this export path yet."
            }
            clip.colorLayers.layers.filter { it.enabled }.forEach { layer ->
                if (layer.grade.keyframes.isNotEmpty()) {
                    errors += "Animated color nodes are not supported by this export path yet."
                }
                if (layer.grade.blendMode != "NORMAL") {
                    errors += "Color node blend modes are not supported by this export path yet."
                }
                if (layer.grade.colorMatch != ColorMatchParams()) {
                    errors += "Color Match on color nodes is not supported by this export path yet."
                }
            }

            if (clip.sourceIn < 0L || clip.sourceOut < 0L) {
                errors += "Clip trim ranges cannot use negative source frames."
            } else if (projectFps.numerator > 0 && projectFps.denominator > 0) {
                val trimRange = runCatching {
                    framesToUs(clip.sourceIn, projectFps) to framesToUs(clip.sourceOut, projectFps)
                }.getOrNull()
                if (trimRange == null) {
                    errors += "A clip trim range is too large to represent safely."
                } else {
                    val (sourceInUs, sourceOutUs) = trimRange
                    val frameToleranceUs = TimelineFrameMath.microsecondsFromFrames(1L, projectFps)
                        ?.coerceAtLeast(1L)
                    val maximumSourceUs = frameToleranceUs?.let { tolerance ->
                        try {
                            Math.addExact(asset.metadata.durationUs, tolerance)
                        } catch (_: ArithmeticException) {
                            Long.MAX_VALUE
                        }
                    }
                    if (frameToleranceUs == null || maximumSourceUs == null || sourceOutUs <= sourceInUs ||
                        sourceOutUs > maximumSourceUs
                    ) {
                        errors += "A clip trim range is outside its imported video's duration."
                    }
                }
            }

            val expectedSourceFrameRate = asset.metadata.exactFrameRate ?: asset.metadata.estimatedFrameRate
            if (!isImage && (expectedSourceFrameRate == null || !frameRatesMatch(expectedSourceFrameRate, settings.frameRate))) {
                errors += "Media3 preserves each source frame rate; set export FPS to match every constant-frame-rate source."
            }
            val sourceUriString = asset.localOriginalUriString?.takeIf { it.isNotBlank() } ?: asset.originalUriString
            if (sourceUriString.isBlank()) errors += "An imported video or still image has no readable source URI."

            if (!isImage && settings.includeAudio && !track.isMuted) {
                if (embeddedAudioPresent == null) {
                    embeddedAudioPresent = asset.metadata.hasAudio
                } else if (embeddedAudioPresent != asset.metadata.hasAudio) {
                    errors += "All clips on the exported video sequence must either contain embedded audio or be muted."
                }
                if (asset.metadata.hasAudio && !isMcraw) {
                    if (asset.metadata.audioStreams.size != 1) {
                        errors += "Exactly one embedded audio stream per clip is supported; separate audio editing is not supported."
                    } else {
                        val audio = asset.metadata.audioStreams.single()
                        audioFormats += (audio.sampleRate to audio.channelCount)
                        if (audio.sampleRate != settings.audioSampleRate || audio.channelCount != settings.audioChannels) {
                            errors += "For this export path, AAC sample rate and channels must match every source clip."
                        }
                        if (audio.channelCount !in 1..2) {
                            errors += "Only mono or stereo embedded audio is supported by this export path."
                        }
                    }
                }
            }
        }
        if (settings.includeAudio && audioFormats.size > 1) {
            errors += "Embedded audio formats must use the same sample rate and channel count across all clips; turn off Include audio to export a silent video."
        }
        val includeAudio = shouldIncludeEmbeddedAudio(
            settings = settings,
            trackMuted = activeVideoTrack?.isMuted ?: true,
            clipHasAudio = activeClips.filterNot { active ->
                active.asset.assetType == com.example.timeline.media.MediaAssetType.IMAGE ||
                    active.asset.mimeType.startsWith("image/")
            }.map { it.asset.metadata.hasAudio }
        )
        if (settings.includeAudio && activeVideoTrack?.isMuted == false && activeClips.isNotEmpty() &&
            activeClips.filterNot { active ->
                active.asset.assetType == com.example.timeline.media.MediaAssetType.IMAGE ||
                    active.asset.mimeType.startsWith("image/")
            }.none { it.asset.metadata.hasAudio }
        ) {
            errors += "No embedded audio is available; turn off Include audio to export a silent video."
        }
        if (includeAudio && !codecSupportProbe.hasAudioEncoder(settings.audioSampleRate, settings.audioChannels, settings.audioBitrate)) {
            errors += "No Android AAC encoder supports the requested source audio format; turn off Include audio to export a silent video."
        }
        if (activeClips.isNotEmpty() && !codecSupportProbe.hasVideoEncoder(settings, requireHardware = false)) {
            errors += "No Android H.264 encoder supports the requested resolution, frame rate, bitrate, and bitrate mode. Try a lower resolution or bitrate."
        }

        return errors.distinct()
    }

    private fun collectValidationWarnings(settings: ExportSettings, project: TimelineProject): List<String> {
        val warnings = mutableListOf<String>()
        val anyVideoEncoder = codecSupportProbe.hasVideoEncoder(settings, requireHardware = false)
        val hardwareVideoEncoder = codecSupportProbe.hasVideoEncoder(settings, requireHardware = true)
        if (settings.useHardwareEncoder && anyVideoEncoder && !hardwareVideoEncoder) {
            warnings += "No matching hardware H.264 encoder was detected; Media3 may use a compatible software encoder, which can be slower."
        } else if (settings.useHardwareEncoder && hardwareVideoEncoder) {
            warnings += "Media3 selects the Android H.264 encoder; hardware acceleration remains best-effort."
        } else if (!settings.useHardwareEncoder && anyVideoEncoder) {
            warnings += "Media3 still selects the compatible Android H.264 encoder; this setting cannot force software-only encoding."
        }
        val activeAssets = project.tracks
            .filter { it.type == TrackType.VIDEO && it.isVisible }
            .flatMap { track -> track.clips.filter { it.isEnabled } }
            .mapNotNull { clip -> project.mediaAssets.firstOrNull { it.assetId == clip.mediaId } }
        if (activeAssets.any { it.metadata.exactFrameRate == null && it.metadata.estimatedFrameRate != null }) {
            warnings += "One or more source frame rates are estimated from import metadata; verify they are constant-frame-rate."
        }
        if (activeAssets.any { asset -> asset.metadata.videoStreams.any { it.isHdr } }) {
            warnings += "HDR source footage will be tone-mapped to Rec.709 SDR; the exported H.264 file will not preserve HDR."
        }
        if (activeAssets.any { MotionCamRawFormat.isMcrawAsset(it) }) {
            warnings += "MotionCam RAW is developed by the app's lightweight Bayer renderer to 8-bit SDR before H.264 encoding; this is not a full camera ISP render or a 10-bit RAW-preserving export."
        }
        if (activeAssets.any { asset ->
                !MotionCamRawFormat.isMcrawAsset(asset) &&
                    asset.metadata.videoStreams.any { (it.bitDepth ?: 8) > 8 && !it.isHdr }
            }
        ) {
            warnings += "Greater-than-8-bit SDR input will be converted to 8-bit H.264 output."
        }
        if (activeAssets.size > 1) {
            warnings += "Video clips are exported as a sequential, cut-only Media3 composition after timeline gaps are closed."
            warnings += "Crossfades and other NLE transitions are not implemented by this export path."
        }
        return warnings
    }

    private fun createPlanOrThrow(settings: ExportSettings, project: TimelineProject): ExportPlan {
        val errors = collectValidationErrors(settings, project)
        require(errors.isEmpty()) { errors.joinToString("\n") }
        val track = project.tracks
            .filter { it.type == TrackType.VIDEO && it.isVisible && it.clips.any { clip -> clip.isEnabled } }
            .single()
        val projectFps = project.settings.getFpsRational()
        val enabledClips = track.clips.filter { it.isEnabled }.sortedBy { it.timelineStart }
        val clipPlans = enabledClips.map { timelineClip ->
            val asset = project.mediaAssets.first { it.assetId == timelineClip.mediaId }
            val sourceUriString = asset.localOriginalUriString?.takeIf { it.isNotBlank() } ?: asset.originalUriString
            val parsedUri = Uri.parse(sourceUriString)
            val sourceUri = if (parsedUri.scheme == null) Uri.fromFile(File(sourceUriString)) else parsedUri
            val startPositionUs = framesToUs(timelineClip.sourceIn, projectFps)
            val endPositionUs = framesToUs(timelineClip.sourceOut, projectFps)
            val sourceFrameRange = if (MotionCamRawFormat.isMcrawAsset(asset)) {
                val sourceFrameRate = asset.metadata.exactFrameRate ?: asset.metadata.estimatedFrameRate
                    ?: error("MotionCam source frame rate is unavailable.")
                mcrawFrameRangeForTimeUs(startPositionUs, endPositionUs, sourceFrameRate)
                    ?: error("MotionCam clip time range cannot be mapped to source frames.")
            } else {
                SourceFrameRange(timelineClip.sourceIn, timelineClip.sourceOut)
            }
            val isImage = asset.assetType == com.example.timeline.media.MediaAssetType.IMAGE ||
                asset.mimeType.startsWith("image/")
            ExportClipPlan(
                clipId = timelineClip.id,
                sourceUri = sourceUri,
                sourceStartFrame = sourceFrameRange.startFrame,
                sourceEndFrameExclusive = sourceFrameRange.endFrameExclusive,
                startPositionUs = if (isImage) 0L else startPositionUs,
                endPositionUs = if (isImage) framesToUs(timelineClip.duration, projectFps) else endPositionUs,
                adjustmentParams = effectiveAdjustmentParams(timelineClip).takeIf { params ->
                    Media3ClipAdjustmentSupport.hasRenderableAdjustments(params)
                },
                asset = asset,
                isImage = isImage
            )
        }
        val totalDurationUs = clipPlans.fold(0L) { totalUs, clip ->
            val clipDurationUs = Math.subtractExact(clip.endPositionUs, clip.startPositionUs)
            Math.addExact(totalUs, clipDurationUs)
        }
        val outputFrames = framesAtRate(totalDurationUs, settings.frameRate)
        check(outputFrames > 0L) { "Validated export duration must produce at least one output frame." }
        val includeAudio = shouldIncludeEmbeddedAudio(
            settings = settings,
            trackMuted = track.isMuted,
            clipHasAudio = enabledClips.map { clip ->
                project.mediaAssets.first { it.assetId == clip.mediaId }.metadata.hasAudio
            }
        )
        return ExportPlan(
            clips = clipPlans,
            outputFrameCount = outputFrames,
            includeAudio = includeAudio,
            hasHdrInput = enabledClips.any { clip ->
                project.mediaAssets.first { it.assetId == clip.mediaId }.metadata.videoStreams.any { it.isHdr }
            }
        )
    }

    /**
     * Export and preview must render the same pixels, so the export plan evaluates each clip
     * through the shared `GradeEvaluationEngine.evaluateClipGrade` composer: the clip's keyframed
     * adjustment block, its persisted clip grade (including legacy unmirrored values), and its
     * enabled color nodes in order, at the clip's first evaluated frame.
     */
    private fun effectiveAdjustmentParams(clip: TimelineClip): VideoAdjustmentParams =
        GradeEvaluationEngine.evaluateClipGrade(clip, frame = clip.sourceIn.coerceAtLeast(0L))

    private fun frameRatesMatch(sourceRate: FrameRate, exportRate: com.example.timeline.export.model.ExportFrameRate): Boolean {
        if (sourceRate.numerator <= 0 || sourceRate.denominator <= 0 ||
            exportRate.numerator <= 0 || exportRate.denominator <= 0
        ) return false
        val sourceScaled = BigInteger.valueOf(sourceRate.numerator.toLong())
            .multiply(BigInteger.valueOf(exportRate.denominator.toLong()))
        val exportScaled = BigInteger.valueOf(exportRate.numerator.toLong())
            .multiply(BigInteger.valueOf(sourceRate.denominator.toLong()))
        val denominator = BigInteger.valueOf(sourceRate.denominator.toLong())
            .multiply(BigInteger.valueOf(exportRate.denominator.toLong()))
        // Accept at most 0.01 fps difference, compared exactly as rational values.
        return sourceScaled.subtract(exportScaled).abs().multiply(BigInteger.valueOf(100L)) <= denominator
    }

    private fun framesToUs(frames: Long, frameRate: Rational): Long {
        require(frames >= 0L && frameRate.numerator > 0 && frameRate.denominator > 0)
        return TimelineFrameMath.microsecondsFromFrames(frames, frameRate)
            ?: throw ArithmeticException("Frame timestamp overflows Long")
    }

    private fun framesAtRate(durationUs: Long, frameRate: com.example.timeline.export.model.ExportFrameRate): Long {
        if (durationUs <= 0L || frameRate.numerator <= 0 || frameRate.denominator <= 0) return 0L
        return TimelineFrameMath.framesFromMicrosecondsCeiling(
            durationUs,
            Rational(frameRate.numerator, frameRate.denominator)
        ) ?: throw ArithmeticException("Export frame count overflows Long")
    }

    private fun progress(
        jobId: String,
        status: ExportJobStatus,
        percent: Int,
        totalFrames: Long,
        startedAtMs: Long,
        stage: String
    ): ExportProgress {
        val elapsed = (System.currentTimeMillis() - startedAtMs).coerceAtLeast(0L)
        val fraction = (percent / 100f).coerceIn(0f, 1f)
        val remaining = if (fraction > 0f && fraction < 1f) {
            ((elapsed / fraction) - elapsed).toLong().coerceAtLeast(0L)
        } else 0L
        val rendered = (totalFrames * fraction).toLong().coerceIn(0L, totalFrames)
        return ExportProgress(
            jobId = jobId,
            status = status,
            progressPercent = percent.toFloat(),
            renderedFrames = rendered,
            totalFrames = totalFrames,
            elapsedMs = elapsed,
            estimatedRemainingMs = remaining,
            currentStage = stage
        )
    }

    private companion object {
        const val MCRAW_PREPARE_PROGRESS_PERCENT = 10
        const val PROGRESS_POLL_INTERVAL_MS = 250L
        const val MIN_SAMPLE_BUFFER_BYTES = 4 * 1024 * 1024
        const val MAX_SAMPLE_BUFFER_BYTES = 32 * 1024 * 1024
        const val MAX_FAILURE_MESSAGE_LENGTH = 2_000
    }
}

private data class VideoEncoderQuery(
    val width: Int,
    val height: Int,
    val frameRate: Float,
    val bitrate: Int,
    val bitrateMode: Int,
    val requireHardware: Boolean
)

private data class AudioEncoderQuery(val sampleRate: Int, val channelCount: Int, val bitrate: Int)
private data class McrawIntermediateEncoderQuery(val width: Int, val height: Int, val frameRate: Float, val bitrate: Int)

private object AndroidMedia3CodecSupportProbe : Media3CodecSupportProbe {
    private val videoEncoderSupportCache = ConcurrentHashMap<VideoEncoderQuery, Boolean>()
    private val audioEncoderSupportCache = ConcurrentHashMap<AudioEncoderQuery, Boolean>()
    private val mcrawEncoderSupportCache = ConcurrentHashMap<McrawIntermediateEncoderQuery, Boolean>()

    override fun hasVideoEncoder(settings: ExportSettings, requireHardware: Boolean): Boolean {
        val bitrateMode = when (settings.bitrateMode) {
            ExportSettings.BitrateMode.CBR -> MediaCodecInfo.EncoderCapabilities.BITRATE_MODE_CBR
            ExportSettings.BitrateMode.AUTO,
            ExportSettings.BitrateMode.VBR,
            ExportSettings.BitrateMode.CRF -> MediaCodecInfo.EncoderCapabilities.BITRATE_MODE_VBR
        }
        val query = VideoEncoderQuery(
            width = settings.resolutionWidth,
            height = settings.resolutionHeight,
            frameRate = settings.frameRate.floatValue,
            bitrate = settings.videoBitrate,
            bitrateMode = bitrateMode,
            requireHardware = requireHardware
        )
        return videoEncoderSupportCache.computeIfAbsent(query) {
            runCatching {
                MediaCodecList(MediaCodecList.ALL_CODECS).codecInfos.any { codecInfo ->
                    codecInfo.isEncoder &&
                        (!query.requireHardware || isHardwareAcceleratedEncoder(codecInfo)) &&
                        codecInfo.supportedTypes.any { it.equals(MediaFormat.MIMETYPE_VIDEO_AVC, true) } &&
                        runCatching {
                            val capabilities = codecInfo.getCapabilitiesForType(MediaFormat.MIMETYPE_VIDEO_AVC)
                            val video = capabilities.videoCapabilities ?: return@runCatching false
                            val encoder = capabilities.encoderCapabilities ?: return@runCatching false
                            video.areSizeAndRateSupported(
                                query.width,
                                query.height,
                                query.frameRate.toDouble()
                            ) && video.bitrateRange.contains(query.bitrate) &&
                                encoder.isBitrateModeSupported(query.bitrateMode)
                        }.getOrDefault(false)
                }
            }.getOrDefault(false)
        }
    }

    private fun isHardwareAcceleratedEncoder(codecInfo: MediaCodecInfo): Boolean {
        if (android.os.Build.VERSION.SDK_INT >= android.os.Build.VERSION_CODES.Q) {
            return codecInfo.isHardwareAccelerated
        }
        // Android's software codecs use these stable names; unknown vendor encoders are treated as hardware-capable.
        val name = codecInfo.name.lowercase()
        return !name.startsWith("omx.google.") && !name.startsWith("c2.android.") && !name.contains("software")
    }

    override fun hasMcrawIntermediateEncoder(width: Int, height: Int, frameRate: Float, bitrate: Int): Boolean {
        val query = McrawIntermediateEncoderQuery(width, height, frameRate, bitrate)
        return mcrawEncoderSupportCache.computeIfAbsent(query) {
            McrawVideoTranscoder.hasCompatibleEncoder(query.width, query.height, query.frameRate, query.bitrate)
        }
    }

    override fun hasAudioEncoder(sampleRate: Int, channelCount: Int, bitrate: Int): Boolean {
        val query = AudioEncoderQuery(sampleRate, channelCount, bitrate)
        return audioEncoderSupportCache.computeIfAbsent(query) {
            runCatching {
                MediaCodecList(MediaCodecList.ALL_CODECS).codecInfos.any { codecInfo ->
                    codecInfo.isEncoder && codecInfo.supportedTypes.any { it.equals(MediaFormat.MIMETYPE_AUDIO_AAC, true) } &&
                        runCatching {
                            val capabilities = codecInfo.getCapabilitiesForType(MediaFormat.MIMETYPE_AUDIO_AAC)
                            val audio = capabilities.audioCapabilities ?: return@runCatching false
                            audio.isSampleRateSupported(query.sampleRate) &&
                                query.channelCount <= audio.maxInputChannelCount &&
                                audio.bitrateRange.contains(query.bitrate)
                        }.getOrDefault(false)
                }
            }.getOrDefault(false)
        }
    }
}
