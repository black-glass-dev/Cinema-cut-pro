package com.example.timeline.ui

import androidx.compose.ui.geometry.Offset
import com.example.timeline.core.CoreV2EditCommand
import com.example.timeline.core.CoreV2EditKind
import com.example.timeline.core.CoreV2RippleScope
import com.example.timeline.core.CoreV2SnapOutcome
import com.example.timeline.core.TimelineClip
import com.example.timeline.core.TimelineCoreV2Adapter
import com.example.timeline.core.TimelineProject
import com.example.timeline.core.TimelineSnapSettings

sealed class TimelineDragAction {
    object None : TimelineDragAction()
    data class Pan(val startScrollX: Float, val startScrollY: Float) : TimelineDragAction()
    object ScrubPlayhead : TimelineDragAction()
    data class MoveClip(val clip: TimelineClip, val startOffsetFrames: Long) : TimelineDragAction()
    data class TrimStart(val clip: TimelineClip, val originalStart: Long) : TimelineDragAction()
    data class TrimEnd(val clip: TimelineClip, val originalEnd: Long) : TimelineDragAction()
}

object TimelineGestureController {
    fun hitTest(
        x: Float,
        y: Float,
        project: TimelineProject,
        pixelsPerFrame: Float,
        trackHeight: Float = 80f,
        trackPadding: Float = 12f
    ): TimelineClip? {
        if (!x.isFinite() || !y.isFinite() || !pixelsPerFrame.isFinite() || pixelsPerFrame <= 0f) return null
        project.tracks.forEachIndexed { trackIndex, track ->
            val top = trackIndex * (trackHeight + trackPadding)
            val bottom = top + trackHeight
            track.clips.asReversed().forEach { clip ->
                val left = TimelineUiGeometry.frameToX(clip.timelineStart, pixelsPerFrame)
                val right = TimelineUiGeometry.frameToX(clip.timelineEnd, pixelsPerFrame)
                if (x in left..right && y in top..bottom) return clip
            }
        }
        return null
    }

    fun determineDragAction(
        startOffset: Offset,
        scrollX: Float,
        scrollY: Float,
        rulerHeightPx: Float,
        project: TimelineProject,
        pixelsPerFrame: Float,
        selectedClipId: String?,
        playheadFrame: Long = 0L,
        playheadHitRadiusPx: Float = 24f,
        trackHeightPx: Float = 80f,
        trackPaddingPx: Float = 12f
    ): TimelineDragAction {
        val playheadX = TimelineUiGeometry.frameToX(playheadFrame, pixelsPerFrame) - scrollX
        if (startOffset.y < rulerHeightPx ||
            (playheadHitRadiusPx.isFinite() && playheadHitRadiusPx >= 0f &&
                startOffset.x.isFinite() && kotlin.math.abs(startOffset.x - playheadX) <= playheadHitRadiusPx)
        ) return TimelineDragAction.ScrubPlayhead
        if (!TimelineCoreV2Adapter.isAvailable()) return TimelineDragAction.Pan(scrollX, scrollY)

        val tapX = startOffset.x + scrollX
        val tapY = startOffset.y + scrollY - rulerHeightPx
        val hitClip = hitTest(tapX, tapY, project, pixelsPerFrame, trackHeightPx, trackPaddingPx)
        if (!TimelineCoreV2Adapter.isAvailable()) return TimelineDragAction.Pan(scrollX, scrollY)
        if (hitClip == null) return TimelineDragAction.Pan(scrollX, scrollY)

        val left = TimelineUiGeometry.frameToX(hitClip.timelineStart, pixelsPerFrame)
        val right = TimelineUiGeometry.frameToX(hitClip.timelineEnd, pixelsPerFrame)
        val trimThreshold = 40f
        if (hitClip.id == selectedClipId && tapX - left < trimThreshold) {
            return TimelineDragAction.TrimStart(hitClip, hitClip.timelineStart)
        }
        if (hitClip.id == selectedClipId && right - tapX < trimThreshold) {
            return TimelineDragAction.TrimEnd(hitClip, hitClip.timelineEnd)
        }
        val startFrame = TimelineUiGeometry.xToFrame(tapX, pixelsPerFrame)
        val offsetFrames = try {
            Math.subtractExact(startFrame, hitClip.timelineStart)
        } catch (_: ArithmeticException) {
            return TimelineDragAction.Pan(scrollX, scrollY)
        }
        return TimelineDragAction.MoveClip(hitClip, offsetFrames)
    }

