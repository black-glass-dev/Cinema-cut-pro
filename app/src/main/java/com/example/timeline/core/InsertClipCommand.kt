package com.example.timeline.core

/** Request object for a native insert; it contains no independent collision or edit logic. */
class InsertClipCommand(
    val trackId: String,
    val clip: TimelineClip,
    override val commandName: String = "Add Clip",
    override val timestamp: Long = System.currentTimeMillis()
) : TimelineCommand {
    override val affectedClipIds: List<String> = listOf(clip.id)

    override fun execute(project: TimelineProject): TimelineResult =
        TimelineCoreV2Adapter.applyEdit(
            project,
            CoreV2EditCommand(
                kind = CoreV2EditKind.INSERT,
                targetTrackId = trackId,
                insertedClip = clip.copy(trackId = trackId)
            )
        ).toV2TimelineResult(project)

    override fun undo(project: TimelineProject): TimelineResult =
        TimelineCoreV2Adapter.applyEdit(
            project,
            CoreV2EditCommand(kind = CoreV2EditKind.DELETE, clipIds = listOf(clip.id))
        ).toV2TimelineResult(project)

    override fun redo(project: TimelineProject): TimelineResult = execute(project)
}
