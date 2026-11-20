package com.example.timeline.export.backend

import android.content.Context
import android.graphics.Bitmap
import android.media.Image
import android.media.MediaCodec
import android.media.MediaCodecInfo
import android.media.MediaCodecList
import android.media.MediaFormat
import android.media.MediaMuxer
import android.net.Uri
import android.os.Build
import android.os.SystemClock
import com.example.timeline.core.Rational
import com.example.timeline.core.TimelineFrameMath
import com.example.timeline.media.FrameRate
import com.example.timeline.media.NativeMcrawDecoder
import kotlinx.coroutines.CancellationException
import org.json.JSONObject
import java.io.File
import java.io.IOException
import java.math.BigInteger
import java.nio.ByteBuffer
import kotlin.math.roundToInt

/** The upright, even-sized frame produced by the native Bayer renderer at the requested edge. */
internal data class McrawRenderSize(val width: Int, val height: Int)
internal data class SourceFrameRange(val startFrame: Long, val endFrameExclusive: Long)

/** Maps project-time trims to source-frame indexes, keeping the boundary frames needed for precise Media3 clipping. */
internal fun mcrawFrameRangeForTimeUs(
    startPositionUs: Long,
    endPositionUs: Long,
    sourceFrameRate: FrameRate
): SourceFrameRange? {
    if (startPositionUs < 0L || endPositionUs <= startPositionUs ||
        sourceFrameRate.numerator <= 0 || sourceFrameRate.denominator <= 0
    ) return null

    val denominator = BigInteger.valueOf(1_000_000L)
        .multiply(BigInteger.valueOf(sourceFrameRate.denominator.toLong()))
    fun frameIndex(positionUs: Long, roundUp: Boolean): Long? {
        val numerator = BigInteger.valueOf(positionUs)
            .multiply(BigInteger.valueOf(sourceFrameRate.numerator.toLong()))
        val division = numerator.divideAndRemainder(denominator)
        val rounded = if (roundUp && division[1] != BigInteger.ZERO) division[0].add(BigInteger.ONE) else division[0]
        return runCatching { rounded.longValueExact() }.getOrNull()
    }

    val firstFrame = frameIndex(startPositionUs, roundUp = false) ?: return null
    val endFrame = frameIndex(endPositionUs, roundUp = true) ?: return null
    return SourceFrameRange(firstFrame, endFrame).takeIf { it.endFrameExclusive > it.startFrame }
}

internal fun mcrawRenderSize(
    sourceWidth: Int,
    sourceHeight: Int,
    rotationDegrees: Int,
    maximumEdge: Int
): McrawRenderSize? {
    if (sourceWidth < 2 || sourceHeight < 2 || maximumEdge < 2) return null
    val scale = minOf(1.0, maximumEdge.toDouble() / maxOf(sourceWidth, sourceHeight).toDouble())
    val sampledWidth = (sourceWidth * scale).roundToInt().coerceAtLeast(2)
    val sampledHeight = (sourceHeight * scale).roundToInt().coerceAtLeast(2)
    val rotation = ((rotationDegrees % 360) + 360) % 360
    if (rotation % 90 != 0) return null
    val swapsDimensions = rotation == 90 || rotation == 270
    val width = if (swapsDimensions) sampledHeight else sampledWidth
    val height = if (swapsDimensions) sampledWidth else sampledHeight
    val evenWidth = width and -2
    val evenHeight = height and -2
    return if (evenWidth >= 2 && evenHeight >= 2) McrawRenderSize(evenWidth, evenHeight) else null
}

/**
 * Develops a MotionCam RAW source into a temporary, video-only AVC/MP4 asset. Media3 Transformer
 * then performs the normal validated trims, supported clip effects, sequencing, and final encode.
 *
 * The current native Bayer renderer deliberately produces 8-bit sRGB pixels. This bridge is not a
 * 10-bit/RAW-preserving path and is only enabled when Android exposes a compatible flexible-YUV
 * H.264 encoder for the intermediate frame size.
 */
