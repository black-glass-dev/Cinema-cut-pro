package com.example.timeline.core

class MoveClipCommand(
    val clipId: String,
    val oldStartFrame: Long,
    val newStartFrame: Long,
    val trackId: String,
    override val commandName: String = "Move Clip",
    override val affectedClipIds: List<String> = listOf(clipId),
    override val timestamp: Long = System.currentTimeMillis()
) : TimelineCommand {

    override fun execute(project: TimelineProject): TimelineResult = performMove(project, newStartFrame)

    override fun undo(project: TimelineProject): TimelineResult = performMove(project, oldStartFrame)

    override fun redo(project: TimelineProject): TimelineResult = performMove(project, newStartFrame)

    private fun performMove(project: TimelineProject, targetFrame: Long): TimelineResult {
        val sourceTrack = project.tracks.firstOrNull { track ->
            track.clips.any { clip -> clip.id == clipId }
        } ?: return TimelineResult(project, "TL_E_CLIP_NOT_FOUND")
        return CoreTimelineEngine.moveClipToTrack(
            project = project,
            clipId = clipId,
            sourceTrackId = sourceTrack.id,
            destTrackId = trackId,
            newTimelineStart = targetFrame
        )
    }
}
