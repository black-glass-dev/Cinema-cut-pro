package com.example.timeline.core

/** Deprecated unverified Kotlin mirror of native read-only timeline queries; no app call-sites remain. */
@Deprecated(
    message = "Compile guard: use the v2 query boundary or a separately parity-tested read-only adapter",
    level = DeprecationLevel.ERROR
)
object TimelineQuery {
    fun getTimelineEndFrame(project: TimelineProject): Long {
        return project.tracks.flatMap { it.clips }
            .maxOfOrNull { it.timelineEnd } ?: 0L
    }

    fun getClipsAtPlayhead(project: TimelineProject, frame: Long): List<TimelineClip> {
        return project.tracks.flatMap { track ->
            track.clips.filter { clip ->
                frame >= clip.timelineStart && frame < clip.timelineEnd
            }
        }
    }

    fun findNearestEditPoint(project: TimelineProject, currentFrame: Long, searchForward: Boolean = true): Long? {
        val allEditPoints = project.tracks.flatMap { track ->
            track.clips.flatMap { listOf(it.timelineStart, it.timelineEnd) }
        }.distinct().sorted()

        return if (searchForward) {
            allEditPoints.firstOrNull { it > currentFrame }
        } else {
            allEditPoints.lastOrNull { it < currentFrame }
        }
    }

    fun sortClipsByTimelineStart(clips: List<TimelineClip>): List<TimelineClip> {
        return clips.sortedBy { it.timelineStart }
    }
}
