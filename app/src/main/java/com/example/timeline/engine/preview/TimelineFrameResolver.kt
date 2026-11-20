package com.example.timeline.engine.preview

import com.example.timeline.core.ClipType
import com.example.timeline.core.Rational
import com.example.timeline.core.TimelineClip
import com.example.timeline.core.TimelineFrameMath
import com.example.timeline.core.TimelineProject
import com.example.timeline.core.TrackType

/** Read-only preview lookup and exact integer frame/time conversion helpers. */
object TimelineFrameResolver {
    /** Finds the topmost visible video clip at the given project frame. */
    fun getTopmostVisibleVideoClip(project: TimelineProject, frame: Long): TimelineClip? =
        getTopmostVisibleVideoClipWithIndex(project, frame)?.first

    /** Finds the topmost visible video clip and its track index at the given project frame. */
    fun getTopmostVisibleVideoClipWithIndex(project: TimelineProject, frame: Long): Pair<TimelineClip, Int>? {
        val reversedIndexedTracks = project.tracks.withIndex()
            .filter { (_, track) -> track.type == TrackType.VIDEO && track.isVisible }
            .reversed()
        for ((index, track) in reversedIndexedTracks) {
            val clip = track.clips.firstOrNull { containsFrame(it, frame) && it.isEnabled && it.type != ClipType.ADJUSTMENT }
            if (clip != null) return clip to index
        }
        return null
    }

    /** Retrieves applicable visible adjustment layers above the supplied video-track index. */
    fun getAdjustmentLayersAtFrame(project: TimelineProject, frame: Long, aboveTrackIndex: Int): List<TimelineClip> =
        project.tracks.withIndex().asSequence().filter { (index, track) ->
            (track.type == TrackType.ADJUSTMENT || track.clips.any { it.type == ClipType.ADJUSTMENT }) &&
                track.isVisible && index > aboveTrackIndex
        }.flatMap { (_, track) ->
            track.clips.asSequence().filter { clip ->
                clip.type == ClipType.ADJUSTMENT && clip.isEnabled && containsFrame(clip, frame)
            }
        }.toList()

    /** Maps a project frame to a source frame, returning null rather than wrapping on overflow. */
    fun resolveSourceFrame(projectPlayheadFrame: Long, clip: TimelineClip): Long? {
        val offset = try {
            Math.subtractExact(projectPlayheadFrame, clip.timelineStart)
        } catch (_: ArithmeticException) {
            return null
        }
        return try {
            Math.addExact(clip.sourceIn, offset)
        } catch (_: ArithmeticException) {
            null
        }
    }

    /** Converts a non-negative frame count to microseconds with floor rounding. */
    fun convertFrameToMicroseconds(frame: Long, fpsNumerator: Int, fpsDenominator: Int): Long? =
        frameToMicroseconds(frame, Rational(fpsNumerator, fpsDenominator))

    fun frameToMicroseconds(frame: Long, frameRate: Rational): Long? =
        TimelineFrameMath.microsecondsFromFrames(frame, frameRate)

    /** Converts media microseconds to frame count with floor rounding. */
    fun microsecondsToFrames(timeUs: Long, frameRate: Rational): Long? =
        TimelineFrameMath.framesFromMicroseconds(timeUs, frameRate)

    private fun containsFrame(clip: TimelineClip, frame: Long): Boolean {
        val end = try {
            Math.addExact(clip.timelineStart, clip.duration)
        } catch (_: ArithmeticException) {
            return false
        }
        return frame >= clip.timelineStart && frame < end
    }
}
