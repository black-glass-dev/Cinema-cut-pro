package com.example.timeline.ui

import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.rememberUpdatedState
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import androidx.compose.foundation.layout.offset
import androidx.compose.foundation.gestures.TransformableState
import kotlinx.coroutines.flow.StateFlow
import kotlin.math.abs

/** A leaf-only subscriber: live playback position does not recompose the timeline's clip layout. */
@Composable
internal fun TimelinePlayheadIndicator(
    playheadFrameFlow: StateFlow<Long>,
    pixelsPerFrame: Float,
    scrollX: Float,
    frameToX: (Long, Float) -> Float,
    top: Dp = 0.dp,
    startOffset: Dp = 0.dp
) {
    val playheadFrame by playheadFrameFlow.collectAsState()
    val playheadX = with(LocalDensity.current) {
        (frameToX(playheadFrame, pixelsPerFrame) - scrollX).toDp()
    }
    TimelinePlayheadView(modifier = Modifier.offset(x = startOffset + playheadX, y = top))
}

/** Keeps follow-playhead scrolling live without making the canvas composition observe each tick. */
@Composable
internal fun FollowPlayheadScrollEffect(
    playheadFrameFlow: StateFlow<Long>,
    pixelsPerFrame: Float,
    centerX: Float,
    currentScrollX: Float,
    maxScrollX: Float,
    isPlaying: Boolean,
    isFollowing: Boolean,
    isDragging: Boolean,
    transformableState: TransformableState,
    onScrollTo: (Float) -> Unit
) {
    val playheadFrame by playheadFrameFlow.collectAsState()
    val latestOnScrollTo by rememberUpdatedState(onScrollTo)

    LaunchedEffect(playheadFrame, pixelsPerFrame, centerX, currentScrollX, maxScrollX, isPlaying, isFollowing, isDragging) {
        if (!isDragging && !transformableState.isTransformInProgress && isPlaying && isFollowing) {
            val targetScrollX = TimelineUiGeometry.followPlayheadScrollX(
                playheadFrame = playheadFrame,
                pixelsPerFrame = pixelsPerFrame,
                centerX = centerX,
                maxScrollX = maxScrollX
            )
            // Smooth-scroll with playback: update the viewport by each decoded frame so the
            // playhead stays visible while the clips and ruler move beneath it.
            if (abs(targetScrollX - currentScrollX) >= 0.5f) {
                latestOnScrollTo(targetScrollX)
            }
        }
    }
}
