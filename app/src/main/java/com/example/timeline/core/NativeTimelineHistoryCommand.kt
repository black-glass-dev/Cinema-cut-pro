package com.example.timeline.core

/**
 * Kotlin history coordinator entry whose timeline state is owned by the native Core v2 journal.
 * It stores no project snapshot and contains no edit calculation.
 */
class NativeTimelineHistoryCommand(
    override val commandName: String,
    override val affectedClipIds: List<String>,
    override val timestamp: Long = System.currentTimeMillis()
) : TimelineCommand {
    override fun execute(project: TimelineProject): TimelineResult = TimelineResult(project)

    override fun undo(project: TimelineProject): TimelineResult =
        TimelineCoreV2Adapter.undo(project).toTimelineResult(project)

    override fun redo(project: TimelineProject): TimelineResult =
        TimelineCoreV2Adapter.redo(project).toTimelineResult(project)
}

private fun CoreV2EditOutcome.toTimelineResult(original: TimelineProject): TimelineResult = when (this) {
    is CoreV2EditOutcome.Applied -> TimelineResult(project)
    is CoreV2EditOutcome.Rejected -> TimelineResult(original, errorCode)
}
