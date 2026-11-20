package com.example.ui.export

import com.example.timeline.core.TimelineProject
import com.example.timeline.core.TrackType
import com.example.timeline.export.model.ExportAudioSettings
import com.example.timeline.export.model.ExportColorSettings
import com.example.timeline.export.model.ExportCodec
import com.example.timeline.export.model.ExportContainer
import com.example.timeline.export.model.ExportFrameRate
import com.example.timeline.export.model.ExportQualityPreset
import com.example.timeline.export.model.ExportSettings
import com.example.timeline.media.FrameRate
import com.example.timeline.media.MediaAsset
import com.example.timeline.media.MotionCamRawFormat
import kotlin.math.abs
import kotlin.math.roundToInt

/** Defaults and UI mappings for the currently implemented Media3 H.264/MP4 export path. */
internal object ExportSettingsDefaults {
    data class ResolutionOption(val width: Int, val height: Int, val label: String)

    private data class ActiveSource(val asset: MediaAsset)

    fun adaptToSimpleSource(
        settings: ExportSettings,
        project: TimelineProject,
        matchProjectOrientation: Boolean = false
    ): ExportSettings {
        val activeVideoTracks = project.tracks
            .filter { it.type == TrackType.VIDEO && it.isVisible && it.clips.any { clip -> clip.isEnabled } }
        val activeVideoTrack = activeVideoTracks.singleOrNull()
        val activeVideoClips = activeVideoTrack?.clips?.filter { it.isEnabled }.orEmpty()
        val activeSources = activeVideoClips.mapNotNull { clip ->
            project.mediaAssets.firstOrNull { it.assetId == clip.mediaId }?.let(::ActiveSource)
        }

        val sourceRate = commonConstantFrameRate(activeSources)
        val projectRate = project.settings.getFpsRational()
            .takeIf { it.numerator > 0 && it.denominator > 0 }
            ?.let { ExportFrameRate(it.numerator, it.denominator) }
        val requestedRate = sourceRate ?: projectRate ?: settings.frameRate

        val commonAudio = commonEmbeddedAudioFormat(activeSources)
        val canIncludeAudio = activeVideoTrack?.isMuted == false && activeVideoClips.isNotEmpty() &&
            activeSources.size == activeVideoClips.size && activeSources.all { it.asset.metadata.hasAudio } &&
            activeSources.none { MotionCamRawFormat.isMcrawAsset(it.asset) } && commonAudio != null
        val (outputWidth, outputHeight) = if (matchProjectOrientation) {
            resolutionMatchingProject(
                settings.resolutionWidth,
                settings.resolutionHeight,
                project.settings.resolutionWidth,
                project.settings.resolutionHeight
            )
        } else {
            settings.resolutionWidth to settings.resolutionHeight
        }
        val requestedMode = when (settings.bitrateMode) {
            ExportSettings.BitrateMode.CRF,
            ExportSettings.BitrateMode.AUTO -> ExportSettings.BitrateMode.VBR
            else -> settings.bitrateMode
        }
        val safeVideoBitrate = settings.videoBitrate.coerceAtLeast(1)

        return settings.copy(
            resolutionWidth = outputWidth,
            resolutionHeight = outputHeight,
            frameRate = requestedRate,
            codec = ExportCodec.H264,
            container = ExportContainer.MP4,
            bitrateMode = requestedMode,
            maxBitrate = safeVideoBitrate,
            useHardwareEncoder = true,
            useOriginalMedia = true,
            allowProxyExport = false,
            renderAlpha = false,
            exportHDR = false,
            colorOutput = ExportColorSettings.ColorOutput.Rec709,
            toneMappingMode = ExportColorSettings.ToneMappingMode.STANDARD,
            audioCodec = ExportAudioSettings.AudioCodec.AAC,
            includeAudio = settings.includeAudio && canIncludeAudio,
            audioSampleRate = commonAudio?.first ?: settings.audioSampleRate.coerceAtLeast(1),
            audioChannels = commonAudio?.second ?: settings.audioChannels.coerceIn(1, 2),
            normalizeAudio = false,
            includeWatermark = false,
            watermarkSettings = settings.watermarkSettings?.copy(enabled = false)
        )
    }

    fun resolutionOptions(width: Int, height: Int): List<ResolutionOption> {
        val safeWidth = width.coerceAtLeast(2)
        val safeHeight = height.coerceAtLeast(2)
        val aspectRatio = safeWidth.toDouble() / safeHeight.toDouble()
        val aspectScale = maxOf(aspectRatio, 1.0 / aspectRatio)
        val maxShortEdge = minOf(2160, (3840.0 / aspectScale).toInt()).coerceAtLeast(2)
        val targets = listOf(720, 1080, 1440, 2160).filter { it <= maxShortEdge }.ifEmpty { listOf(maxShortEdge) }

        return targets.map { shortEdge ->
            val dimensions = dimensionsAtShortEdge(shortEdge, aspectRatio)
            val label = when {
                minOf(dimensions.first, dimensions.second) == 720 -> "720p"
                minOf(dimensions.first, dimensions.second) == 1080 -> "1080p"
                minOf(dimensions.first, dimensions.second) == 1440 -> "1440p"
                maxOf(dimensions.first, dimensions.second) >= 3840 -> "4K"
                else -> "${dimensions.first}×${dimensions.second}"
            }
            ResolutionOption(dimensions.first, dimensions.second, label)
        }.distinctBy { it.width to it.height }
    }