internal class McrawVideoTranscoder(private val context: Context) {
    data class Result(
        val frameCount: Int,
        val durationUs: Long,
        val trimStartUs: Long,
        val trimEndUs: Long
    )

    fun transcode(
        sourceUri: Uri,
        outputFile: File,
        maximumEdge: Int,
        sourceStartFrame: Long,
        sourceEndFrameExclusive: Long,
        sourceStartUs: Long,
        sourceEndUs: Long,
        frameRate: Rational,
        bitrate: Int,
        isCancelled: () -> Boolean,
        onFrame: (completedFrames: Int, totalFrames: Int) -> Unit
    ): Result {
        if (Build.VERSION.SDK_INT < Build.VERSION_CODES.M) {
            throw IOException("MotionCam RAW export requires Android 6 or newer for flexible YUV encoder input.")
        }
        val frameRateFloat = frameRate.toFloat()
        if (frameRate.numerator <= 0 || frameRate.denominator <= 0 ||
            !frameRateFloat.isFinite() || frameRateFloat <= 0f || bitrate <= 0
        ) {
            throw IOException("MotionCam RAW export received invalid H.264 frame-rate or bitrate settings.")
        }
        val nominalFrameRate = TimelineFrameMath.nominalFramesPerSecond(frameRate)
            ?.takeIf { it <= Int.MAX_VALUE.toLong() }
            ?: throw IOException("MotionCam RAW export frame rate cannot be represented by MediaCodec.")
        val frameDurationUs = TimelineFrameMath.microsecondsFromFrames(1L, frameRate)
            ?: throw IOException("MotionCam RAW frame duration overflows Long.")
        if (maximumEdge !in 64..MAX_RENDER_EDGE) {
            throw IOException("MotionCam RAW export resolution is outside the supported renderer limit.")
        }
        checkCancelled(isCancelled)

        var decoderHandle = 0L
        var encoder: MediaCodec? = null
        var muxer: MediaMuxer? = null
        var encoderStarted = false
        var bitmap: Bitmap? = null
        var drainStateForCleanup: DrainState? = null
        var succeeded = false

        try {
            outputFile.parentFile?.let { parent ->
                if (!parent.exists() && !parent.mkdirs()) throw IOException("Could not create MotionCam export cache directory.")
            }
            if (outputFile.exists() && !outputFile.delete()) throw IOException("Could not replace the temporary MotionCam export file.")

            decoderHandle = NativeMcrawDecoder.open(context, sourceUri)
            val sourceFrameCount = NativeMcrawDecoder.frameCount(decoderHandle)
            if (sourceFrameCount <= 0) throw IOException("MotionCam recording contains no video frames.")
            if (sourceStartFrame < 0L || sourceEndFrameExclusive <= sourceStartFrame ||
                sourceEndFrameExclusive > sourceFrameCount.toLong()
            ) {
                throw IOException("The selected MotionCam source-frame range is outside the recording.")
            }
            val sourceOriginTimestampNs = NativeMcrawDecoder.frameTimestampNs(decoderHandle, 0)
            var firstFrameIndex = sourceStartFrame.toInt()
            var endFrameIndexExclusive = sourceEndFrameExclusive.toInt()
            firstFrameIndex = frameAtOrBeforeTimeUs(
                decoderHandle,
                sourceFrameCount,
                sourceOriginTimestampNs,
                sourceStartUs,
                firstFrameIndex
            )
            endFrameIndexExclusive = firstFrameAtOrAfterTimeUs(
                decoderHandle,
                sourceFrameCount,
                sourceOriginTimestampNs,
                sourceEndUs,
                endFrameIndexExclusive
            )
            if (endFrameIndexExclusive <= firstFrameIndex) {
                throw IOException("The selected MotionCam trim does not contain a source video frame.")
            }
            val frameCount = endFrameIndexExclusive - firstFrameIndex
            if (sourceStartUs < 0L || sourceEndUs <= sourceStartUs) {
                throw IOException("The selected MotionCam source trim range is invalid.")
            }
            val firstTimestampNs = NativeMcrawDecoder.frameTimestampNs(decoderHandle, firstFrameIndex)
            val segmentStartOffsetUs = sourceTimestampOffsetUs(firstTimestampNs, sourceOriginTimestampNs)
            val trimStartUs = try {
                Math.subtractExact(sourceStartUs, segmentStartOffsetUs)
            } catch (_: ArithmeticException) {
                throw IOException("The selected MotionCam trim start overflows the decoded time range.")
            }
            val trimEndUs = try {
                Math.subtractExact(sourceEndUs, segmentStartOffsetUs)
            } catch (_: ArithmeticException) {
                throw IOException("The selected MotionCam trim end overflows the decoded time range.")
            }
            if (trimStartUs < 0L || trimEndUs <= trimStartUs) {
                throw IOException("The selected MotionCam trim range cannot be represented by the decoded frame timestamps.")
            }
            if (frameCount <= 0) throw IOException("The selected MotionCam trim contains no source frames.")
            val firstBitmap = NativeMcrawDecoder.decodeFrameInto(
                decoderHandle,
                index = firstFrameIndex,
                maximumEdge = maximumEdge,
                gradeParams = null,
                reusableBitmap = null
            )
            bitmap = firstBitmap
            val sourceBitmapWidth = firstBitmap.width
            val sourceBitmapHeight = firstBitmap.height
            val renderWidth = sourceBitmapWidth and -2
            val renderHeight = sourceBitmapHeight and -2
            if (renderWidth < 2 || renderHeight < 2) throw IOException("MotionCam renderer returned an invalid frame size.")
            val encoderInfo = findFlexibleYuvEncoder(renderWidth, renderHeight, frameRateFloat, bitrate)
                ?: throw IOException(
                    "No Android H.264 encoder with flexible YUV input supports the MotionCam intermediate " +
                        "${renderWidth}×${renderHeight} frame size at ${frameRate.numerator}/${frameRate.denominator} fps and $bitrate bit/s."
                )

            val videoFormat = MediaFormat.createVideoFormat(MediaFormat.MIMETYPE_VIDEO_AVC, renderWidth, renderHeight).apply {
                setInteger(MediaFormat.KEY_COLOR_FORMAT, MediaCodecInfo.CodecCapabilities.COLOR_FormatYUV420Flexible)
                setInteger(MediaFormat.KEY_BIT_RATE, bitrate)
                setInteger(MediaFormat.KEY_FRAME_RATE, nominalFrameRate.toInt())
                setInteger(MediaFormat.KEY_I_FRAME_INTERVAL, 1)
                setInteger(MediaFormat.KEY_COLOR_STANDARD, MediaFormat.COLOR_STANDARD_BT709)
                setInteger(MediaFormat.KEY_COLOR_TRANSFER, MediaFormat.COLOR_TRANSFER_SDR_VIDEO)
                setInteger(MediaFormat.KEY_COLOR_RANGE, MediaFormat.COLOR_RANGE_LIMITED)
            }
            val activeEncoder = MediaCodec.createByCodecName(encoderInfo.name)
            encoder = activeEncoder
            activeEncoder.configure(videoFormat, null, null, MediaCodec.CONFIGURE_FLAG_ENCODE)
            activeEncoder.start()
            encoderStarted = true
            val activeMuxer = MediaMuxer(outputFile.absolutePath, MediaMuxer.OutputFormat.MUXER_OUTPUT_MPEG_4)
            muxer = activeMuxer

            val state = DrainState()
            drainStateForCleanup = state
            val outputInfo = MediaCodec.BufferInfo()
            val rowA = IntArray(firstBitmap.width)
            val rowB = IntArray(firstBitmap.width)
            var workingBitmap = firstBitmap
            var previousPtsUs = -1L
            var lastPtsUs = 0L
            for (index in 0 until frameCount) {
                checkCancelled(isCancelled)
                val sourceFrameIndex = firstFrameIndex + index
                if (index > 0) {
                    validateRawFrameDimensions(decoderHandle, sourceFrameIndex)
                    val previousBitmap = workingBitmap
                    workingBitmap = NativeMcrawDecoder.decodeFrameInto(
                        decoderHandle,
                        index = sourceFrameIndex,
                        maximumEdge = maximumEdge,
                        gradeParams = null,
                        reusableBitmap = previousBitmap
                    )
                    bitmap = workingBitmap
                    if (workingBitmap !== previousBitmap && !previousBitmap.isRecycled) {
                        previousBitmap.recycle()
                    }
                }
                val frame = workingBitmap
                if (frame.width != sourceBitmapWidth || frame.height != sourceBitmapHeight) {
                    throw IOException("MotionCam frame dimensions changed during export.")
                }
                if ((frame.width and -2) != renderWidth || (frame.height and -2) != renderHeight) {
                    throw IOException("MotionCam frame dimensions changed during export.")
                }

                val timestampNs = NativeMcrawDecoder.frameTimestampNs(decoderHandle, sourceFrameIndex)
                val relativeNs = try {
                    Math.subtractExact(timestampNs, firstTimestampNs)
                } catch (_: ArithmeticException) {
                    throw IOException("MotionCam frame timestamp range overflows Long.")
                }
                if (relativeNs < 0L) throw IOException("MotionCam frame timestamps are not in source order.")
                val presentationTimeUs = relativeNs / 1_000L
                if (presentationTimeUs <= previousPtsUs) {
                    throw IOException("MotionCam frame timestamps are not strictly increasing at microsecond precision.")
                }
                previousPtsUs = presentationTimeUs
                lastPtsUs = presentationTimeUs

                queueVideoFrame(
                    codec = activeEncoder,
                    bitmap = frame,
                    targetWidth = renderWidth,
                    targetHeight = renderHeight,
                    presentationTimeUs = presentationTimeUs,
                    rowA = rowA,
                    rowB = rowB,
                    muxer = activeMuxer,
                    state = state,
                    outputInfo = outputInfo,
                    isCancelled = isCancelled
                )
                onFrame(index + 1, frameCount)
            }

            checkCancelled(isCancelled)
            val eosPresentationTimeUs = Math.addExact(lastPtsUs, frameDurationUs)
            queueEndOfStream(activeEncoder, eosPresentationTimeUs, activeMuxer, state, outputInfo, isCancelled)
            if (!state.muxerStarted || !state.outputEos || state.samplesWritten <= 0) {
                throw IOException("Android H.264 encoder ended before producing a complete MP4 video track.")
            }

            activeEncoder.stop()
            encoderStarted = false
            activeMuxer.stop()
            state.muxerStarted = false
            val durationUs = eosPresentationTimeUs
            if (!outputFile.isFile || outputFile.length() <= 0L) throw IOException("MotionCam conversion produced an empty MP4 file.")
            val maximumTrimEndUs = Math.addExact(durationUs, frameDurationUs)
            if (trimEndUs > maximumTrimEndUs) {
                throw IOException("The selected MotionCam trim extends beyond the generated intermediate video duration.")
            }
            succeeded = true
            return Result(frameCount, durationUs, trimStartUs, trimEndUs)
        } catch (cancelled: CancellationException) {
            throw cancelled
        } catch (failure: Exception) {
            throw if (failure is IOException) failure else IOException(
                failure.message?.let { "MotionCam RAW conversion failed: $it" } ?: "MotionCam RAW conversion failed.",
                failure
            )
        } finally {
            if (encoderStarted) runCatching { encoder?.stop() }
            runCatching { encoder?.release() }
            if (drainStateForCleanup?.muxerStarted == true) {
                runCatching { muxer?.stop() }
                drainStateForCleanup?.muxerStarted = false
            }
            runCatching { muxer?.release() }
            bitmap?.let { if (!it.isRecycled) it.recycle() }
            if (decoderHandle != 0L) NativeMcrawDecoder.close(decoderHandle)
            if (!succeeded) outputFile.delete()
        }
    }

