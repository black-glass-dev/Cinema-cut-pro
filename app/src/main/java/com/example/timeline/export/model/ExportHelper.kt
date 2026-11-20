package com.example.timeline.export.model

import com.example.timeline.core.ProjectSettings
import com.example.timeline.core.Rational
import com.example.timeline.core.TimelineFrameMath
import java.math.BigInteger
import java.util.UUID

object ExportHelper {

    fun defaultExportSettings(projectSettings: ProjectSettings, projectId: String = "unknown"): ExportSettings {
        val rational = projectSettings.getFpsRational()
        return ExportSettings(
            exportId = UUID.randomUUID().toString(),
            projectId = projectId,
            outputFileName = projectSettings.projectName + "_export.mp4",
            outputDirectoryUri = "content://media/external/video/media", // Placeholder
            resolutionWidth = projectSettings.resolutionWidth,
            resolutionHeight = projectSettings.resolutionHeight,
            frameRate = ExportFrameRate(rational.numerator, rational.denominator),
            codec = ExportCodec.H264,
            container = ExportContainer.MP4,
            bitrateMode = ExportSettings.BitrateMode.VBR,
            videoBitrate = 15_000_000,
            maxBitrate = 20_000_000,
            qualityPreset = ExportQualityPreset.STANDARD,
            keyframeInterval = 1, // Usually 1 or 2 seconds
            useHardwareEncoder = true,
            useOriginalMedia = true,
            allowProxyExport = false
        )
    }

    fun youtubePreset(baseSettings: ExportSettings): ExportSettings {
        return baseSettings.copy(
            resolutionWidth = 3840,
            resolutionHeight = 2160,
            codec = ExportCodec.H265,
            videoBitrate = 50_000_000,
            maxBitrate = 60_000_000,
            qualityPreset = ExportQualityPreset.HIGH,
            socialPreset = "YouTube"
        )
    }

    fun tiktokPreset(baseSettings: ExportSettings): ExportSettings {
        return baseSettings.copy(
            resolutionWidth = 1080,
            resolutionHeight = 1920,
            codec = ExportCodec.H264,
            videoBitrate = 8_000_000,
            maxBitrate = 10_000_000,
            qualityPreset = ExportQualityPreset.STANDARD,
            socialPreset = "TikTok"
        )
    }

    fun instagramReelsPreset(baseSettings: ExportSettings): ExportSettings {
        return baseSettings.copy(
            resolutionWidth = 1080,
            resolutionHeight = 1920,
            codec = ExportCodec.H265,
            videoBitrate = 10_000_000,
            maxBitrate = 15_000_000,
            qualityPreset = ExportQualityPreset.HIGH,
            socialPreset = "Instagram Reels"
        )
    }

    fun customPreset(baseSettings: ExportSettings): ExportSettings {
        return baseSettings.copy(
            qualityPreset = ExportQualityPreset.CUSTOM,
            socialPreset = "Custom"
        )
    }

    /** Duration is integer microseconds; output frame count is rounded up to cover the full duration. */
    fun calculateTotalFrames(settings: ExportSettings, durationUs: Long): Long? =
        TimelineFrameMath.framesFromMicrosecondsCeiling(
            durationUs,
            Rational(settings.frameRate.numerator, settings.frameRate.denominator)
        )

    /** Exact bitrate-size estimate in bytes; null means invalid duration or Long overflow. */
    fun estimateFileSize(settings: ExportSettings, durationUs: Long): Long? {
        if (durationUs < 0L || settings.videoBitrate < 0 || settings.audioBitrate < 0) return null
        val bitsPerSecond = BigInteger.valueOf(settings.videoBitrate.toLong())
            .add(BigInteger.valueOf(settings.audioBitrate.toLong()))
        return try {
            bitsPerSecond.multiply(BigInteger.valueOf(durationUs))
                .divide(BigInteger.valueOf(8_000_000L))
                .longValueExact()
        } catch (_: ArithmeticException) {
            null
        }
    }

    fun validateExportSettings(settings: ExportSettings): ExportValidationResult {
        val errors = mutableListOf<String>()
        val warnings = mutableListOf<String>()

        if (settings.resolutionWidth <= 0 || settings.resolutionHeight <= 0) {
            errors.add("Invalid resolution: ${settings.resolutionWidth}x${settings.resolutionHeight}")
        }
        
        if (settings.frameRate.numerator <= 0 || settings.frameRate.denominator <= 0) {
            errors.add("Invalid frame rate")
        }

        if (settings.videoBitrate <= 0) {
            errors.add("Invalid video bitrate")
        }
        
        if (settings.codec == ExportCodec.IMAGE_SEQUENCE && settings.container != ExportContainer.MP4) {
             warnings.add("Container usually ignored for image sequence")
        }
        
        // Example check for ProRes 
        if (settings.codec == ExportCodec.PRORES && settings.container != ExportContainer.MOV) {
            warnings.add("ProRes typically uses MOV container")
        }

        return ExportValidationResult(
            isValid = errors.isEmpty(),
            unsupportedFeatures = errors,
            warnings = warnings,
            errorMessage = errors.firstOrNull()
        )
    }

    fun createExportReport(
        jobId: String,
        projectId: String,
        startTimeMs: Long,
        endTimeMs: Long,
        totalFramesRendered: Long,
        finalFileSizeBytes: Long,
        outputUri: String,
        settingsUsed: ExportSettings,
        backendUsed: ExportBackendType
    ): ExportReport {
        val durationSecs = (endTimeMs - startTimeMs) / 1000f
        val averageFps = if (durationSecs > 0) totalFramesRendered / durationSecs else 0f
        
        return ExportReport(
            jobId = jobId,
            projectId = projectId,
            startTimeMs = startTimeMs,
            endTimeMs = endTimeMs,
            totalFramesRendered = totalFramesRendered,
            averageFps = averageFps,
            finalFileSizeBytes = finalFileSizeBytes,
            outputUri = outputUri,
            settingsUsed = settingsUsed,
            backendUsed = backendUsed,
            warnings = emptyList() // Could merge with run-time warnings if applicable
        )
    }
}
