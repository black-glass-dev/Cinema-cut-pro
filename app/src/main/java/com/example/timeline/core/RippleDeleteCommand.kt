package com.example.timeline.core

class RippleDeleteCommand(
    val clipIds: List<String>,
    val rippleAllTracks: Boolean = false,
    override val commandName: String = "Ripple Delete",
    override val timestamp: Long = System.currentTimeMillis()
) : TimelineCommand {
    private var projectBefore: TimelineProject? = null
    private var projectAfter: TimelineProject? = null
    override val affectedClipIds: List<String> = clipIds

    override fun execute(project: TimelineProject): TimelineResult {
        projectBefore = project
        val result = TimelineCoreV2Adapter.applyEdit(
            project,
            CoreV2EditCommand(
                kind = CoreV2EditKind.DELETE,
                clipIds = clipIds,
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
