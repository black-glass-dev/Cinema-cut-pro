package com.example.timeline.core

import java.math.BigInteger

/** Integer nominal-rate timecode conversion; drop-frame timecode is intentionally not implemented. */
object FrameTimecode {
    fun frameToTimecode(frame: Long, fps: Int): String =
        frameToTimecode(frame, Rational(fps, 1))

    fun frameToTimecode(frame: Long, fps: Rational): String {
        val nominalFps = TimelineFrameMath.nominalFramesPerSecond(fps) ?: return "00:00:00:00"
        if (frame < 0L) return "00:00:00:00"
        val frames = frame % nominalFps
        val totalSeconds = frame / nominalFps
        val seconds = totalSeconds % 60L
        val minutes = (totalSeconds / 60L) % 60L
        val hours = totalSeconds / 3600L
        return String.format("%02d:%02d:%02d:%02d", hours, minutes, seconds, frames)
    }

    fun timecodeToFrame(timecode: String, fps: Int): Long =
        timecodeToFrame(timecode, Rational(fps, 1))

    fun timecodeToFrame(timecode: String, fps: Rational): Long {
        val nominalFps = TimelineFrameMath.nominalFramesPerSecond(fps)
            ?: throw IllegalArgumentException("Frame rate must be positive")
        val parts = timecode.split(":")
        require(parts.size == 4) { "Invalid timecode format, expected HH:MM:SS:FF" }
        val hours = parts[0].toLongOrNull() ?: throw IllegalArgumentException("Invalid timecode hours")
        val minutes = parts[1].toLongOrNull() ?: throw IllegalArgumentException("Invalid timecode minutes")
        val seconds = parts[2].toLongOrNull() ?: throw IllegalArgumentException("Invalid timecode seconds")
        val frames = parts[3].toLongOrNull() ?: throw IllegalArgumentException("Invalid timecode frames")
        require(hours >= 0L && minutes in 0L..59L && seconds in 0L..59L && frames in 0 until nominalFps) {
            "Timecode field is outside its valid range"
        }
        val totalSeconds = BigInteger.valueOf(hours)
            .multiply(BigInteger.valueOf(3600L))
            .add(BigInteger.valueOf(minutes).multiply(BigInteger.valueOf(60L)))
            .add(BigInteger.valueOf(seconds))
        return totalSeconds.multiply(BigInteger.valueOf(nominalFps))
            .add(BigInteger.valueOf(frames))
            .longValueExact()
    }
}