    fun resolutionMatchingProject(
        requestedWidth: Int,
        requestedHeight: Int,
        projectWidth: Int,
        projectHeight: Int
    ): Pair<Int, Int> {
        if (projectWidth <= 0 || projectHeight <= 0) return requestedWidth to requestedHeight
        val aspectRatio = projectWidth.toDouble() / projectHeight.toDouble()
        val requestedShortEdge = minOf(requestedWidth, requestedHeight).coerceAtLeast(2)
        val projectShortEdge = minOf(projectWidth, projectHeight)
        val aspectScale = maxOf(aspectRatio, 1.0 / aspectRatio)
        val maxShortEdge = minOf(projectShortEdge, 2160, (3840.0 / aspectScale).toInt()).coerceAtLeast(2)
        val shortEdge = minOf(requestedShortEdge, maxShortEdge)
        return dimensionsAtShortEdge(shortEdge, aspectRatio)
    }

    private fun dimensionsAtShortEdge(shortEdge: Int, aspectRatio: Double): Pair<Int, Int> {
        val safeShortEdge = shortEdge.coerceAtLeast(2)
        val aspectScale = maxOf(aspectRatio, 1.0 / aspectRatio)
        val boundedShortEdge = minOf(safeShortEdge, 2160, (3840.0 / aspectScale).toInt()).coerceAtLeast(2)
        return if (aspectRatio >= 1.0) {
            val height = (boundedShortEdge / 2) * 2
            val width = (((height * aspectRatio).roundToInt()) / 2) * 2
            width.coerceAtLeast(2) to height.coerceAtLeast(2)
        } else {
            val width = (boundedShortEdge / 2) * 2
            val height = (((width / aspectRatio).roundToInt()) / 2) * 2
            width.coerceAtLeast(2) to height.coerceAtLeast(2)
        }
    }

    fun bitrateForQualityPreset(
        preset: ExportQualityPreset,
        width: Int,
        height: Int,
        frameRate: ExportFrameRate
    ): Int? {
        val multiplier = when (preset) {
            ExportQualityPreset.DRAFT -> 0.45
            ExportQualityPreset.STANDARD -> 0.7
            ExportQualityPreset.HIGH -> 1.0
            ExportQualityPreset.MASTER -> 1.5
            ExportQualityPreset.CUSTOM -> return null
        }
        val pixelsScale = width.coerceAtLeast(1).toDouble() * height.coerceAtLeast(1) /
            (1920.0 * 1080.0)
        val frameRateScale = (frameRate.floatValue.takeIf { it.isFinite() && it > 0f } ?: 30f) / 30.0
        return (8_000_000.0 * pixelsScale * frameRateScale * multiplier)
            .roundToInt()
            .coerceIn(500_000, 150_000_000)
    }

    fun closestExportFrameRate(source: FrameRate): ExportFrameRate {
        val commonRates = listOf(
            ExportFrameRate.FPS_23_976, ExportFrameRate.FPS_24, ExportFrameRate.FPS_25,
            ExportFrameRate.FPS_29_97, ExportFrameRate.FPS_30, ExportFrameRate.FPS_50,
            ExportFrameRate.FPS_59_94, ExportFrameRate.FPS_60, ExportFrameRate.FPS_120
        )
        val nearest = commonRates.minByOrNull { abs(it.floatValue - source.fpsAsFloat) }
        return if (nearest != null && abs(nearest.floatValue - source.fpsAsFloat) <= 0.01f) {
            nearest
        } else {
            ExportFrameRate(source.numerator, source.denominator)
        }
    }

    private fun commonConstantFrameRate(sources: List<ActiveSource>): ExportFrameRate? {
        if (sources.isEmpty()) return null
        val rates = sources.map { source ->
            val metadata = source.asset.metadata
            if (metadata.isVariableFrameRate) return null
            (metadata.exactFrameRate ?: metadata.estimatedFrameRate)
                ?.takeIf { it.numerator > 0 && it.denominator > 0 }
                ?: return null
        }
        val first = rates.first()
        if (rates.any { abs(it.fpsAsFloat - first.fpsAsFloat) > 0.01f }) return null
        return closestExportFrameRate(first)
    }

    private fun commonEmbeddedAudioFormat(sources: List<ActiveSource>): Pair<Int, Int>? {
        if (sources.isEmpty() || sources.any { !it.asset.metadata.hasAudio }) return null
        val streams = sources.map { it.asset.metadata.audioStreams.singleOrNull() ?: return null }
        val first = streams.first()
        if (first.sampleRate <= 0 || first.channelCount !in 1..2) return null
        if (streams.any { it.sampleRate != first.sampleRate || it.channelCount != first.channelCount }) return null
        return first.sampleRate to first.channelCount
    }
}