    private fun frameAtOrBeforeTimeUs(
        handle: Long,
        frameCount: Int,
        originTimestampNs: Long,
        targetTimeUs: Long,
        hintedFrameIndex: Int
    ): Int {
        if (frameCount <= 0 || targetTimeUs < 0L) throw IOException("Invalid MotionCam source start time.")
        var frameIndex = hintedFrameIndex.coerceIn(0, frameCount - 1)
        while (frameIndex > 0 && sourceTimestampOffsetUs(
                NativeMcrawDecoder.frameTimestampNs(handle, frameIndex), originTimestampNs
            ) > targetTimeUs
        ) {
            frameIndex--
        }
        while (frameIndex + 1 < frameCount && sourceTimestampOffsetUs(
                NativeMcrawDecoder.frameTimestampNs(handle, frameIndex + 1), originTimestampNs
            ) <= targetTimeUs
        ) {
            frameIndex++
        }
        if (sourceTimestampOffsetUs(
                NativeMcrawDecoder.frameTimestampNs(handle, frameIndex), originTimestampNs
            ) > targetTimeUs
        ) {
            throw IOException("MotionCam has no source frame at or before the requested trim start.")
        }
        return frameIndex
    }

    private fun firstFrameAtOrAfterTimeUs(
        handle: Long,
        frameCount: Int,
        originTimestampNs: Long,
        targetTimeUs: Long,
        hintedFrameIndex: Int
    ): Int {
        if (frameCount <= 0 || targetTimeUs < 0L) throw IOException("Invalid MotionCam source end time.")
        var frameIndex = hintedFrameIndex.coerceIn(0, frameCount)
        while (frameIndex > 0 && sourceTimestampOffsetUs(
                NativeMcrawDecoder.frameTimestampNs(handle, frameIndex - 1), originTimestampNs
            ) >= targetTimeUs
        ) {
            frameIndex--
        }
        while (frameIndex < frameCount && sourceTimestampOffsetUs(
                NativeMcrawDecoder.frameTimestampNs(handle, frameIndex), originTimestampNs
            ) < targetTimeUs
        ) {
            frameIndex++
        }
        return frameIndex
    }

