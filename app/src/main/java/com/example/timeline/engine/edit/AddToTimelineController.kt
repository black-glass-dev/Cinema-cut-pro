package com.example.timeline.engine.edit

import com.example.timeline.core.CoreV2EditCommand
import com.example.timeline.core.CoreV2EditKind
import com.example.timeline.core.Rational
import com.example.timeline.core.TimelineClip
import com.example.timeline.core.TimelineFrameMath
import com.example.timeline.core.TimelineProject
import com.example.timeline.core.TimelineTrack
import com.example.timeline.core.TrackType
import com.example.timeline.media.FrameRate
import com.example.timeline.media.MediaAsset
import java.util.UUID

data class TimelineClipInsertionPlan(
    /** May include a newly-created empty track, but never contains the proposed clip. */
    val baseProject: TimelineProject,
    val command: CoreV2EditCommand,
    val clip: TimelineClip
)

/** Builds an insertion request; only Timeline Core v2 may validate or apply the clip edit. */
object AddToTimelineController {
    fun determineTargetTrack(project: TimelineProject, mediaAsset: MediaAsset): TimelineTrack? {
        val targetType = if (mediaAsset.mimeType.startsWith("audio/")) TrackType.AUDIO else TrackType.VIDEO
        return project.tracks.firstOrNull { it.type == targetType && !it.isLocked }
    }

    /** Finds the first non-overlapping start at or after [desiredStart], rejecting overflow. */
    internal fun findEmptySpace(track: TimelineTrack, desiredStart: Long, duration: Long): Long? {
        if (desiredStart < 0L || duration <= 0L) return null
        var currentStart = desiredStart
        while (true) {
            val candidateEnd = try {
                Math.addExact(currentStart, duration)
            } catch (_: ArithmeticException) {
                return null
            }
            var latestOverlapEnd: Long? = null
            for (clip in track.clips) {
                val clipEnd = try {
                    Math.addExact(clip.timelineStart, clip.duration)
                } catch (_: ArithmeticException) {
                    return null
                }
                if (currentStart < clipEnd && candidateEnd > clip.timelineStart) {
                    latestOverlapEnd = maxOf(latestOverlapEnd ?: clipEnd, clipEnd)
                }
            }
            val nextStart = latestOverlapEnd ?: return currentStart
            if (nextStart <= currentStart) return null
            currentStart = nextStart
        }
    }

    /** The first video item is anchored at frame zero; later items start at the playhead. */
    internal fun resolveStartFrame(
        project: TimelineProject,
        targetTrack: TimelineTrack,
        playheadFrame: Long,
        duration: Long
    ): Long? {
        val hasExistingVideoClips = project.tracks.any { track ->
            track.type == TrackType.VIDEO && track.isVisible && track.clips.any { it.isEnabled }
        }
        val desiredStart = if (hasExistingVideoClips) playheadFrame.coerceAtLeast(0L) else 0L
        return findEmptySpace(targetTrack, desiredStart, duration)
    }

    /**
     * Creates a native insert request plus any empty-track metadata needed to host it. Unknown or
     * unrepresentable source durations reject rather than inventing a 150-frame placeholder.
     */
    fun createClipInsertionPlan(
        project: TimelineProject,
        mediaAsset: MediaAsset,
        playheadFrame: Long,
        mode: String = "active",
        selectedTrackId: String? = null
    ): TimelineClipInsertionPlan? {
        val durationProjectFrames = durationInProjectFrames(project, mediaAsset) ?: return null
        var baseProject = project
        val hasVideo = mediaAsset.metadata.hasVideo || mediaAsset.mimeType.startsWith("image/")
        val hasAudio = mediaAsset.metadata.hasAudio
        val targetType = when {
            hasVideo -> TrackType.VIDEO
            hasAudio -> TrackType.AUDIO
            else -> TrackType.VIDEO
        }
        val targetTrack = if (mode == "active") {
            val selected = selectedTrackId?.let { id ->
                baseProject.tracks.firstOrNull { it.id == id && it.type == targetType && !it.isLocked && it.isVisible }
            }
            val existingEnabledVisibleVideo = baseProject.tracks.any { track ->
                track.type == TrackType.VIDEO && track.isVisible && track.clips.any { it.isEnabled }
            }
            selected ?: baseProject.tracks.firstOrNull { track ->
                track.type == targetType && track.isVisible && !track.isLocked &&
                    (targetType != TrackType.VIDEO ||
                        if (existingEnabledVisibleVideo) track.clips.any { it.isEnabled }
                        else track.clips.isEmpty())
            }
        } else {
            null
        } ?: TimelineTrack(
            id = UUID.randomUUID().toString(),
            name = when (targetType) {
                TrackType.AUDIO -> "Audio ${baseProject.tracks.count { it.type == TrackType.AUDIO } + 1}"
                else -> "Video ${baseProject.tracks.count { it.type == TrackType.VIDEO } + 1}"
            },
            type = targetType,
            height = if (targetType == TrackType.AUDIO) 80 else 100
        ).also { newTrack -> baseProject = baseProject.copy(tracks = baseProject.tracks + newTrack) }

        val startFrame = resolveStartFrame(baseProject, targetTrack, playheadFrame, durationProjectFrames)
            ?: return null
        val clip = TimelineClip(
            id = UUID.randomUUID().toString(),
            trackId = targetTrack.id,
            mediaId = mediaAsset.assetId,
            name = mediaAsset.displayName,
            sourceIn = 0L,
            sourceOut = durationProjectFrames,
            timelineStart = startFrame
        )
        return TimelineClipInsertionPlan(
            baseProject = baseProject,
            command = CoreV2EditCommand(
                kind = CoreV2EditKind.INSERT,
                targetTrackId = targetTrack.id,
                insertedClip = clip
            ),
            clip = clip
        )
    }

    private fun durationInProjectFrames(project: TimelineProject, mediaAsset: MediaAsset): Long? {
        val projectRate = project.settings.getFpsRational()
        val metadata = mediaAsset.metadata
        val durationFrames = if (metadata.durationUs > 0L) {
            TimelineFrameMath.framesFromMicroseconds(metadata.durationUs, projectRate)
        } else {
            val sourceFrameRate: FrameRate? = metadata.exactFrameRate ?: metadata.estimatedFrameRate
            if (metadata.durationFramesInSourceRate <= 0L || sourceFrameRate == null) {
                null
            } else {
                TimelineFrameMath.convertFrameCount(
                    frameCount = metadata.durationFramesInSourceRate,
                    sourceNumerator = sourceFrameRate.numerator,
                    sourceDenominator = sourceFrameRate.denominator,
                    projectNumerator = projectRate.numerator,
                    projectDenominator = projectRate.denominator
                )
            }
        }
        return durationFrames?.takeIf { it > 0L }
    }
}
