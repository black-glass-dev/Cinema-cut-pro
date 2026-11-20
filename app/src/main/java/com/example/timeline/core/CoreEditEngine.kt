package com.example.timeline.core

import java.util.UUID

enum class TrimMode {
    NORMAL,
    RIPPLE,
    RIPPLE_ALL_TRACKS
}

data class SplitResult(
    val project: TimelineProject,
    val newClipIds: List<String>,
    val error: String? = null
) {
    val isSuccess: Boolean get() = error == null
}

/**
 * Deprecated source-compatible facade. It contains no edit calculation: each operation crosses
 * the typed JNI adapter and is evaluated by timeline_core_v2. If v2 is unavailable, edits reject.
 * New source must call TimelineCoreV2Adapter directly.
 */
@Deprecated(
    message = "Compile guard: use TimelineCoreV2Adapter; this compatibility facade is retired",
    level = DeprecationLevel.ERROR
)
object CoreEditEngine {
    fun trimClipStart(
        project: TimelineProject,
        clipId: String,
        newTimelineStartFrame: Long,
        mode: TrimMode = TrimMode.NORMAL
    ): TimelineResult = apply(
        project,
        CoreV2EditCommand(
            kind = CoreV2EditKind.TRIM_START,
            clipId = clipId,
            targetFrame = newTimelineStartFrame,
            ripple = mode.toCoreV2RippleScope()
        )
    )

    fun trimClipEnd(
        project: TimelineProject,
        clipId: String,
        newTimelineEndFrame: Long,
        mode: TrimMode = TrimMode.NORMAL
    ): TimelineResult = apply(
        project,
        CoreV2EditCommand(
            kind = CoreV2EditKind.TRIM_END,
            clipId = clipId,
            targetFrame = newTimelineEndFrame,
            ripple = mode.toCoreV2RippleScope()
        )
    )

    fun splitClipAtFrame(project: TimelineProject, clipId: String, splitFrame: Long): SplitResult {
        val newClipId = UUID.randomUUID().toString()
        return when (val outcome = TimelineCoreV2Adapter.applyEdit(
            project,
            CoreV2EditCommand(
                kind = CoreV2EditKind.SPLIT,
                clipId = clipId,
                otherClipId = newClipId,
                targetFrame = splitFrame
            )
        )) {
            is CoreV2EditOutcome.Applied -> SplitResult(outcome.project, listOf(newClipId))
            is CoreV2EditOutcome.Rejected -> SplitResult(project, emptyList(), outcome.errorCode)
        }
    }

    /** Batch cut is all-or-nothing: a locked/invalid clip rejects the entire native transaction. */
    fun cutUnlockedClipsAtFrame(project: TimelineProject, frame: Long): SplitResult {
        val candidates = project.tracks.flatMap { track ->
            track.clips.filter { clip ->
                clip.isEnabled && frame > clip.timelineStart && frame < clip.timelineEnd
            }
        }
        if (candidates.isEmpty()) return SplitResult(project, emptyList())
        val newIds = candidates.map { UUID.randomUUID().toString() }
        val commands = candidates.zip(newIds).map { (clip, newId) ->
            CoreV2EditCommand(
                kind = CoreV2EditKind.SPLIT,
                clipId = clip.id,
                otherClipId = newId,
                targetFrame = frame
            )
        }
        return when (val outcome = TimelineCoreV2Adapter.applyEdits(project, commands)) {
            is CoreV2EditOutcome.Applied -> SplitResult(outcome.project, newIds)
            is CoreV2EditOutcome.Rejected -> SplitResult(project, emptyList(), outcome.errorCode)
        }
    }

    fun deleteClips(
        project: TimelineProject,
        clipIds: List<String>,
        rippleMode: Boolean = false,
        rippleAllTracks: Boolean = false
    ): TimelineResult {
        if (clipIds.isEmpty()) return TimelineResult(project)
        val ripple = when {
            !rippleMode -> CoreV2RippleScope.NONE
            rippleAllTracks -> CoreV2RippleScope.ALL_TRACKS
            else -> CoreV2RippleScope.SAME_TRACK
        }
        return apply(
            project,
            CoreV2EditCommand(
                kind = CoreV2EditKind.DELETE,
                clipIds = clipIds,
                ripple = ripple
            )
        )
    }

    fun rollEdit(
        project: TimelineProject,
        leftClipId: String,
        rightClipId: String,
        deltaFrames: Long
    ): TimelineResult = apply(
        project,
        CoreV2EditCommand(
            kind = CoreV2EditKind.ROLL,
            clipId = leftClipId,
            otherClipId = rightClipId,
            deltaFrames = deltaFrames
        )
    )

    fun slipEdit(project: TimelineProject, clipId: String, deltaFrames: Long): TimelineResult = apply(
        project,
        CoreV2EditCommand(
            kind = CoreV2EditKind.SLIP,
            clipId = clipId,
            deltaFrames = deltaFrames
        )
    )

    fun slideEdit(project: TimelineProject, clipId: String, deltaFrames: Long): TimelineResult = apply(
        project,
        CoreV2EditCommand(
            kind = CoreV2EditKind.SLIDE,
            clipId = clipId,
            deltaFrames = deltaFrames
        )
    )

    private fun apply(project: TimelineProject, command: CoreV2EditCommand): TimelineResult =
        when (val outcome = TimelineCoreV2Adapter.applyEdit(project, command)) {
            is CoreV2EditOutcome.Applied -> TimelineResult(outcome.project)
            is CoreV2EditOutcome.Rejected -> TimelineResult(project, outcome.errorCode)
        }
}

