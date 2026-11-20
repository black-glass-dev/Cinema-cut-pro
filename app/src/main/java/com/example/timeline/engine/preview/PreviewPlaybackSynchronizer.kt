package com.example.timeline.engine.preview

import androidx.media3.exoplayer.ExoPlayer

/**
 * Handles synchronizing ExoPlayer playback state with timeline playhead.
 */
class PreviewPlaybackSynchronizer(
    private val player: ExoPlayer,
    private val fpsNumerator: Int,
    private val fpsDenominator: Int
) {

    fun syncToTimelineFrame(projectFrame: Long, sourceFrame: Long) {
        val targetPositionUs = TimelineFrameResolver.convertFrameToMicroseconds(
            sourceFrame,
            fpsNumerator,
            fpsDenominator
        ) ?: return
        val targetPositionMs = targetPositionUs / 1_000L

        // Only seek if the difference is significant to avoid stuttering.
        if (kotlin.math.abs(player.currentPosition - targetPositionMs) > 100L) {
            player.seekTo(targetPositionMs)
        }
    }

    fun sourcePositionToTimelineFrame(
        playerPosMs: Long,
        timelineStart: Long,
        sourceIn: Long
    ): Long {
        val playerUs = try {
            Math.multiplyExact(playerPosMs, 1_000L)
        } catch (_: ArithmeticException) {
            return timelineStart
        }
        val sourceFrame = com.example.timeline.core.TimelineFrameMath.framesFromMicroseconds(
            playerUs,
            com.example.timeline.core.Rational(fpsNumerator, fpsDenominator)
        ) ?: return timelineStart
        val offsetFrames = try {
            Math.subtractExact(sourceFrame, sourceIn)
        } catch (_: ArithmeticException) {
            return timelineStart
        }
        return try {
            Math.addExact(timelineStart, offsetFrames)
        } catch (_: ArithmeticException) {
            timelineStart
        }
    }
}
