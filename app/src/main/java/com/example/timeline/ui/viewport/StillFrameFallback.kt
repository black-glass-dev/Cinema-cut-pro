package com.example.timeline.ui.viewport

import android.graphics.Bitmap
import android.media.MediaMetadataRetriever
import android.net.Uri
import androidx.compose.foundation.Image
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.asImageBitmap
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.platform.LocalContext
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext

import com.example.timeline.engine.preview.PreviewRenderPipeline
import com.example.timeline.core.TimelineProject
import com.example.timeline.core.transform.ClipTransform
import com.example.timeline.engine.preview.PreviewTransformAdapter
import com.example.timeline.engine.preview.ViewportTransformLayer

@Composable
fun StillFrameFallback(
    uriString: String, 
    timeUs: Long, 
    project: TimelineProject?, 
    projectFrame: Long?, 
    clipTransform: ClipTransform = ClipTransform(),
    modifier: Modifier = Modifier
) {
    val context = LocalContext.current
    val bitmapState = remember { mutableStateOf<Bitmap?>(null) }
    
    // Cache the raw extracted frame to avoid slow MediaMetadataRetriever calls during slider drags
    val rawBitmapState = remember { mutableStateOf<Bitmap?>(null) }
    val lastUriTime = remember { mutableStateOf<Pair<String, Long>?>(null) }

    val renderState = remember(clipTransform, projectFrame) {
        PreviewTransformAdapter.adaptTransformForEngine(
            clipTransform, 
            com.example.timeline.engine.preview.PreviewEngineType.STILL_FRAME, 
            projectFrame ?: 0L
        )
    }

    LaunchedEffect(uriString, timeUs) {
        if (lastUriTime.value?.first != uriString || lastUriTime.value?.second != timeUs) {
            withContext(Dispatchers.IO) {
                try {
                    val retriever = MediaMetadataRetriever()
                    val rawBitmap = try {
                        val sourceUri = Uri.parse(uriString)
                        if (sourceUri.scheme == "content") retriever.setDataSource(context, sourceUri)
                        else retriever.setDataSource(sourceUri.path ?: uriString)
                        retriever.getFrameAtTime(timeUs, MediaMetadataRetriever.OPTION_CLOSEST_SYNC)
                    } finally {
                        runCatching { retriever.release() }
                    }

                    if (rawBitmap != null) {
                        var bitmap = rawBitmap
                        val maxDimension = 640
                        val width = bitmap.width
                        val height = bitmap.height
                        if (width > maxDimension || height > maxDimension) {
                            val ratio = Math.min(maxDimension.toFloat() / width, maxDimension.toFloat() / height)
                            val newWidth = Math.round(ratio * width)
                            val newHeight = Math.round(ratio * height)
                            val scaledBitmap = Bitmap.createScaledBitmap(bitmap, newWidth, newHeight, true)
                            if (scaledBitmap != bitmap) {
                                bitmap.recycle()
                                bitmap = scaledBitmap
                            }
                        }
                        rawBitmapState.value = bitmap
                        lastUriTime.value = Pair(uriString, timeUs)
                    }
                } catch (e: Exception) {
                    if (com.example.BuildConfig.DEBUG) e.printStackTrace()
                }
            }
        }
    }

    LaunchedEffect(rawBitmapState.value, project, projectFrame, clipTransform.motionBlurParams) {
        val rawBmp = rawBitmapState.value
        if (rawBmp != null && project != null && projectFrame != null) {
            // Apply cpu grading dynamically
            val gradedBmp = PreviewRenderPipeline.renderPreviewFrame(project, projectFrame, rawBmp)
            
            // Apply native CPU motion blur if enabled
            if (clipTransform.motionBlurParams.enabled && gradedBmp.config == Bitmap.Config.ARGB_8888) {
                try {
                    val w = gradedBmp.width
                    val h = gradedBmp.height
                    val size = w * h * 4
                    val inBuffer = java.nio.ByteBuffer.allocate(size)
                    gradedBmp.copyPixelsToBuffer(inBuffer)
                    val inArray = inBuffer.array()
                    val outArray = ByteArray(size)
                    
                    val prevTx = clipTransform.evaluateTransformAtFrame(projectFrame - 1)
                    val currTx = clipTransform.evaluateTransformAtFrame(projectFrame)
                    val nextTx = clipTransform.evaluateTransformAtFrame(projectFrame + 1)
                    
                    com.example.timeline.engine.preview.MotionBlurEngine.nativeApplyTransformMotionBlurRgba8888(
                        outPixels = outArray,
                        inPixels = inArray,
                        width = w,
                        height = h,
                        currX = currTx.positionX, currY = currTx.positionY, currRot = currTx.rotationDegrees, currScaleX = currTx.scaleX, currScaleY = currTx.scaleY,
                        prevX = prevTx.positionX, prevY = prevTx.positionY, prevRot = prevTx.rotationDegrees, prevScaleX = prevTx.scaleX, prevScaleY = prevTx.scaleY,
                        nextX = nextTx.positionX, nextY = nextTx.positionY, nextRot = nextTx.rotationDegrees, nextScaleX = nextTx.scaleX, nextScaleY = nextTx.scaleY,
                        anchorX = currTx.anchorPointX, anchorY = currTx.anchorPointY,
                        shutterAngle = clipTransform.motionBlurParams.shutterAngle,
                        sampleCount = clipTransform.motionBlurParams.sampleCount,
                        strength = clipTransform.motionBlurParams.strength
                    )
                    
                    val outBmp = Bitmap.createBitmap(w, h, Bitmap.Config.ARGB_8888)
                    outBmp.copyPixelsFromBuffer(java.nio.ByteBuffer.wrap(outArray))
                    bitmapState.value = outBmp
                } catch (e: Exception) {
                    if (com.example.BuildConfig.DEBUG) e.printStackTrace()
                    bitmapState.value = gradedBmp
                }
            } else {
                bitmapState.value = gradedBmp
            }
        } else {
            bitmapState.value = rawBmp
        }
    }

    Box(modifier = modifier.fillMaxSize()) {
        ViewportTransformLayer(renderState = renderState) {
            bitmapState.value?.let { bmp ->
                Image(
                    bitmap = bmp.asImageBitmap(),
                    contentDescription = "Still frame",
                    modifier = Modifier.fillMaxSize(),
                    contentScale = ContentScale.Fit
                )
            }
        }
    }
}
