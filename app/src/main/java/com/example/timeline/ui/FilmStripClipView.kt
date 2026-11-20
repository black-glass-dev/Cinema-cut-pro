package com.example.timeline.ui

import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.example.timeline.core.Rational
import com.example.timeline.core.TimelineClip
import com.example.timeline.media.MediaAsset

/** Compact thumbnail-first clip block matching the portrait timeline reference. */
@Composable
fun FilmStripClipView(
    clip: TimelineClip,
    mediaAsset: MediaAsset? = null,
    isSelected: Boolean,
    modifier: Modifier = Modifier,
    pixelsPerFrame: Float,
    scrollX: Float,
    viewportWidth: Float,
    frameRate: Rational
) {
    val durationFrames = clip.duration.coerceAtLeast(0L)
    val rawWidthPx = durationFrames.toDouble() * pixelsPerFrame.toDouble()
    val widthPx = if (rawWidthPx.isFinite() && rawWidthPx > 0.0) {
        rawWidthPx.coerceAtMost(Float.MAX_VALUE.toDouble()).toFloat()
    } else {
        0f
    }
    val widthDp = with(androidx.compose.ui.platform.LocalDensity.current) { widthPx.toDp() }
    val durationSec = if (frameRate.numerator > 0 && frameRate.denominator > 0) {
        durationFrames.toDouble() * frameRate.denominator.toDouble() / frameRate.numerator.toDouble()
    } else {
        0.0
    }

    val clipShape = RoundedCornerShape(6.dp)
    val outline = if (isSelected) TimelineDesignTokens.Accent else Color(0xFF34353B)
    Box(
        modifier = modifier
            .width(widthDp)
            .height(TimelineDesignTokens.TrackHeight)
            .clip(clipShape)
            .background(TimelineDesignTokens.Clip)
            .border(if (isSelected) 1.5.dp else 0.75.dp, outline, clipShape)
    ) {
        if (mediaAsset != null && clip.type == com.example.timeline.core.ClipType.MEDIA) {
            FilmStripThumbnailRow(
                clip = clip,
                mediaAsset = mediaAsset,
                pixelsPerFrame = pixelsPerFrame,
                scrollX = scrollX,
                viewportWidth = viewportWidth,
                modifier = Modifier.fillMaxSize()
            )
        }

        Column(
            modifier = Modifier
                .align(Alignment.TopStart)
                .fillMaxWidth()
                .padding(start = 8.dp, end = 18.dp, top = 5.dp)
        ) {
            Text(
                text = mediaAsset?.displayName ?: clip.name,
                color = Color.White,
                fontSize = 11.sp,
                fontWeight = FontWeight.SemiBold,
                maxLines = 1,
                overflow = TextOverflow.Ellipsis
            )
        }
        Text(
            text = String.format("%.1fs", durationSec),
            color = Color.White.copy(alpha = 0.9f),
            fontSize = 9.sp,
            maxLines = 1,
            modifier = Modifier.align(Alignment.BottomStart).padding(start = 8.dp, bottom = 4.dp)
        )

        if (isSelected) {
            listOf(Alignment.CenterStart, Alignment.CenterEnd).forEach { alignment ->
                Box(
                    modifier = Modifier
                        .align(alignment)
                        .width(10.dp)
                        .fillMaxHeight()
                ) {
                    Box(
                        modifier = Modifier
                            .align(Alignment.Center)
                            .width(2.dp)
                            .height(22.dp)
                            .background(Color.White.copy(alpha = 0.9f), RoundedCornerShape(2.dp))
                    )
                    Box(
                        modifier = Modifier
                            .align(Alignment.Center)
                            .width(8.dp)
                            .height(8.dp)
                            .background(TimelineDesignTokens.Accent, CircleShape)
                            .border(1.dp, Color.White, CircleShape)
                    )
                }
            }
        }
    }
}