    private fun sourceTimestampOffsetUs(timestampNs: Long, originTimestampNs: Long): Long {
        if (timestampNs < originTimestampNs) throw IOException("MotionCam frame timestamps are not in source order.")
        val offsetNs = try {
            Math.subtractExact(timestampNs, originTimestampNs)
        } catch (_: ArithmeticException) {
            throw IOException("MotionCam frame timestamp range is too large to represent safely.")
        }
        return offsetNs / 1_000L
    }

    private fun validateRawFrameDimensions(handle: Long, frameIndex: Int) {
        val metadata = JSONObject(NativeMcrawDecoder.frameMetadataJson(handle, frameIndex))
        val width = metadata.optInt("width", 0)
        val height = metadata.optInt("height", 0)
        val pixels = width.toLong() * height.toLong()
        if (width < 2 || height < 2 || pixels > MAX_SOURCE_RAW_PIXELS) {
            throw IOException(
                "MotionCam RAW frame ${width}×${height} exceeds the safe export decode limit " +
                    "(${MAX_SOURCE_RAW_PIXELS / 1_000_000L} megapixels)."
            )
        }
    }

    private fun queueVideoFrame(
        codec: MediaCodec,
        bitmap: Bitmap,
        targetWidth: Int,
        targetHeight: Int,
        presentationTimeUs: Long,
        rowA: IntArray,
        rowB: IntArray,
        muxer: MediaMuxer,
        state: DrainState,
        outputInfo: MediaCodec.BufferInfo,
        isCancelled: () -> Boolean
    ) {
        val timeoutUs = CODEC_DEQUEUE_TIMEOUT_US
        val inputDeadlineMs = SystemClock.elapsedRealtime() + INPUT_BUFFER_TIMEOUT_MS
        while (true) {
            checkCancelled(isCancelled)
            val inputIndex = codec.dequeueInputBuffer(timeoutUs)
            if (inputIndex >= 0) {
                // Read the capacity before getInputImage(): MediaCodec invalidates any ByteBuffer
                // view for this index when an Image view is requested.
                val inputSize = codec.getInputBuffer(inputIndex)?.remaining()
                    ?: throw IOException("Android H.264 encoder returned no input buffer.")
                val image = codec.getInputImage(inputIndex)
                    ?: throw IOException("Android H.264 encoder does not expose writable flexible-YUV frames.")
                try {
                    writeBitmapToYuvImage(bitmap, targetWidth, targetHeight, image, rowA, rowB)
                } finally {
                    image.close()
                }
                codec.queueInputBuffer(inputIndex, 0, inputSize, presentationTimeUs, 0)
                drainEncoder(codec, muxer, state, outputInfo, timeoutUs = 0L)
                return
            }
            drainEncoder(codec, muxer, state, outputInfo, timeoutUs = timeoutUs)
            if (SystemClock.elapsedRealtime() >= inputDeadlineMs) {
                throw IOException("Android H.264 encoder did not provide an input buffer in time.")
            }
        }
    }

