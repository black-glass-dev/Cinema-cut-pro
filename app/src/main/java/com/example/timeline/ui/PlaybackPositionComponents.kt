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
import kotlin.math.max

/** A leaf-only subscriber: live playback position does not recompose the timeline's clip layout. */
@Composable
internal fun TimelinePlayheadIndicator(
    playheadFrameFlow: StateFlow<Long>,
    pixelsPerFrame: Float,
    scrollX: Float,
    frameToX: (Long, Float) -> Float,
    top: Dp = 40.dp,
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
    isPlaying: Boolean,
    isFollowing: Boolean,
    isDragging: Boolean,
    transformableState: TransformableState,
    frameToX: (Long, Float) -> Float,
    onScrollTo: (Float) -> Unit
) {
    val playheadFrame by playheadFrameFlow.collectAsState()
    val latestOnScrollTo by rememberUpdatedState(onScrollTo)

    LaunchedEffect(playheadFrame, pixelsPerFrame, centerX, currentScrollX, isPlaying, isFollowing, isDragging) {
        if (!isDragging && !transformableState.isTransformInProgress && isPlaying && isFollowing) {
            val targetScrollX = max(0f, frameToX(playheadFrame, pixelsPerFrame) - centerX)
            // Avoid laying out every track and clip on every decoded frame just to keep the cursor
            // mathematically centered. The playhead indicator itself still moves at display rate.
            val minScrollStepPx = max(24f, pixelsPerFrame * 10f)
            if (abs(targetScrollX - currentScrollX) >= minScrollStepPx) {
                latestOnScrollTo(targetScrollX)
            }
        }
    }
}
