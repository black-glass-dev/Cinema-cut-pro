package com.example.timeline.media

import android.content.Context
import android.media.MediaExtractor
import android.media.MediaFormat
import android.media.MediaMetadataRetriever
import android.net.Uri
import com.example.timeline.core.Rational
import com.example.timeline.core.TimelineFrameMath
import java.math.BigDecimal
import java.math.RoundingMode
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext

class AndroidMediaAnalyzer(
    private val context: Context,
    private val capabilityDetector: VideoDecoderCapabilitySource = CodecCapabilityDetector(),
    private val decisionEngine: MediaImportDecisionEngine = MediaImportDecisionEngine()
) : MediaAnalyzer {

    override suspend fun analyze(asset: MediaAsset, uri: Uri): MediaAsset = withContext(Dispatchers.IO) {
        if (MotionCamRawFormat.isCandidate(context, asset, uri)) {
            return@withContext McrawMediaAnalyzer(context).analyze(asset, uri)
        }

        val retriever = MediaMetadataRetriever()
        val extractor = MediaExtractor()

        var durationUs = 0L
        var width = 0
        var height = 0
        var primaryFrameRate: FrameRate? = null
        var defaultMimeType = asset.mimeType
        var rotation = 0

        val videoStreams = mutableListOf<VideoStreamInfo>()
        val audioStreams = mutableListOf<AudioStreamInfo>()
        var firstVideoTrackIndex: Int? = null

        try {
            retriever.setDataSource(context, uri)
            extractor.setDataSource(context, uri, null)

            val durationStr = retriever.extractMetadata(MediaMetadataRetriever.METADATA_KEY_DURATION)
            if (durationStr != null) {
                val durationMs = durationStr.toLongOrNull() ?: 0L
                durationUs = if (durationMs >= 0L) Math.multiplyExact(durationMs, 1_000L) else 0L
            }
            
            val rotationStr = retriever.extractMetadata(MediaMetadataRetriever.METADATA_KEY_VIDEO_ROTATION)
            if (rotationStr != null) {
                rotation = rotationStr.toIntOrNull() ?: 0
            }

            for (i in 0 until extractor.trackCount) {
                val format = extractor.getTrackFormat(i)
                val trackMime = format.getString(MediaFormat.KEY_MIME) ?: ""

                if (trackMime.startsWith("video/")) {
                    val isPrimaryTrack = firstVideoTrackIndex == null
                    if (isPrimaryTrack) firstVideoTrackIndex = i
                    width = format.getIntegerSafely(MediaFormat.KEY_WIDTH, 0)
                    height = format.getIntegerSafely(MediaFormat.KEY_HEIGHT, 0)

                    val streamFrameRate = if (format.containsKey(MediaFormat.KEY_FRAME_RATE)) {
                        val rateText = try {
                            format.getFloat(MediaFormat.KEY_FRAME_RATE).toString()
                        } catch (_: Exception) {
                            format.getIntegerSafely(MediaFormat.KEY_FRAME_RATE, 0)
                                .takeIf { it > 0 }
                                ?.toString()
                        }
                        frameRateFromDecimal(rateText)
                    } else {
                        null
                    }
                    if (isPrimaryTrack) primaryFrameRate = streamFrameRate

                    val colorStandard = if (format.containsKey(MediaFormat.KEY_COLOR_STANDARD)) format.getInteger(MediaFormat.KEY_COLOR_STANDARD) else null
                    val colorTransfer = if (format.containsKey(MediaFormat.KEY_COLOR_TRANSFER)) format.getInteger(MediaFormat.KEY_COLOR_TRANSFER) else null
                    val isHdr = colorTransfer == MediaFormat.COLOR_TRANSFER_HLG || colorTransfer == MediaFormat.COLOR_TRANSFER_ST2084
                    val codecProfile = if (format.containsKey(MediaFormat.KEY_PROFILE)) format.getIntegerSafely(MediaFormat.KEY_PROFILE, 0) else null
                    val codecLevel = if (format.containsKey(MediaFormat.KEY_LEVEL)) format.getIntegerSafely(MediaFormat.KEY_LEVEL, 0) else null
                    val decoderSupport = streamFrameRate?.let { rate ->
                        capabilityDetector.decoderSupportForFormat(
                            trackMime,
                            width,
                            height,
                            rate.fpsAsFloat,
                            codecProfile,
                            codecLevel
                        )
                    } ?: VideoDecoderSupport(unknown = true)

                    videoStreams.add(
                        VideoStreamInfo(
                            codecName = trackMime.replace("video/", ""),
                            codecMime = trackMime,
                            codecProfile = codecProfile,
                            codecLevel = codecLevel,
                            width = width,
                            height = height,
                            frameRate = streamFrameRate,
                            bitDepth = null,
                            chromaSubsampling = null,
                            colorStandard = colorStandard,
                            colorTransfer = colorTransfer,
                            colorRange = if (format.containsKey(MediaFormat.KEY_COLOR_RANGE)) format.getInteger(MediaFormat.KEY_COLOR_RANGE) else null,
                            isHdr = isHdr,
                            isLog = false,
                            detectedLogProfile = null,
                            hardwareDecodeSupported = decoderSupport.hardware,
                            softwareDecodeSupported = decoderSupport.software,
                            requiresProxy = decoderSupport.available && !decoderSupport.hardware,
                            requiresTranscode = !decoderSupport.available,
                            decoderSupportUnknown = decoderSupport.unknown
                        )
                    )
                    // Keep the container MIME reported by the document provider. `trackMime`
                    // describes a codec (for example video/hevc), not the media container.
                } else if (trackMime.startsWith("audio/")) {
                    audioStreams.add(
                        AudioStreamInfo(
                            codecMime = trackMime,
                            sampleRate = format.getIntegerSafely(MediaFormat.KEY_SAMPLE_RATE, 44100),
                            channelCount = format.getIntegerSafely(MediaFormat.KEY_CHANNEL_COUNT, 2),
                            bitrate = format.getIntegerSafely(MediaFormat.KEY_BIT_RATE, 128000),
                            durationUs = format.getLongSafely(MediaFormat.KEY_DURATION, 0L),
                            language = if(format.containsKey(MediaFormat.KEY_LANGUAGE)) format.getString(MediaFormat.KEY_LANGUAGE) else null,
                            hardwareDecodeSupported = true
                        )
                    )
                    // Do not replace the container MIME with an audio codec MIME here.
                } else if (trackMime.startsWith("image/")) {
                    width = format.getIntegerSafely(MediaFormat.KEY_WIDTH, width)
                    height = format.getIntegerSafely(MediaFormat.KEY_HEIGHT, height)
                    if (defaultMimeType == "application/octet-stream") {
                        defaultMimeType = trackMime
                    }
                }
            }

            if (width == 0 && videoStreams.isEmpty() && !defaultMimeType.startsWith("audio/")) {
                width = retriever.extractMetadata(MediaMetadataRetriever.METADATA_KEY_VIDEO_WIDTH)?.toIntOrNull() ?: width
                height = retriever.extractMetadata(MediaMetadataRetriever.METADATA_KEY_VIDEO_HEIGHT)?.toIntOrNull() ?: height
            }

            val hasVideo = videoStreams.isNotEmpty() || width > 0
            val hasAudio = audioStreams.isNotEmpty()

            if (hasVideo && primaryFrameRate == null) {
                primaryFrameRate = frameRateFromDecimal(
                    retriever.extractMetadata(MediaMetadataRetriever.METADATA_KEY_CAPTURE_FRAMERATE)
                )
            }

            // Probe presentation timestamps from the selected video track. This does not decode
            // frames, and the bounded sample avoids making media import wait on a full scan.
            val cadenceAnalysis = firstVideoTrackIndex?.let { trackIndex ->
                scanVideoPresentationTimes(extractor, trackIndex)
            } ?: FrameRateCadenceAnalysis(FrameRateCadence.UNKNOWN, null)
            val cadence = cadenceAnalysis.cadence
            val isVariableFrameRate = cadence == FrameRateCadence.VARIABLE
            val variableFrameRateDetectionComplete = cadence != FrameRateCadence.UNKNOWN
            val estimatedFrameRate = primaryFrameRate ?: cadenceAnalysis.medianIntervalUs?.let(
                MediaTimeMapping::frameRateFromIntervalMicroseconds
            )
            val videoStreamsWithRate = videoStreams.mapIndexed { index, stream ->
                if (index == 0 && stream.frameRate == null && estimatedFrameRate != null) {
                    stream.copy(frameRate = estimatedFrameRate)
                } else {
                    stream
                }
            }

            val analysisResult = MediaAnalysisResult(
                durationUs = durationUs,
                width = width,
                height = height,
                rotation = rotation,
                frameRate = estimatedFrameRate?.fpsAsFloat,
                defaultMimeType = defaultMimeType,
                videoStreams = videoStreamsWithRate,
                audioStreams = audioStreams,
                hasVideo = hasVideo,
                hasAudio = hasAudio,
                isVariableFrameRate = isVariableFrameRate,
                variableFrameRateDetectionComplete = variableFrameRateDetectionComplete
            )

            val mediaStatusResult = decisionEngine.decideImportStatus(analysisResult)

            val durationFrames = if (hasVideo || hasAudio) {
                estimatedFrameRate?.let { rate ->
                    TimelineFrameMath.framesFromMicroseconds(
                        durationUs,
                        Rational(rate.numerator, rate.denominator)
                    )
                } ?: 0L
            } else {
                1L
            }

            val metadata = asset.metadata.copy(
                durationUs = durationUs,
                durationFramesInSourceRate = durationFrames,
                containerFormat = "",
                width = width,
                height = height,
                rotationDegrees = rotation,
                videoStreams = videoStreamsWithRate,
                audioStreams = audioStreams,
                hasVideo = hasVideo,
                hasAudio = hasAudio,
                isVariableFrameRate = isVariableFrameRate,
                estimatedFrameRate = estimatedFrameRate,
                variableFrameRateDetectionComplete = variableFrameRateDetectionComplete
            )

            return@withContext asset.copy(
                mimeType = defaultMimeType,
                previewStatus = mediaStatusResult.previewStatus,
                proxyStatus = mediaStatusResult.proxyStatus,
                metadata = metadata,
                technicalReport = mediaStatusResult.report
            )

        } catch (e: Exception) {
            if (com.example.BuildConfig.DEBUG) e.printStackTrace()
            return@withContext asset.copy(
                previewStatus = PreviewStatus.UNSUPPORTED,
                mediaErrorStatus = MediaErrorStatus.UNKNOWN_ERROR,
                errorMessage = e.message ?: "Analysis failed",
                technicalReport = ImportTechnicalReport(
                    detectedFormat = defaultMimeType,
                    directPlaybackSupport = false,
                    proxyRecommendation = false,
                    transcodeRecommendation = false,
                    warnings = listOf("Analysis failed: ${e.message}"),
                    userFriendlyMessage = "Could not analyze the media file."
                )
            )
        } finally {
            try { retriever.release() } catch (e: Exception) {}
            try { extractor.release() } catch (e: Exception) {}
        }
    }

    private fun frameRateFromDecimal(value: String?): FrameRate? {
        val text = value?.trim()?.takeIf { it.isNotEmpty() } ?: return null
        val decimal = try {
            BigDecimal(text).stripTrailingZeros()
        } catch (_: NumberFormatException) {
            return null
        }
        if (decimal.signum() <= 0) return null
        val scale = decimal.scale().coerceAtLeast(0)
        if (scale > 6) return null
        val numerator = try {
            decimal.movePointRight(scale).setScale(0, RoundingMode.UNNECESSARY).longValueExact()
        } catch (_: ArithmeticException) {
            return null
        }
        val denominator = when (scale) {
            0 -> 1L
            1 -> 10L
            2 -> 100L
            3 -> 1_000L
            4 -> 10_000L
            5 -> 100_000L
            else -> 1_000_000L
        }
        if (numerator <= 0L || numerator > Int.MAX_VALUE || denominator > Int.MAX_VALUE) return null
        val divisor = greatestCommonDivisor(numerator, denominator)
        val reducedNumerator = numerator / divisor
        val reducedDenominator = denominator / divisor
        val rate = FrameRate(reducedNumerator.toInt(), reducedDenominator.toInt())
        return rate.takeIf {
            it.numerator.toLong() <= it.denominator.toLong() * 240L &&
                it.denominator.toLong() <= it.numerator.toLong()
        }
    }

    private fun greatestCommonDivisor(first: Long, second: Long): Long {
        var a = first
        var b = second
        while (b != 0L) {
            val remainder = a % b
            a = b
            b = remainder
        }
        return a
    }

    private fun scanVideoPresentationTimes(
        extractor: MediaExtractor,
        videoTrackIndex: Int
    ): FrameRateCadenceAnalysis {
        val presentationTimesUs = ArrayList<Long>()
        var selectedTrack = false
        try {
            extractor.selectTrack(videoTrackIndex)
            selectedTrack = true
            while (true) {
                val sampleTrackIndex = extractor.sampleTrackIndex
                val sampleTimeUs = extractor.sampleTime
                if (sampleTrackIndex < 0 || sampleTimeUs < 0L) break
                if (sampleTrackIndex == videoTrackIndex) {
                    if (presentationTimesUs.size >= MAX_VFR_SAMPLE_COUNT) {
                        // A bounded scan that hits the limit is inconclusive, not proof of CFR.
                        return FrameRateCadenceAnalysis(FrameRateCadence.UNKNOWN, null)
                    }
                    presentationTimesUs += sampleTimeUs
                }
                if (!extractor.advance()) break
            }
        } catch (error: Exception) {
            if (com.example.BuildConfig.DEBUG) {
                android.util.Log.w("AndroidMediaAnalyzer", "Could not scan video timestamps for VFR detection", error)
            }
            return FrameRateCadenceAnalysis(FrameRateCadence.UNKNOWN, null)
        } finally {
            if (selectedTrack) runCatching { extractor.unselectTrack(videoTrackIndex) }
        }
        return VariableFrameRateDetector.analyze(presentationTimesUs)
    }

    private fun MediaFormat.getIntegerSafely(key: String, default: Int): Int {
        return try { getInteger(key) } catch(e: Exception) { default }
    }
    private fun MediaFormat.getLongSafely(key: String, default: Long): Long {
        return try { getLong(key) } catch(e: Exception) { default }
    }

    private companion object {
        const val MAX_VFR_SAMPLE_COUNT = 500_000
    }
}
