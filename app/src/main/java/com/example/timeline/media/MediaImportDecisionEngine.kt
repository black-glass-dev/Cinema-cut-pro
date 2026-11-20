package com.example.timeline.media

data class MediaStatusResult(
    val previewStatus: PreviewStatus,
    val proxyStatus: ProxyStatus,
    val report: ImportTechnicalReport
)

class MediaImportDecisionEngine {
    fun decideImportStatus(result: MediaAnalysisResult): MediaStatusResult {
        val mimeType = result.defaultMimeType
        val codecIdentifiers = result.videoStreams
            .flatMap { listOf(it.codecName, it.codecMime) }
            .joinToString(" ")
            .lowercase()
        val formatIdentifiers = "$mimeType $codecIdentifiers".lowercase()
        var directPlaybackSupport = false
        var proxyRecommendation = false
        var transcodeRecommendation = false
        var previewStatus = PreviewStatus.DIRECT_SUPPORTED_PROBABLE
        var proxyStatus = ProxyStatus.NONE
        val warnings = mutableListOf<String>()
        var userMessage = "Media is ready."

        if (formatIdentifiers.contains("motioncam-raw") || formatIdentifiers.contains(".mcraw")) {
            return MediaStatusResult(
                previewStatus = PreviewStatus.DIRECT_SUPPORTED_PROBABLE,
                proxyStatus = ProxyStatus.NONE,
                report = ImportTechnicalReport(
                    detectedFormat = MotionCamRawFormat.MIME_TYPE,
                    directPlaybackSupport = true,
                    proxyRecommendation = false,
                    transcodeRecommendation = false,
                    warnings = listOf("Native MotionCam RAW preview is available; camera color and real-time performance still require device validation."),
                    userFriendlyMessage = "MotionCam RAW is ready for native preview."
                )
            )
        }

        if (mimeType.startsWith("audio/") || mimeType.startsWith("image/")) {
            return MediaStatusResult(
                previewStatus = PreviewStatus.DIRECT_SUPPORTED_PROBABLE,
                proxyStatus = ProxyStatus.NONE,
                report = ImportTechnicalReport(
                    detectedFormat = mimeType,
                    directPlaybackSupport = true,
                    proxyRecommendation = false,
                    transcodeRecommendation = false,
                    warnings = warnings,
                    userFriendlyMessage = userMessage
                )
            )
        }

        if (formatIdentifiers.contains("raw") || formatIdentifiers.contains("braw") || formatIdentifiers.contains("r3d") || formatIdentifiers.contains("arriraw")) {
            previewStatus = PreviewStatus.UNSUPPORTED
            proxyStatus = ProxyStatus.REQUIRED
            userMessage = "A manufacturer plugin is required to decode this RAW format."
        } else if (formatIdentifiers.contains("prores") || formatIdentifiers.contains("dnxhr") || formatIdentifiers.contains("mxf")) {
            transcodeRecommendation = true
            previewStatus = PreviewStatus.UNSUPPORTED
            proxyStatus = ProxyStatus.REQUIRED
            userMessage = "This professional format requires transcoding for editing on this device."
        } else {
            val hardwareDecoderAvailable = result.videoStreams.any { it.hardwareDecodeSupported }
            val anyDecoderAvailable = result.videoStreams.any {
                it.hardwareDecodeSupported || it.softwareDecodeSupported || it.decoderSupportUnknown
            }

            if (!anyDecoderAvailable) {
                transcodeRecommendation = true
                previewStatus = PreviewStatus.UNSUPPORTED
                proxyStatus = ProxyStatus.REQUIRED
                userMessage = "No compatible decoder was found for this video on the device. A proxy or supported transcode is required."
                warnings.add("No compatible decoder was detected for $mimeType")
            } else {
                directPlaybackSupport = true
                val isHevc = formatIdentifiers.contains("hevc") || formatIdentifiers.contains("h265")
                val isHighWorkload = isHighDecodeWorkload(result)
                val isHdr = result.videoStreams.any { it.isHdr }
                val noConfirmedHardwareDecoder = !hardwareDecoderAvailable

                if (noConfirmedHardwareDecoder || isHighWorkload || (isHevc && isHdr)) {
                    proxyRecommendation = true
                    proxyStatus = ProxyStatus.RECOMMENDED
                    if (noConfirmedHardwareDecoder) {
                        warnings.add("A hardware decoder was not confirmed for $mimeType; direct playback may stutter and a proxy is recommended.")
                    }
                    if (isHighWorkload) {
                        val rateDescription = result.frameRate
                            ?.takeIf { it.isFinite() && it > 0f }
                            ?.let { "$it fps" }
                            ?: "unknown frame rate"
                        warnings.add("High decode workload detected (${result.width}×${result.height} at $rateDescription); a proxy is recommended for smoother editing.")
                    }
                    if (isHevc && isHdr) {
                        warnings.add("HDR HEVC media can be demanding for preview; a proxy is recommended for smoother editing.")
                    }
                    userMessage = if (noConfirmedHardwareDecoder) {
                        "This video may play slowly on this device. A proxy is recommended for smoother editing."
                    } else {
                        "A proxy is recommended to keep editing responsive while preserving the original media."
                    }
                }
            }
        }

        if (result.isVariableFrameRate) {
            warnings.add("Variable frame rate was detected from sample timestamps. This Media3 export path cannot conform it yet; export will be blocked and the original will remain unchanged.")
        } else if (result.hasVideo && !result.variableFrameRateDetectionComplete) {
            warnings.add("Video timestamp cadence could not be verified; reanalyze before exporting this source.")
        }

        val report = ImportTechnicalReport(
            detectedFormat = mimeType,
            directPlaybackSupport = directPlaybackSupport,
            proxyRecommendation = proxyRecommendation,
            transcodeRecommendation = transcodeRecommendation,
            warnings = warnings,
            userFriendlyMessage = userMessage
        )
        return MediaStatusResult(previewStatus, proxyStatus, report)
    }

    private fun isHighDecodeWorkload(result: MediaAnalysisResult): Boolean {
        val width = result.width.coerceAtLeast(0)
        val height = result.height.coerceAtLeast(0)
        val frameRate = result.frameRate?.takeIf { it.isFinite() && it > 0f } ?: 0f
        val pixelsPerSecond = width.toDouble() * height.toDouble() * frameRate.toDouble()
        val baseline1080p30 = 1920.0 * 1080.0 * 30.0
        return maxOf(width, height) >= 2560 ||
            frameRate > 60f ||
            pixelsPerSecond > baseline1080p30 * 1.5
    }
}