    private fun queueEndOfStream(
        codec: MediaCodec,
        presentationTimeUs: Long,
        muxer: MediaMuxer,
        state: DrainState,
        outputInfo: MediaCodec.BufferInfo,
        isCancelled: () -> Boolean
    ) {
        val deadlineMs = SystemClock.elapsedRealtime() + EOS_TIMEOUT_MS
        while (true) {
            checkCancelled(isCancelled)
            val inputIndex = codec.dequeueInputBuffer(CODEC_DEQUEUE_TIMEOUT_US)
            if (inputIndex >= 0) {
                codec.queueInputBuffer(
                    inputIndex,
                    0,
                    0,
                    presentationTimeUs,
                    MediaCodec.BUFFER_FLAG_END_OF_STREAM
                )
                break
            }
            drainEncoder(codec, muxer, state, outputInfo, timeoutUs = CODEC_DEQUEUE_TIMEOUT_US)
            if (SystemClock.elapsedRealtime() >= deadlineMs) {
                throw IOException("Android H.264 encoder did not accept the end-of-stream signal.")
            }
        }

        while (!state.outputEos) {
            checkCancelled(isCancelled)
            drainEncoder(codec, muxer, state, outputInfo, timeoutUs = CODEC_DEQUEUE_TIMEOUT_US)
            if (SystemClock.elapsedRealtime() >= deadlineMs) {
                throw IOException("Android H.264 encoder did not finish the MotionCam video track.")
            }
        }
    }

