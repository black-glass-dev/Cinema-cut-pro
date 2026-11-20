package com.example.timeline.core

object CommandFactory {
    fun createTrimStartCommand(
        projectBefore: TimelineProject,
        clipId: String,
        newStartFrame: Long,
        mode: TrimMode = TrimMode.NORMAL
    ): TimelineCommand? {
        val command = CoreV2EditCommand(
            kind = CoreV2EditKind.TRIM_START,
            clipId = clipId,
            targetFrame = newStartFrame,
            ripple = mode.toCoreV2RippleScope()
        )
        if (TimelineCoreV2Adapter.applyEdit(projectBefore, command) !is CoreV2EditOutcome.Applied) return null
        return TrimCommand(clipId = clipId, newFrame = newStartFrame, isStart = true, mode = mode)
    }

    fun createTrimEndCommand(
        projectBefore: TimelineProject,
        clipId: String,
        newEndFrame: Long,
        mode: TrimMode = TrimMode.NORMAL
    ): TimelineCommand? {
        val command = CoreV2EditCommand(
            kind = CoreV2EditKind.TRIM_END,
            clipId = clipId,
            targetFrame = newEndFrame,
            ripple = mode.toCoreV2RippleScope()
        )
        if (TimelineCoreV2Adapter.applyEdit(projectBefore, command) !is CoreV2EditOutcome.Applied) return null
        return TrimCommand(clipId = clipId, newFrame = newEndFrame, isStart = false, mode = mode)
    }

    fun createSplitCommand(
        projectBefore: TimelineProject,
        clipId: String,
        splitFrame: Long
    ): TimelineCommand? {
        val command = SplitClipCommand(clipId, splitFrame, splitLinkedAudio = false)
        val edits = command.nativeEditCommands(projectBefore)
        if (edits.isEmpty() ||
            TimelineCoreV2Adapter.applyEdits(projectBefore, edits) !is CoreV2EditOutcome.Applied
        ) return null
        return command
    }

    fun createRippleDeleteCommand(
        projectBefore: TimelineProject,
        clipIds: List<String>
    ): TimelineCommand? {
        if (clipIds.isEmpty()) return null
        val command = RippleDeleteCommand(clipIds)
        val edit = CoreV2EditCommand(
            kind = CoreV2EditKind.DELETE,
            clipIds = clipIds,
            ripple = CoreV2RippleScope.SAME_TRACK
        )
        if (TimelineCoreV2Adapter.applyEdit(projectBefore, edit) !is CoreV2EditOutcome.Applied) return null
        return command
    }

    fun createAddClipCommand(
        project: TimelineProject,
        trackId: String,
        clip: TimelineClip
    ): TimelineCommand = InsertClipCommand(trackId, clip)
}