    /** Keeps trim-edge drags inside the clip's legal timeline/source range before preview or commit. */
    fun clampTrimTarget(action: TimelineDragAction, targetFrame: Long, project: TimelineProject): Long {
        val clip = when (action) {
            is TimelineDragAction.TrimStart -> action.clip
            is TimelineDragAction.TrimEnd -> action.clip
            else -> return targetFrame
        }
        if (clip.duration <= 0L) return targetFrame
        return when (action) {
            is TimelineDragAction.TrimStart -> {
                val earliestTimelineFrame = (clip.timelineStart - clip.sourceIn).coerceAtLeast(0L)
                val latestTimelineFrame = clip.timelineEnd - 1L
                targetFrame.coerceIn(earliestTimelineFrame, latestTimelineFrame)
            }
            is TimelineDragAction.TrimEnd -> {
                val minimumEnd = Math.addExact(clip.timelineStart, 1L)
                val asset = project.mediaAssets.firstOrNull { it.assetId == clip.mediaId }
                val sourceEnd = asset?.metadata?.durationUs?.takeIf { it > 0L }?.let { durationUs ->
                    TimelineFrameMath.framesFromMicroseconds(durationUs, project.settings.getFpsRational())
                }?.takeIf { it > 0L } ?: clip.sourceOut
                val maxDuration = (sourceEnd - clip.sourceIn).coerceAtLeast(clip.duration)
                val maximumEnd = try {
                    Math.addExact(clip.timelineStart, maxDuration)
                } catch (_: ArithmeticException) {
                    Long.MAX_VALUE
                }
                targetFrame.coerceIn(minimumEnd, maximumEnd)
            }
            else -> targetFrame
        }
    }

    /** Builds the exact native command batch shared by preview and commit. */
    fun previewCommands(
        action: TimelineDragAction,
        targetFrame: Long,
        project: TimelineProject,
        activeTool: TimelineTool = TimelineTool.SELECTION,
        rippleScope: CoreV2RippleScope = CoreV2RippleScope.NONE,
        linkedClipIds: Set<String> = emptySet()
    ): List<CoreV2EditCommand>? {
        val primary = when (action) {
            is TimelineDragAction.MoveClip -> action.clip
            is TimelineDragAction.TrimStart -> action.clip
            is TimelineDragAction.TrimEnd -> action.clip
            else -> return null
        }
        val primaryCommand = previewCommand(action, targetFrame, project, activeTool, rippleScope)
            ?: return null
        val groupIds = (linkedClipIds + primary.id).distinct().sorted()

        if (primaryCommand.kind == CoreV2EditKind.MOVE && groupIds.size > 1) {
            return listOf(primaryCommand.copy(clipIds = groupIds))
        }
        if (activeTool == TimelineTool.SELECTION && groupIds.size > 1 &&
            (action is TimelineDragAction.TrimStart || action is TimelineDragAction.TrimEnd)
        ) {
            val isStart = action is TimelineDragAction.TrimStart
            val origin = if (isStart) primary.timelineStart else safeTimelineEnd(primary) ?: return null
            val delta = checkedDelta(targetFrame, origin) ?: return null
            return groupIds.map { id ->
                val clip = project.tracks.asSequence().flatMap { it.clips.asSequence() }
                    .firstOrNull { it.id == id } ?: return null
                val edge = if (isStart) clip.timelineStart else safeTimelineEnd(clip) ?: return null
                val target = try {
                    Math.addExact(edge, delta)
                } catch (_: ArithmeticException) {
                    return null
                }
                primaryCommand.copy(clipId = id, targetFrame = target)
            }
        }
        return listOf(primaryCommand)
    }

