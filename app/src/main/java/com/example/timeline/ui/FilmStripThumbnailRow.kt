package com.example.timeline.ui

import android.graphics.Bitmap
import androidx.compose.foundation.Image
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.*
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.asImageBitmap
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.unit.dp
import com.example.timeline.media.MediaAsset
import com.example.timeline.media.MediaTimeMapping
import com.example.timeline.core.TimelineClip
import com.example.timeline.engine.preview.TimelineThumbnailGenerator

@Composable
fun FilmStripThumbnailRow(
    clip: TimelineClip,
    mediaAsset: MediaAsset,
    pixelsPerFrame: Float,
    scrollX: Float,
    viewportWidth: Float,
    modifier: Modifier = Modifier
) {
    val context = LocalContext.current
    val density = LocalDensity.current
    
    val heightPx = with(density) { 60.dp.toPx() }
    val isQuarterTurn = mediaAsset.metadata.rotationDegrees % 180 != 0
    val displayWidth = (if (isQuarterTurn) mediaAsset.metadata.height else mediaAsset.metadata.width).coerceAtLeast(1)
    val displayHeight = (if (isQuarterTurn) mediaAsset.metadata.width else mediaAsset.metadata.height).coerceAtLeast(1)
    val widthPx = heightPx * (displayWidth.toFloat() / displayHeight.toFloat())
    
    val durationFrames = (clip.timelineEnd - clip.timelineStart).coerceAtLeast(0L)
    val clipStartX = TimelineUiGeometry.frameToX(clip.timelineStart, pixelsPerFrame)
    val localVisibleStart = scrollX - clipStartX
    val localVisibleEnd = localVisibleStart + viewportWidth
    val thumbnailPlans = remember(
        clip.id, durationFrames, pixelsPerFrame, clip.sourceIn, widthPx, localVisibleStart, localVisibleEnd
    ) {
        if (durationFrames == 0L || !widthPx.isFinite() || widthPx <= 0f ||
            !pixelsPerFrame.isFinite() || pixelsPerFrame <= 0f
        ) {
            emptyList()
        } else {
            val clipWidth = durationFrames.toDouble() * pixelsPerFrame.toDouble()
            if (!clipWidth.isFinite() || clipWidth <= 0.0) {
                emptyList()
            } else {
                val lastCell = (kotlin.math.ceil(clipWidth / widthPx.toDouble()).toLong() - 1L)
                    .coerceAtLeast(0L)
                val firstVisible = kotlin.math.floor(localVisibleStart.toDouble() / widthPx.toDouble()).toLong()
                val lastVisible = kotlin.math.ceil(localVisibleEnd.toDouble() / widthPx.toDouble()).toLong()
                val firstCandidate = if (firstVisible <= 1L) 0L else firstVisible - 1L
                val firstCell = firstCandidate.coerceAtMost(lastCell)
                val lastCellExclusive = lastCell + 1L
                val visibleEndCandidate = if (lastVisible >= Long.MAX_VALUE - 1L) {
                    Long.MAX_VALUE
                } else maxOf(firstCell + 1L, lastVisible + 1L)
                val endCell = visibleEndCandidate.coerceAtMost(lastCellExclusive)
                val framesPerCell = widthPx.toDouble() / pixelsPerFrame.toDouble()
                buildList {
                    var index = firstCell
                    while (index < endCell) {
                        val startX = index.toDouble() * widthPx.toDouble()
                        val cellWidth = minOf(widthPx.toDouble(), clipWidth - startX)
                        val frameOffset = kotlin.math.floor(index.toDouble() * framesPerCell + 0.5)
                            .coerceIn(0.0, Long.MAX_VALUE.toDouble()).toLong()
                        val sourceFrame = try {
                            Math.addExact(clip.sourceIn, frameOffset)
                        } catch (_: ArithmeticException) {
                            Long.MAX_VALUE
                        }
                        add(
                            ThumbnailCellPlan(
                                cellIndex = index,
                                sourceFrame = sourceFrame,
                                startX = startX.coerceIn(-Float.MAX_VALUE.toDouble(), Float.MAX_VALUE.toDouble()).toFloat(),
                                width = cellWidth.coerceAtLeast(0.0).toFloat()
                            )
                        )
                        index++
                    }
                }
            }
        }
    }

    Box(modifier = modifier.fillMaxSize().padding(vertical = 2.dp)) {
        thumbnailPlans.forEach { plan ->
            val isVisible = plan.startX < localVisibleEnd && (plan.startX + plan.width) > localVisibleStart
            if (isVisible) {
                ThumbnailCellView(
                    plan = plan,
                    mediaAsset = mediaAsset,
                    context = android.content.ContextWrapper(context),
                    targetWidth = widthPx.toInt(),
                    targetHeight = heightPx.toInt()
                )
            }
        }
        
        // Dark overlay for text readability
        Box(modifier = Modifier.fillMaxSize().background(Color.Black.copy(alpha = 0.18f)))
    }
}

@Composable
fun ThumbnailCellView(
    plan: ThumbnailCellPlan,
    mediaAsset: MediaAsset,
    context: android.content.Context,
    targetWidth: Int,
    targetHeight: Int
) {
    var bitmap by remember { mutableStateOf<Bitmap?>(null) }
    
    val sourceFrameRate = mediaAsset.metadata.exactFrameRate ?: mediaAsset.metadata.estimatedFrameRate
    val uri = mediaAsset.localOriginalUriString ?: mediaAsset.originalUriString

    LaunchedEffect(plan.sourceFrame, uri, sourceFrameRate) {
        val timeUs = sourceFrameRate?.let { rate ->
            runCatching { MediaTimeMapping.sourceFrameToMicroseconds(plan.sourceFrame, rate) }.getOrNull()
        }
        bitmap = if (timeUs == null) {
            null
        } else {
            TimelineThumbnailGenerator.getOrGenerateThumbnail(
                context = context,
                assetId = mediaAsset.assetId,
                uriString = uri,
                timeUs = timeUs,
                width = targetWidth,
                height = targetHeight,
                mimeType = mediaAsset.mimeType
            )
        }
    }

    val dpStartX = with(LocalDensity.current) { plan.startX.toDp() }
    val dpWidth = with(LocalDensity.current) { plan.width.toDp() }

    Box(
        modifier = Modifier
            .offset(x = dpStartX)
            .width(dpWidth)
            .fillMaxHeight()
            .background(Color.DarkGray)
    ) {
        if (bitmap != null) {
            Image(
                bitmap = bitmap!!.asImageBitmap(),
                contentDescription = null,
                contentScale = ContentScale.Crop,
                modifier = Modifier.fillMaxSize()
            )
        }
    }
}
