package com.example.timeline.export.backend

import com.example.timeline.core.TimelineProject
import com.example.timeline.export.model.ExportBackendType
import com.example.timeline.export.model.ExportJob
import com.example.timeline.export.model.ExportJobStatus
import com.example.timeline.export.model.ExportProgress
import com.example.timeline.export.model.ExportSettings
import com.example.timeline.export.model.ExportValidationResult

/**
 * Kept as a future integration point only. The native code currently evaluates a frame plan; it
 * does not encode and mux an output video, so this backend must never appear as an export option.
 */
class NativeSoftwareExportBackend : ExportBackend {
    override val backendType = ExportBackendType.NATIVE_SOFTWARE

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
        val message = "The native software backend does not yet encode and mux a playable video file."
        return ExportValidationResult(
            isValid = false,
            unsupportedFeatures = listOf(message),
            errorMessage = message
        )
    }

    override suspend fun prepare(job: ExportJob): Boolean = false

    override suspend fun render(job: ExportJob, onProgress: (ExportProgress) -> Unit) {
        onProgress(
            ExportProgress(
                jobId = job.jobId,
                status = ExportJobStatus.FAILED,
                progressPercent = 0f,
                renderedFrames = 0,
                totalFrames = 0,
                elapsedMs = 0,
                estimatedRemainingMs = 0,
                currentStage = "Native video export unavailable",
                errorMessage = "The native software backend does not yet encode and mux a playable video file."
            )
        )
    }

    override fun cancel(jobId: String) = Unit

    override fun release() = Unit
}
