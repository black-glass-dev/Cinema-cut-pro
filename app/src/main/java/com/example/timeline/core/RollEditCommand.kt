package com.example.timeline.core

class RollEditCommand(
    val leftClipId: String,
    val rightClipId: String,
    val deltaFrames: Long,
    override val commandName: String = "Roll Edit",
    override val timestamp: Long = System.currentTimeMillis()
) : TimelineCommand {
    private var projectBefore: TimelineProject? = null
    private var projectAfter: TimelineProject? = null
    override val affectedClipIds: List<String> = listOf(leftClipId, rightClipId)

    override fun execute(project: TimelineProject): TimelineResult {
        projectBefore = project
        val result = TimelineCoreV2Adapter.applyEdit(
            project,
            CoreV2EditCommand(
                kind = CoreV2EditKind.ROLL,
                clipId = leftClipId,
                otherClipId = rightClipId,
                deltaFrames = deltaFrames
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
