package com.example.timeline.media

import android.content.Context
import android.net.Uri
import kotlinx.coroutines.Dispatchers
import com.example.timeline.core.Rational
import com.example.timeline.core.TimelineFrameMath
import kotlinx.coroutines.withContext
import org.json.JSONObject
import java.math.BigDecimal
import java.math.RoundingMode
import kotlin.math.ceil
import kotlin.math.ln

/** Reads container timing/track metadata without asking MediaExtractor to parse a .mcraw file. */
class McrawMediaAnalyzer(private val context: Context) {
    suspend fun analyze(asset: MediaAsset, uri: Uri): MediaAsset = withContext(Dispatchers.IO) {
        var handle = 0L
        try {
            handle = NativeMcrawDecoder.open(context, uri)
            val frameCount = NativeMcrawDecoder.frameCount(handle)
            if (frameCount <= 0) throw IllegalStateException("MotionCam recording contains no video frames")

            val container = JSONObject(NativeMcrawDecoder.containerMetadataJson(handle))
            val firstFrame = JSONObject(NativeMcrawDecoder.frameMetadataJson(handle, 0))
            val width = firstFrame.optInt("width", 0)
            val height = firstFrame.optInt("height", 0)
            if (width <= 0 || height <= 0) throw IllegalStateException("MotionCam frame dimensions are missing")
            val whiteLevel = container.optDouble("whiteLevel", 65_535.0).coerceAtLeast(1.0)
            val sensorBitDepth = ceil(ln(whiteLevel + 1.0) / ln(2.0)).toInt().coerceIn(1, 16)

            val timestampsNs = LongArray(frameCount) { index ->
                NativeMcrawDecoder.frameTimestampNs(handle, index)
            }
            val positiveIntervalsNs = timestampsNs.asList()
                .zipWithNext { earlier, later ->
                    try {
                        Math.subtractExact(later, earlier)
                    } catch (_: ArithmeticException) {
                        0L
                    }
                }
                .filter { it > 0L }
            val medianIntervalNs = median(positiveIntervalsNs)
            val frameRateRational = if (medianIntervalNs > 0L) {
                MediaTimeMapping.frameRateFromIntervalNanoseconds(medianIntervalNs)
            } else {
                readNominalFrameRate(container)
            }
            val durationUs = when {
                timestampsNs.size > 1 && medianIntervalNs > 0L -> {
                    val elapsedNs = Math.subtractExact(timestampsNs.last(), timestampsNs.first()).coerceAtLeast(0L)
                    Math.addExact(elapsedNs, medianIntervalNs) / 1_000L
                }
                frameRateRational != null -> TimelineFrameMath.microsecondsFromFrames(
                    1L,
                    Rational(frameRateRational.numerator, frameRateRational.denominator)
                ) ?: 0L
                else -> 0L
            }
            val cadenceUs = timestampsNs.map { it / 1000L }
            val cadence = VariableFrameRateDetector.classify(cadenceUs)
            val cadenceVerified = cadence != FrameRateCadence.UNKNOWN

            val sampleRate = NativeMcrawDecoder.audioSampleRateHz(handle).coerceAtLeast(0)
            val channelCount = NativeMcrawDecoder.audioChannelCount(handle).coerceIn(0, 8)
            val hasAudio = sampleRate > 0 && channelCount > 0
            val audioStreams = if (hasAudio) {
                listOf(
                    AudioStreamInfo(
                        codecMime = "audio/raw",
                        sampleRate = sampleRate,
                        channelCount = channelCount,
                        bitrate = (sampleRate.toLong() * channelCount * 16L).coerceAtMost(Int.MAX_VALUE.toLong()).toInt(),
                        durationUs = durationUs,
                        language = null,
                        hardwareDecodeSupported = false
                    )
                )
            } else {
                emptyList()
            }

            val hasGyro = NativeMcrawDecoder.hasGyroData(handle)
            val hasAccelerometer = NativeMcrawDecoder.hasAccelerometerData(handle)
            val reportWarnings = buildList {
                add("The native preview uses a lightweight Bayer demosaic. Playback renders at 480 px on the longest edge and may skip frames on slower devices rather than slowing the timeline. Basic exposure, temperature/tint, Lift/Gamma/Gain/Offset wheels, and three-zone color balance are previewed; curves, HSL, LUTs, and other advanced grades are not yet reflected.")
                if (hasAudio) {
                    add("Embedded PCM audio is detected, but MotionCam audio playback and AAC export are not connected; the current RAW export is video-only.")
                }
                if (hasGyro || hasAccelerometer) {
                    add("MotionCam sensor metadata detected: gyro=$hasGyro, accelerometer=$hasAccelerometer. Stabilization is not applied yet.")
                }
                if (!hasAudio) {
                    add("No embedded PCM audio track was detected.")
                }
            }

            asset.copy(
                mimeType = MotionCamRawFormat.MIME_TYPE,
                assetType = MediaAssetType.VIDEO,
                previewStatus = PreviewStatus.DIRECT_SUPPORTED_PROBABLE,
                proxyStatus = ProxyStatus.NONE,
                mediaErrorStatus = MediaErrorStatus.NONE,
                errorMessage = null,
                metadata = asset.metadata.copy(
                    durationUs = durationUs,
                    durationFramesInSourceRate = frameCount.toLong(),
                    containerFormat = "MotionCam RAW (MCRAW v3)",
                    bitrate = 0L,
                    width = width,
                    height = height,
                    rotationDegrees = firstFrame.optInt("rotationDegrees", 0).normalizedRotation(),
                    pixelAspectRatio = 1.0f,
                    videoStreams = listOf(
                        VideoStreamInfo(
                            codecName = "motioncam-raw",
                            codecMime = MotionCamRawFormat.MIME_TYPE,
                            codecProfile = null,
                            codecLevel = null,
                            width = width,
                            height = height,
                            frameRate = frameRateRational,
                            bitDepth = sensorBitDepth,
                            chromaSubsampling = "Bayer",
                            colorStandard = null,
                            colorTransfer = null,
                            colorRange = null,
                            isHdr = false,
                            isLog = false,
                            detectedLogProfile = null,
                            hardwareDecodeSupported = false,
                            softwareDecodeSupported = true,
                            requiresProxy = false,
                            requiresTranscode = false,
                            decoderSupportUnknown = false
                        )
                    ),
                    audioStreams = audioStreams,
                    hasVideo = true,
                    hasAudio = hasAudio,
                    isVariableFrameRate = cadence == FrameRateCadence.VARIABLE,
                    estimatedFrameRate = frameRateRational,
                    exactFrameRate = null,
                    variableFrameRateDetectionComplete = cadenceVerified
                ),
                technicalReport = ImportTechnicalReport(
                    detectedFormat = MotionCamRawFormat.MIME_TYPE,
                    directPlaybackSupport = true,
                    proxyRecommendation = false,
                    transcodeRecommendation = false,
                    warnings = reportWarnings,
                    userFriendlyMessage = "MotionCam RAW media is ready for native preview."
                )
            )
        } catch (error: Exception) {
            asset.copy(
                mimeType = MotionCamRawFormat.MIME_TYPE,
                assetType = MediaAssetType.VIDEO,
                previewStatus = PreviewStatus.UNSUPPORTED,
                proxyStatus = ProxyStatus.NONE,
                mediaErrorStatus = MediaErrorStatus.ANALYSIS_FAILED,
                errorMessage = MotionCamRawFormat.openErrorMessage(error),
                technicalReport = ImportTechnicalReport(
                    detectedFormat = MotionCamRawFormat.MIME_TYPE,
                    directPlaybackSupport = false,
                    proxyRecommendation = false,
                    transcodeRecommendation = false,
                    warnings = listOf(MotionCamRawFormat.openErrorMessage(error)),
                    userFriendlyMessage = "This MotionCam RAW recording could not be opened."
                )
            )
        } finally {
            if (handle != 0L) NativeMcrawDecoder.close(handle)
        }
    }

    private fun readNominalFrameRate(container: JSONObject): FrameRate? {
        val keys = listOf("frameRate", "fps", "captureFrameRate")
        for (key in keys) {
            val value = container.optString(key, "").toBigDecimalOrNull() ?: continue
            if (value < BigDecimal.ONE || value > BigDecimal.valueOf(240L)) continue
            val milliFramesPerSecond = value.multiply(BigDecimal.valueOf(1_000L))
                .setScale(0, RoundingMode.HALF_UP)
                .toInt()
            if (milliFramesPerSecond > 0) return FrameRate(milliFramesPerSecond, 1_000)
        }
        return null
    }

    private fun median(values: List<Long>): Long {
        if (values.isEmpty()) return 0L
        val sorted = values.sorted()
        val middle = sorted.size / 2
        return if (sorted.size % 2 == 0) {
            val lower = sorted[middle - 1]
            val upper = sorted[middle]
            lower + (upper - lower) / 2L
        } else {
            sorted[middle]
        }
    }

    private fun Int.normalizedRotation(): Int = ((this % 360) + 360) % 360
}
