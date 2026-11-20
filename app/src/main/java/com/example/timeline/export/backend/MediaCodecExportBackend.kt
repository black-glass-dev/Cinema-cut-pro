package com.example.timeline.export.backend

import android.content.Context
import com.example.timeline.core.TimelineFrameMath
import com.example.timeline.core.TimelineProject
import com.example.timeline.export.model.*
import com.example.timeline.export.pipeline.VideoExportPipeline
import com.example.timeline.export.pipeline.ExportCancellationToken
import com.example.timeline.export.pipeline.ExportProgressTracker
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.launch

class MediaCodecExportBackend(private val context: Context) : ExportBackend {
    override val backendType = ExportBackendType.MEDIACODEC_HARDWARE
    
    private val cancellationTokens = mutableMapOf<String, ExportCancellationToken>()
    private var projectRef: TimelineProject? = null
    
    /** This experimental pipeline is not registered because it has not passed end-to-end mux/export validation. */
    override fun getCapabilities(): ExportBackendCapability = ExportBackendCapability(
        backendAvailable = false,
        hardwareAccelerated = false,
        supportedCodecs = emptyList(),
        supportedContainers = emptyList(),
        maxResolutionWidth = 0,
        maxResolutionHeight = 0,
        maxFps = 0f,
        supportsHDR = false,
        supportsAlpha = false,
        supportsAudioMix = false,
        supportsVideoComposition = false,
        supportsProRes = false,
        supportsAV1 = false,
        supports10Bit = false,
        supportsBatch = false,
        supportsCancel = false
    )
    
    override fun validate(settings: ExportSettings, project: TimelineProject): ExportValidationResult {
        projectRef = project
        if (!getCapabilities().backendAvailable) {
            val message = "The experimental MediaCodec pipeline is not a validated production export backend."
            return ExportValidationResult(false, listOf(message), errorMessage = message)
        }
        val errors = mutableListOf<String>()
        val warnings = mutableListOf<String>()
        val cap = getCapabilities()
        
        if (!cap.backendAvailable) errors.add("MediaCodec backend not available")
        if (settings.codec !in cap.supportedCodecs) errors.add("Codec ${settings.codec} not supported by MediaCodec")
        if (settings.container !in cap.supportedContainers) errors.add("Container ${settings.container} not supported by MediaCodec")
        if (settings.resolutionWidth > cap.maxResolutionWidth || settings.resolutionHeight > cap.maxResolutionHeight) {
            errors.add("Resolution ${settings.resolutionWidth}x${settings.resolutionHeight} exceeds maximum limits")
        }
        
        return ExportValidationResult(
            isValid = errors.isEmpty(),
            unsupportedFeatures = errors,
            warnings = warnings,
            errorMessage = errors.firstOrNull()
        )
    }
    
    override suspend fun prepare(job: ExportJob): Boolean = false
    
    override suspend fun render(job: ExportJob, onProgress: (ExportProgress) -> Unit) {
        if (!getCapabilities().backendAvailable) {
            val message = "The experimental MediaCodec pipeline is not a validated production export backend."
            onProgress(ExportProgress(job.jobId, ExportJobStatus.FAILED, 0f, 0, 0, 0, 0, message, message))
            return
        }
        val proj = projectRef ?: run {
            onProgress(ExportProgress(job.jobId, ExportJobStatus.FAILED, 0f, 0, 0, 0, 0, "Project reference not set"))
            return
        }

        val cancellationToken = ExportCancellationToken()
        cancellationTokens[job.jobId] = cancellationToken
        
        // Output path needs to be available. We will assume getting one from context scope or it's passed somehow.
        val outputFile = if (job.outputUri != null) {
            java.io.File(job.outputUri)
        } else {
            java.io.File(context.cacheDir, "export_${System.currentTimeMillis()}.mp4")
        }
        
        val maxTimelineEnd = proj.tracks.flatMap { it.clips }.maxOfOrNull { it.timelineEnd } ?: 0L
        val projectRate = proj.settings.getFpsRational()
        val maxDurationUs = TimelineFrameMath.microsecondsFromFrames(maxTimelineEnd, projectRate)
        if (maxDurationUs == null) {
            val message = "Project duration cannot be represented safely in microseconds."
            onProgress(ExportProgress(job.jobId, ExportJobStatus.FAILED, 0f, 0, 0, 0, 0, message, message))
            cancellationTokens.remove(job.jobId)
            return
        }
        val totalFrames = TimelineFrameMath.convertFrameCountCeiling(
            frameCount = maxTimelineEnd,
            sourceNumerator = projectRate.numerator,
            sourceDenominator = projectRate.denominator,
            targetNumerator = job.settings.frameRate.numerator,
            targetDenominator = job.settings.frameRate.denominator
        )
        if (totalFrames == null || totalFrames == 0L) {
            val message = if (totalFrames == 0L) {
                "The project contains no timeline frames to export."
            } else {
                "Export frame count cannot be represented safely."
            }
            onProgress(ExportProgress(job.jobId, ExportJobStatus.FAILED, 0f, 0, 0, 0, 0, message, message))
            cancellationTokens.remove(job.jobId)
            return
        }
        
        val progressTracker = ExportProgressTracker(job.jobId, totalFrames) { progress ->
            onProgress(progress)
        }

        val pipeline = VideoExportPipeline(context, proj)
        pipeline.runExport(job, outputFile.absolutePath, cancellationToken, progressTracker)
        
        cancellationTokens.remove(job.jobId)
    }
    
    override fun cancel(jobId: String) {
        cancellationTokens[jobId]?.cancel()
    }
    
    override fun release() {
        cancellationTokens.values.forEach { it.cancel() }
        cancellationTokens.clear()
        projectRef = null
    }

}