    private fun drainEncoder(
        codec: MediaCodec,
        muxer: MediaMuxer,
        state: DrainState,
        outputInfo: MediaCodec.BufferInfo,
        timeoutUs: Long
    ) {
        while (true) {
            when (val outputIndex = codec.dequeueOutputBuffer(outputInfo, timeoutUs)) {
                MediaCodec.INFO_TRY_AGAIN_LATER -> return
                MediaCodec.INFO_OUTPUT_FORMAT_CHANGED -> {
                    if (state.trackIndex >= 0) throw IOException("Android H.264 encoder changed its output format more than once.")
                    state.trackIndex = muxer.addTrack(codec.outputFormat)
                    muxer.start()
                    state.muxerStarted = true
                }
                MediaCodec.INFO_OUTPUT_BUFFERS_CHANGED -> Unit
                else -> if (outputIndex >= 0) {
                    try {
                        if (outputInfo.size > 0 && outputInfo.flags and MediaCodec.BUFFER_FLAG_CODEC_CONFIG == 0) {
                            if (!state.muxerStarted || state.trackIndex < 0) {
                                throw IOException("Android H.264 encoder produced samples before its output format.")
                            }
                            val encodedData = codec.getOutputBuffer(outputIndex)
                                ?: throw IOException("Android H.264 encoder returned an empty output buffer.")
                            encodedData.position(outputInfo.offset)
                            encodedData.limit(outputInfo.offset + outputInfo.size)
                            muxer.writeSampleData(state.trackIndex, encodedData, outputInfo)
                            state.samplesWritten++
                        }
                        if (outputInfo.flags and MediaCodec.BUFFER_FLAG_END_OF_STREAM != 0) state.outputEos = true
                    } finally {
                        codec.releaseOutputBuffer(outputIndex, false)
                    }
                }
            }
        }
    }

