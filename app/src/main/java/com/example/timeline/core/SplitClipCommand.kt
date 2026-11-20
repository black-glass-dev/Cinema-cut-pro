package com.example.timeline.core

class SplitClipCommand(
    val clipId: String,
    val splitFrame: Long,
    val splitLinkedAudio: Boolean = true,
    override val commandName: String = "Split Clip",
    override val timestamp: Long = System.currentTimeMillis()
) : TimelineCommand {
    private var projectBefore: TimelineProject? = null
    private var projectAfter: TimelineProject? = null
    private var _affectedIds: List<String> = listOf(clipId)

    override val affectedClipIds: List<String> get() = _affectedIds

    /** Builds one atomic native command batch for the requested split and linked clips. */
    fun nativeEditCommands(project: TimelineProject): List<CoreV2EditCommand> {
        val primary = findClip(project, clipId) ?: return emptyList()
        val candidates = if (splitLinkedAudio && primary.mediaId.isNotEmpty()) {
            project.tracks.asSequence().flatMap { it.clips.asSequence() }
                .filter { candidate ->
                    candidate.id == primary.id ||
                        (candidate.mediaId == primary.mediaId &&
                            candidate.timelineStart == primary.timelineStart &&
                            candidate.sourceIn == primary.sourceIn)
                }
                .distinctBy { it.id }
                .filter { candidate -> isSplitInside(candidate, splitFrame) }
                .toList()
        } else {
            listOf(primary).filter { isSplitInside(it, splitFrame) }
        }
        return candidates.map { candidate ->
            CoreV2EditCommand(
                kind = CoreV2EditKind.SPLIT,
                clipId = candidate.id,
                otherClipId = java.util.UUID.randomUUID().toString(),
                targetFrame = splitFrame
            )
        }
    }

    override fun execute(project: TimelineProject): TimelineResult {
        projectBefore = project
        val commands = nativeEditCommands(project)
        if (commands.isEmpty()) return TimelineResult(project, "TL_E_INVALID_EDIT")
        val newIds = commands.mapNotNull { it.otherClipId }
        val result = TimelineCoreV2Adapter.applyEdits(project, commands)
            .toV2SplitResult(project, newIds)
        if (result.isSuccess) {
            projectAfter = result.project
            _affectedIds = commands.mapNotNull { it.clipId } + newIds
        }
        return TimelineResult(result.project, result.error)
    }

    override fun undo(project: TimelineProject): TimelineResult =
        TimelineResult(projectBefore ?: project)

    override fun redo(project: TimelineProject): TimelineResult =
        TimelineResult(projectAfter ?: project)

    private fun findClip(project: TimelineProject, id: String): TimelineClip? =
        project.tracks.asSequence().flatMap { it.clips.asSequence() }.firstOrNull { it.id == id }

    private fun isSplitInside(clip: TimelineClip, frame: Long): Boolean {
        val end = try {
            Math.addExact(clip.timelineStart, clip.duration)
        } catch (_: ArithmeticException) {
            return false
        }
        return frame > clip.timelineStart && frame < end
    }
}
