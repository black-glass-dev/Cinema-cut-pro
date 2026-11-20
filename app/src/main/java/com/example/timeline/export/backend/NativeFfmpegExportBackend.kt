package com.example.timeline.export.backend

import com.example.timeline.core.TimelineProject
import com.example.timeline.export.model.*

class NativeFfmpegExportBackend : ExportBackend {
    override val backendType = ExportBackendType.NATIVE_FFMPEG
    
    // We intentionally set this to false to reflect reality: no FFmpeg binary is loaded yet.
    private val isFfmpegAvailable = false 

    override fun getCapabilities(): ExportBackendCapability {
        return ExportBackendCapability(
            backendAvailable = isFfmpegAvailable,
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
    }
    
    override fun validate(settings: ExportSettings, project: TimelineProject): ExportValidationResult {
        if (!isFfmpegAvailable) {
            return ExportValidationResult(
                isValid = false,
                unsupportedFeatures = listOf("FFmpeg backend not installed"),
                errorMessage = "FFmpeg backend not installed"
            )
        }
        return ExportValidationResult(true)
    }
    
    override suspend fun prepare(job: ExportJob): Boolean {
        return isFfmpegAvailable
    }
    
    override suspend fun render(job: ExportJob, onProgress: (ExportProgress) -> Unit) {
        if (!isFfmpegAvailable) {
            onProgress(ExportProgress(job.jobId, ExportJobStatus.FAILED, 0f, 0, 0, 0, 0, "FFmpeg backend not installed.", "FFmpeg Missing"))
            return
        }
    }
    
    override fun cancel(jobId: String) {}
    
    override fun release() {}
}
