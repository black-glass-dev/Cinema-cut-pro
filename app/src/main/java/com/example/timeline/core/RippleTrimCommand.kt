package com.example.timeline.core

class RippleTrimCommand(
    val clipId: String,
    val newFrame: Long,
    val isStart: Boolean,
    val rippleAllTracks: Boolean = false,
    override val commandName: String = if (isStart) "Ripple Trim Start" else "Ripple Trim End",
    override val timestamp: Long = System.currentTimeMillis()
) : TimelineCommand {
    private var projectBefore: TimelineProject? = null
    private var projectAfter: TimelineProject? = null
    override val affectedClipIds: List<String> = listOf(clipId)

    override fun execute(project: TimelineProject): TimelineResult {
        projectBefore = project
        val result = TimelineCoreV2Adapter.applyEdit(
            project,
            CoreV2EditCommand(
                kind = if (isStart) CoreV2EditKind.TRIM_START else CoreV2EditKind.TRIM_END,
                clipId = clipId,
                targetFrame = newFrame,
                ripple = if (rippleAllTracks) CoreV2RippleScope.ALL_TRACKS
                    else CoreV2RippleScope.SAME_TRACK
            )
        ).toV2TimelineResult(project)
        if (result.isSuccess) projectAfter = result.project
        return result
    }

    override fun undo(project: TimelineProject): TimelineResult =
        TimelineResult(projectBefore ?: project)

    override fun redo(project: TimelineProject): TimelineResult =
        TimelineResult(projectAfter ?: project)
}
