package com.example.timeline.export.pipeline

import android.content.Context
import android.media.MediaCodec
import android.net.Uri
import android.util.Log
import com.example.timeline.core.Rational
import com.example.timeline.core.TimelineFrameMath
import com.example.timeline.core.TimelineProject
import com.example.timeline.export.model.ExportJob
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import java.io.File
import java.nio.ByteBuffer
import java.nio.ByteOrder
import com.example.timeline.export.resolver.TimelineRenderResolver
import com.example.timeline.export.performance.ExportPerformanceMonitor
import com.example.timeline.export.performance.ExportMemoryManager

class VideoExportPipeline(
    private val context: Context,
    private val project: TimelineProject
) {
    suspend fun runExport(
        job: ExportJob,
        outputFilePath: String,
        cancellationToken: ExportCancellationToken,
        progressTracker: ExportProgressTracker
    ) {
        val perfMonitor = ExportPerformanceMonitor()
        
        withContext(Dispatchers.IO) {
            var muxerWriter: MediaMuxerWriter? = null
            var encoder: MediaCodecVideoEncoder? = null
            var audioEncoder: AudioEncoder? = null
            
            val sources = mutableMapOf<String, ExportDecodeSource>()
            val decodedFrames = mutableMapOf<String, DecodedFrame?>()
            val audioSources = mutableMapOf<String, AudioStreamProvider>()

            try {
                perfMonitor.start()
                // Initialize Resolver
                val resolver = TimelineRenderResolver(project, job.settings)
                
                // Setup Video Encoder
                val targetBitRate = job.settings.videoBitrate
                encoder = MediaCodecVideoEncoder(
                    width = job.settings.resolutionWidth,
                    height = job.settings.resolutionHeight,
                    bitRate = targetBitRate,
                    frameRate = TimelineFrameMath.nominalFramesPerSecond(
                        Rational(job.settings.frameRate.numerator, job.settings.frameRate.denominator)
                    )?.toInt() ?: throw IllegalArgumentException("Invalid output frame rate"),
                    mimeType = when (job.settings.codec.name) {
                        "HEVC" -> android.media.MediaFormat.MIMETYPE_VIDEO_HEVC
                        "AV1" -> "video/av01"
                        else -> android.media.MediaFormat.MIMETYPE_VIDEO_AVC
                    }
                )
                encoder.prepare()
                
                // Setup Audio Encoder
                val aSampleRate = job.settings.audioSampleRate
                val aChannels = job.settings.audioChannels
                val aBitRate = job.settings.audioBitrate
                audioEncoder = AudioEncoder(aSampleRate, aChannels, aBitRate)
                audioEncoder.prepare()
                val audioMixer = AudioMixer(aSampleRate, aChannels)

                // Setup Muxer
                muxerWriter = MediaMuxerWriter(outputFilePath, android.media.MediaMuxer.OutputFormat.MUXER_OUTPUT_MPEG_4)

                val mediaCodecEncoder = encoder.getEncoder() ?: throw Exception("Encoder failed to create")
                val mediaCodecAudioEncoder = audioEncoder.getEncoder() ?: throw Exception("Audio encoder failed to create")
                
                var videoTrackIndex = -1
                var audioTrackIndex = -1
                var muxerStarted = false

                // Check watermark
                var watermarkBuffer: java.nio.ByteBuffer? = null
                if (job.settings.includeWatermark && job.settings.watermarkSettings?.enabled == true) {
                    val wmSettings = job.settings.watermarkSettings!!
                    val wmStartTime = System.currentTimeMillis()
                    watermarkBuffer = com.example.ui.export.WatermarkRenderer.getWatermarkRgbaBuffer(context, wmSettings, job.settings.resolutionWidth, job.settings.resolutionHeight)
                    perfMonitor.nativeStats.memoryAllocatedBytes += watermarkBuffer.capacity()
                    perfMonitor.nativeStats.nativeRenderTimeMs += (System.currentTimeMillis() - wmStartTime)
                }

                val totalFrames = progressTracker.totalFrames
                if (totalFrames <= 0L) throw IllegalStateException("The export contains no frames.")
                val bufferInfo = MediaCodec.BufferInfo()
                val audioBufferInfo = MediaCodec.BufferInfo()
                val outputFrameRate = Rational(
                    job.settings.frameRate.numerator,
                    job.settings.frameRate.denominator
                )
                var lastVideoPresentationTimeUs = -1L
                fun audioSampleBoundary(outputFrame: Long): Long = TimelineFrameMath.convertFrameCount(
                    frameCount = outputFrame,
                    sourceNumerator = outputFrameRate.numerator,
                    sourceDenominator = outputFrameRate.denominator,
                    projectNumerator = aSampleRate,
                    projectDenominator = 1
                ) ?: throw ArithmeticException("Audio sample boundary overflows Long")

                for (frameIndex in 0 until totalFrames) {
                    if (cancellationToken.isCancelled()) {
                        progressTracker.fail("Export cancelled by user")
                        break
                    }

                    // 1. Get Plan
                    val plan = resolver.createPlanForFrame(frameIndex)
                    var yuvBuffer: java.nio.ByteBuffer? = null
                    val presentationTimeUs = plan.outputTimeUs
                    val expectedPresentationTimeUs = TimelineFrameMath.microsecondsFromFrames(
                        frameIndex,
                        outputFrameRate
                    ) ?: throw ArithmeticException("Output frame timestamp overflows Long")
                    if (plan.outputFrameIndex != frameIndex || presentationTimeUs != expectedPresentationTimeUs ||
                        presentationTimeUs < 0L || presentationTimeUs <= lastVideoPresentationTimeUs
                    ) {
                        throw IllegalStateException("Output frame timestamps are invalid or not strictly increasing")
                    }
                    lastVideoPresentationTimeUs = presentationTimeUs
                    var dummyBufferUsed = false
                    var emptyBuf: java.nio.ByteBuffer? = null
                    
                    val topLayer = plan.activeVideoLayers.lastOrNull { it.isVideoEnabled }

                    if (topLayer != null && topLayer.mediaAsset != null) {
                        val assetId = topLayer.mediaAsset.assetId
                        var source = sources[assetId]
                        if (source == null) {
                            source = ExportDecodeSource(context, Uri.parse(topLayer.mediaAsset.localOriginalUriString ?: topLayer.mediaAsset.originalUriString))
                            source.prepare()
                            sources[assetId] = source
                            if (com.example.BuildConfig.DEBUG) Log.d("ExportPipeline", "Prepared decode source: $assetId")
                        }
                        
                        val targetTimeUs = topLayer.sourceTimeUs
                        var currentDecoded = decodedFrames[assetId]
                        
                        val seekAheadLimitUs = Math.addExact(targetTimeUs, 100_000L)
                        val seekBehindLimitUs = Math.subtractExact(targetTimeUs, 1_000_000L)
                        val advanceUntilUs = Math.subtractExact(targetTimeUs, 30_000L)
                        // Check if we need to seek; all threshold arithmetic is checked.
                        if (currentDecoded == null || currentDecoded.presentationTimeUs > seekAheadLimitUs ||
                            currentDecoded.presentationTimeUs < seekBehindLimitUs
                        ) {
                            source.seekTo(targetTimeUs)
                            currentDecoded?.let { source.releaseFrame(it.bufferIndex) }
                            currentDecoded = source.getNextFrame(10000)
                        }

                        // Advance until we match or exceed target time (minus a small epsilon to avoid overshooting).
                        var eos = false
                        while (currentDecoded != null && currentDecoded.presentationTimeUs < advanceUntilUs && !eos) {
                            source.releaseFrame(currentDecoded.bufferIndex)
                            val nextFrame = source.getNextFrame(10000)
                            if (nextFrame != null) {
                                currentDecoded = nextFrame
                                if (nextFrame.isEndOfStream) eos = true
                            } else {
                                break // No more frames ready
                            }
                        }
                        
                        decodedFrames[assetId] = currentDecoded
                        yuvBuffer = currentDecoded?.buffer
                    }

                    // 2. Feed to Video Encoder
                    if (yuvBuffer != null) {
                        yuvBuffer.rewind()
                        watermarkBuffer?.let { wmBuffer ->
                            if (com.example.timeline.export.native.NativeExportCore.isAvailable()) {
                                val startBlend = System.currentTimeMillis()
                                com.example.timeline.export.native.NativeExportCore.nativeBlendWatermarkYuv(
                                    yuvBuffer, wmBuffer, job.settings.resolutionWidth, job.settings.resolutionHeight
                                )
                                perfMonitor.nativeStats.nativeCompositeTimeMs += (System.currentTimeMillis() - startBlend)
                            }
                        }
                        perfMonitor.onFrameProcessed()
                        encoder.encodeFrame(yuvBuffer, presentationTimeUs)
                    } else {
                       val size = job.settings.resolutionWidth * job.settings.resolutionHeight * 3 / 2
                       emptyBuf = ExportMemoryManager.obtainBuffer(size)
                       dummyBufferUsed = true
                       
                       watermarkBuffer?.let { wmBuffer ->
                           if (com.example.timeline.export.native.NativeExportCore.isAvailable()) {
                               val startBlend = System.currentTimeMillis()
                               com.example.timeline.export.native.NativeExportCore.nativeBlendWatermarkYuv(
                                   emptyBuf, wmBuffer, job.settings.resolutionWidth, job.settings.resolutionHeight
                               )
                               perfMonitor.nativeStats.nativeCompositeTimeMs += (System.currentTimeMillis() - startBlend)
                           }
                       }
                       perfMonitor.onFrameProcessed()
                       encoder.encodeFrame(emptyBuf, presentationTimeUs)
                    }
                    
                    if (frameIndex == totalFrames - 1) {
                         encoder.signalEndOfInputStream()
                    }
                    
                    // 3. Process Audio
                    val nextFrameIndex = Math.addExact(frameIndex, 1L)
                    val startSample = audioSampleBoundary(frameIndex)
                    val endSample = audioSampleBoundary(nextFrameIndex)
                    val sampleCount = Math.subtractExact(endSample, startSample)
                    if (sampleCount < 0L || sampleCount > Int.MAX_VALUE.toLong()) {
                        throw ArithmeticException("Audio sample buffer exceeds the platform limit")
                    }
                    val numSamples = sampleCount.toInt()
                    val bytesToRead = Math.multiplyExact(Math.multiplyExact(numSamples, aChannels), 2)
                    
                    if (bytesToRead > 0) {
                        val destAudioBuffer = ByteBuffer.allocateDirect(bytesToRead).order(ByteOrder.nativeOrder())
                        var audioLayerCount = 0
                        
                        for (layer in plan.activeAudioLayers) {
                            if (layer.mediaAsset != null) {
                                val assetId = layer.mediaAsset.assetId
                                var aSource = audioSources[assetId]
                                if (aSource == null) {
                                    aSource = AudioStreamProvider(context, Uri.parse(layer.mediaAsset.localOriginalUriString ?: layer.mediaAsset.originalUriString), aSampleRate, aChannels)
                                    aSource.prepare()
                                    aSource.seekTo(layer.sourceTimeUs)
                                    audioSources[assetId] = aSource
                                }
                                
                                val tempArray = ByteArray(bytesToRead)
                                val read = aSource.pullBytes(tempArray, 0, bytesToRead)
                                if (read > 0) {
                                    val srcBuffer = ByteBuffer.allocateDirect(bytesToRead).order(ByteOrder.nativeOrder())
                                    srcBuffer.put(tempArray, 0, read)
                                    srcBuffer.flip()
                                    
                                    audioMixer.mixAndScale(srcBuffer, destAudioBuffer, read / (aChannels * 2), 1.0f)
                                    audioLayerCount++
                                }
                            }
                        }
                        
                        if (audioLayerCount > 0) {
                            destAudioBuffer.position(0)
                            destAudioBuffer.limit(bytesToRead)
                            audioEncoder.encodeFrame(destAudioBuffer, presentationTimeUs)
                        } else {
                            destAudioBuffer.clear()
                            destAudioBuffer.put(ByteArray(bytesToRead))
                            destAudioBuffer.flip()
                            audioEncoder.encodeFrame(destAudioBuffer, presentationTimeUs)
                        }
                    }
                    
                    if (frameIndex == totalFrames - 1) {
                        audioEncoder.signalEndOfInputStream()
                    }

                    // 4. Drain Encoders
                    var drainVideoCompleted = false
                    while (!drainVideoCompleted && !cancellationToken.isCancelled()) {
                        val encoderStatus = mediaCodecEncoder.dequeueOutputBuffer(bufferInfo, 10000)
                        if (encoderStatus == MediaCodec.INFO_TRY_AGAIN_LATER) {
                            drainVideoCompleted = true
                        } else if (encoderStatus == MediaCodec.INFO_OUTPUT_FORMAT_CHANGED) {
                            val newFormat = mediaCodecEncoder.outputFormat
                            videoTrackIndex = muxerWriter.addVideoTrack(newFormat)
                            if (audioTrackIndex >= 0) {
                                muxerWriter.start()
                                muxerStarted = true
                            }
                        } else if (encoderStatus >= 0) {
                            val encodedData = mediaCodecEncoder.getOutputBuffer(encoderStatus)
                            if (encodedData != null) {
                                if ((bufferInfo.flags and MediaCodec.BUFFER_FLAG_CODEC_CONFIG) != 0) {
                                    bufferInfo.size = 0
                                }
                                if (bufferInfo.size != 0) {
                                    encodedData.position(bufferInfo.offset)
                                    encodedData.limit(bufferInfo.offset + bufferInfo.size)
                                    muxerWriter.writeVideo(encodedData, bufferInfo)
                                    perfMonitor.onFrameEncoded()
                                }
                                mediaCodecEncoder.releaseOutputBuffer(encoderStatus, false)
                                if ((bufferInfo.flags and MediaCodec.BUFFER_FLAG_END_OF_STREAM) != 0) {
                                    drainVideoCompleted = true
                                }
                            }
                        }
                    }
                    
                    var drainAudioCompleted = false
                    while (!drainAudioCompleted && !cancellationToken.isCancelled()) {
                        val encoderStatus = mediaCodecAudioEncoder.dequeueOutputBuffer(audioBufferInfo, 10000)
                        if (encoderStatus == MediaCodec.INFO_TRY_AGAIN_LATER) {
                            drainAudioCompleted = true
                        } else if (encoderStatus == MediaCodec.INFO_OUTPUT_FORMAT_CHANGED) {
                            val newFormat = mediaCodecAudioEncoder.outputFormat
                            audioTrackIndex = muxerWriter.addAudioTrack(newFormat)
                            if (videoTrackIndex >= 0) {
                                muxerWriter.start()
                                muxerStarted = true
                            }
                        } else if (encoderStatus >= 0) {
                            val encodedData = mediaCodecAudioEncoder.getOutputBuffer(encoderStatus)
                            if (encodedData != null) {
                                if ((audioBufferInfo.flags and MediaCodec.BUFFER_FLAG_CODEC_CONFIG) != 0) {
                                    audioBufferInfo.size = 0
                                }
                                if (audioBufferInfo.size != 0) {
                                    encodedData.position(audioBufferInfo.offset)
                                    encodedData.limit(audioBufferInfo.offset + audioBufferInfo.size)
                                    muxerWriter.writeAudio(encodedData, audioBufferInfo)
                                }
                                mediaCodecAudioEncoder.releaseOutputBuffer(encoderStatus, false)
                                if ((audioBufferInfo.flags and MediaCodec.BUFFER_FLAG_END_OF_STREAM) != 0) {
                                    drainAudioCompleted = true
                                }
                            }
                        }
                    }
                    
                    if (dummyBufferUsed && emptyBuf != null) {
                        ExportMemoryManager.releaseBuffer(emptyBuf)
                    }

                    progressTracker.updateProgress(1L)
                }

                if (!cancellationToken.isCancelled()) {
                    progressTracker.complete()
                    perfMonitor.generateReport()
                }
            } catch (e: Exception) {
                if (com.example.BuildConfig.DEBUG) e.printStackTrace()
                progressTracker.fail(e.message ?: "Export failed due to unknown error")
            } finally {
                decodedFrames.forEach { (assetId, frame) ->
                    frame?.let { sources[assetId]?.releaseFrame(it.bufferIndex) }
                }
                sources.values.forEach { it.release() }
                sources.clear()
                audioSources.values.forEach { it.release() }
                audioSources.clear()
                encoder?.release()
                audioEncoder?.release()
                muxerWriter?.stopAndRelease()
                ExportMemoryManager.clearPool()
            }
        }
    }
}