    private fun writeBitmapToYuvImage(
        bitmap: Bitmap,
        targetWidth: Int,
        targetHeight: Int,
        image: Image,
        rowA: IntArray,
        rowB: IntArray
    ) {
        if (image.format != android.graphics.ImageFormat.YUV_420_888 ||
            image.width != targetWidth || image.height != targetHeight ||
            bitmap.width != rowA.size || bitmap.width != rowB.size ||
            bitmap.width < targetWidth || bitmap.height < targetHeight
        ) {
            throw IOException("Android returned an incompatible flexible-YUV input frame.")
        }
        val planes = image.planes
        if (planes.size != 3) throw IOException("Android flexible-YUV input did not provide three image planes.")
        val yPlane = PlaneWriter(planes[0])
        val uPlane = PlaneWriter(planes[1])
        val vPlane = PlaneWriter(planes[2])

        for (y in 0 until targetHeight) {
            val sourceY = nearestSourceCoordinate(y, targetHeight, bitmap.height)
            bitmap.getPixels(rowA, 0, bitmap.width, 0, sourceY, bitmap.width, 1)
            for (x in 0 until targetWidth) {
                val sourceX = nearestSourceCoordinate(x, targetWidth, bitmap.width)
                val pixel = rowA[sourceX]
                val red = pixel ushr 16 and 0xff
                val green = pixel ushr 8 and 0xff
                val blue = pixel and 0xff
                yPlane.put(x, y, rgbToY(red, green, blue))
            }
        }

        for (chromaY in 0 until targetHeight / 2) {
            val targetY0 = chromaY * 2
            val targetY1 = targetY0 + 1
            val sourceY0 = nearestSourceCoordinate(targetY0, targetHeight, bitmap.height)
            val sourceY1 = nearestSourceCoordinate(targetY1, targetHeight, bitmap.height)
            bitmap.getPixels(rowA, 0, bitmap.width, 0, sourceY0, bitmap.width, 1)
            bitmap.getPixels(rowB, 0, bitmap.width, 0, sourceY1, bitmap.width, 1)
            for (chromaX in 0 until targetWidth / 2) {
                val targetX0 = chromaX * 2
                val targetX1 = targetX0 + 1
                val sourceX0 = nearestSourceCoordinate(targetX0, targetWidth, bitmap.width)
                val sourceX1 = nearestSourceCoordinate(targetX1, targetWidth, bitmap.width)
                val pixel00 = rowA[sourceX0]
                val pixel01 = rowA[sourceX1]
                val pixel10 = rowB[sourceX0]
                val pixel11 = rowB[sourceX1]
                val red = (
                    (pixel00 ushr 16 and 0xff) + (pixel01 ushr 16 and 0xff) +
                        (pixel10 ushr 16 and 0xff) + (pixel11 ushr 16 and 0xff)
                    ) / 4
                val green = (
                    (pixel00 ushr 8 and 0xff) + (pixel01 ushr 8 and 0xff) +
                        (pixel10 ushr 8 and 0xff) + (pixel11 ushr 8 and 0xff)
                    ) / 4
                val blue = (
                    (pixel00 and 0xff) + (pixel01 and 0xff) +
                        (pixel10 and 0xff) + (pixel11 and 0xff)
                    ) / 4
                uPlane.put(chromaX, chromaY, rgbToU(red, green, blue))
                vPlane.put(chromaX, chromaY, rgbToV(red, green, blue))
            }
        }
    }