    /** Builds transport-only commands; native Core v2 owns edit validation and calculations. */
    fun previewCommand(
        action: TimelineDragAction,
        targetFrame: Long,
        project: TimelineProject,
        activeTool: TimelineTool = TimelineTool.SELECTION,
        rippleScope: CoreV2RippleScope = CoreV2RippleScope.NONE
    ): CoreV2EditCommand? {
        val clip = when (action) {
            is TimelineDragAction.MoveClip -> action.clip
            is TimelineDragAction.TrimStart -> action.clip
            is TimelineDragAction.TrimEnd -> action.clip
            else -> return null
        }
        val delta = when (action) {
            is TimelineDragAction.MoveClip -> checkedDelta(targetFrame, clip.timelineStart)
            is TimelineDragAction.TrimStart -> checkedDelta(targetFrame, clip.timelineStart)
            is TimelineDragAction.TrimEnd -> checkedDelta(targetFrame, safeTimelineEnd(clip) ?: return null)
            else -> return null
        }

        return when (activeTool) {
            TimelineTool.SLIP -> CoreV2EditCommand(
                kind = CoreV2EditKind.SLIP,
                clipId = clip.id,
                deltaFrames = delta ?: return null
            )
            TimelineTool.SLIDE -> CoreV2EditCommand(
                kind = CoreV2EditKind.SLIDE,
                clipId = clip.id,
                deltaFrames = delta ?: return null
            )
            TimelineTool.RIPPLE -> {
                if (action is TimelineDragAction.MoveClip) {
                    CoreV2EditCommand(
                        kind = CoreV2EditKind.MOVE,
                        clipId = clip.id,
                        targetTrackId = clip.trackId,
                        targetFrame = targetFrame
                    )
                } else {
                    CoreV2EditCommand(
                        kind = if (action is TimelineDragAction.TrimStart) {
                            CoreV2EditKind.TRIM_START
                        } else CoreV2EditKind.TRIM_END,
                        clipId = clip.id,
                        targetFrame = targetFrame,
                        ripple = rippleScope
                    )
                }
            }
            TimelineTool.ROLL -> {
                val isStart = action is TimelineDragAction.TrimStart
                if (action !is TimelineDragAction.TrimStart && action !is TimelineDragAction.TrimEnd) {
                    CoreV2EditCommand(
                        kind = CoreV2EditKind.MOVE,
                        clipId = clip.id,
                        targetTrackId = clip.trackId,
                        targetFrame = targetFrame
                    )
                } else {
                    val adjacent = adjacentClip(project, clip, isStart) ?: return null
                    CoreV2EditCommand(
                        kind = CoreV2EditKind.ROLL,
                        clipId = if (isStart) adjacent.id else clip.id,
                        otherClipId = if (isStart) clip.id else adjacent.id,
                        deltaFrames = delta ?: return null
                    )
                }
            }
            else -> when (action) {
                is TimelineDragAction.MoveClip -> CoreV2EditCommand(
                    kind = CoreV2EditKind.MOVE,
                    clipId = clip.id,
                    targetTrackId = clip.trackId,
                    targetFrame = targetFrame
                )
                is TimelineDragAction.TrimStart -> CoreV2EditCommand(
                    kind = CoreV2EditKind.TRIM_START,
                    clipId = clip.id,
                    targetFrame = targetFrame
                )
                is TimelineDragAction.TrimEnd -> CoreV2EditCommand(
                    kind = CoreV2EditKind.TRIM_END,
                    clipId = clip.id,
                    targetFrame = targetFrame
                )
                else -> null
            }
        }
    }

    private fun checkedDelta(target: Long, origin: Long): Long? = try {
        Math.subtractExact(target, origin)
    } catch (_: ArithmeticException) {
        null
    }

    private fun safeTimelineEnd(clip: TimelineClip): Long? = try {
        Math.addExact(clip.timelineStart, clip.duration)
    } catch (_: ArithmeticException) {
        null
    }

    /** Finds a unique time-adjacent clip; native Core v2 still validates adjacency on commit. */
    private fun adjacentClip(
        project: TimelineProject,
        clip: TimelineClip,
        before: Boolean
    ): TimelineClip? {
        val track = project.tracks.firstOrNull { it.id == clip.trackId } ?: return null
        val boundary = if (before) clip.timelineStart else safeTimelineEnd(clip) ?: return null
        val matches = track.clips.filter { candidate ->
            candidate.id != clip.id &&
                (if (before) safeTimelineEnd(candidate) == boundary else candidate.timelineStart == boundary)
        }
        return matches.singleOrNull()
    }

    /** Null means native snapping rejected/unavailable; callers must cancel the edit gesture. */
    fun snapFrame(
        inputFrame: Long,
        project: TimelineProject,
        playheadFrame: Long,
        pixelsPerFrame: Float,
        excludeClipId: String? = null,
        snapSettings: TimelineSnapSettings = TimelineSnapSettings()
    ): Long? {
        if (!TimelineCoreV2Adapter.isAvailable()) return null
        val thresholdFrames = TimelineCoreV2Adapter.pixelThresholdToFrames(
            snapSettings.snapThresholdPixels, pixelsPerFrame
        )
        return when (val result = TimelineCoreV2Adapter.snap(
            project = project,
            inputFrame = inputFrame,
            playheadFrame = playheadFrame,
            thresholdFrames = thresholdFrames,
            excludeClipId = excludeClipId,
            snapToPlayhead = snapSettings.snapToPlayhead,
            snapToClipEdges = snapSettings.snapToClips,
            snapToMarkers = snapSettings.snapToMarkers,
            gridIntervalFrames = if (snapSettings.snapToGrid) snapSettings.gridIntervalFrames else 0L
        )) {
            is CoreV2SnapOutcome.Snapped -> if (result.didSnap) result.frame else inputFrame
            is CoreV2SnapOutcome.Rejected -> null
        }
    }
}