    private fun nearestSourceCoordinate(destination: Int, destinationSize: Int, sourceSize: Int): Int =
        (((destination.toLong() * 2L + 1L) * sourceSize) / (destinationSize.toLong() * 2L))
            .toInt()
            .coerceIn(0, sourceSize - 1)

    private fun rgbToY(red: Int, green: Int, blue: Int): Byte =
        (16.0 + 0.182586 * red + 0.614231 * green + 0.062007 * blue).roundToInt().coerceIn(16, 235).toByte()

    private fun rgbToU(red: Int, green: Int, blue: Int): Byte =
        (128.0 - 0.100644 * red - 0.338572 * green + 0.439216 * blue).roundToInt().coerceIn(16, 240).toByte()

    private fun rgbToV(red: Int, green: Int, blue: Int): Byte =
        (128.0 + 0.439216 * red - 0.398942 * green - 0.040274 * blue).roundToInt().coerceIn(16, 240).toByte()

    private class PlaneWriter(plane: Image.Plane) {
        private val buffer: ByteBuffer = plane.buffer
        private val basePosition = buffer.position()
        private val rowStride = plane.rowStride
        private val pixelStride = plane.pixelStride

        fun put(x: Int, y: Int, value: Byte) {
            val index = basePosition + y * rowStride + x * pixelStride
            if (index < basePosition || index >= buffer.limit()) {
                throw IOException("Android flexible-YUV plane has an invalid stride.")
            }
            buffer.put(index, value)
        }
    }

    private data class DrainState(
        var trackIndex: Int = -1,
        var muxerStarted: Boolean = false,
        var outputEos: Boolean = false,
        var samplesWritten: Int = 0
    )

    companion object {
        const val MAX_RENDER_EDGE = 3840
        const val MAX_SOURCE_RAW_PIXELS = 24_000_000L
        private const val CODEC_DEQUEUE_TIMEOUT_US = 10_000L
        private const val INPUT_BUFFER_TIMEOUT_MS = 10_000L
        private const val EOS_TIMEOUT_MS = 30_000L

        fun hasCompatibleEncoder(width: Int, height: Int, frameRate: Float, bitrate: Int): Boolean =
            findFlexibleYuvEncoder(width, height, frameRate, bitrate) != null

        private fun findFlexibleYuvEncoder(
            width: Int,
            height: Int,
            frameRate: Float,
            bitrate: Int
        ): MediaCodecInfo? {
            if (width < 2 || height < 2 || width % 2 != 0 || height % 2 != 0 ||
                !frameRate.isFinite() || frameRate <= 0f || bitrate <= 0
            ) return null
            return runCatching {
                MediaCodecList(MediaCodecList.ALL_CODECS).codecInfos.firstOrNull { codecInfo ->
                    codecInfo.isEncoder && codecInfo.supportedTypes.any {
                        it.equals(MediaFormat.MIMETYPE_VIDEO_AVC, ignoreCase = true)
                    } && runCatching {
                        val capabilities = codecInfo.getCapabilitiesForType(MediaFormat.MIMETYPE_VIDEO_AVC)
                        val video = capabilities.videoCapabilities ?: return@runCatching false
                        val encoder = capabilities.encoderCapabilities ?: return@runCatching false
                        MediaCodecInfo.CodecCapabilities.COLOR_FormatYUV420Flexible in capabilities.colorFormats &&
                            video.areSizeAndRateSupported(width, height, frameRate.toDouble()) &&
                            encoder.getBitrateRange().contains(bitrate)
                    }.getOrDefault(false)
                }
            }.getOrNull()
        }

        private fun checkCancelled(isCancelled: () -> Boolean) {
            if (isCancelled()) throw CancellationException("MotionCam RAW export was cancelled.")
        }
    }
}
