package com.example.timeline.ui

import com.example.timeline.core.CoreV2EditCommand
import com.example.timeline.core.CoreV2EditKind
import com.example.timeline.core.TimelineProject
import androidx.compose.foundation.gestures.detectDragGesturesAfterLongPress
import androidx.compose.ui.Modifier
import androidx.compose.ui.composed
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableFloatStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import kotlin.math.abs
import com.example.timeline.core.TimelineClip

/** Long-press drag recognizer attached to each clip; ordinary taps and trim drags remain intact. */
internal fun Modifier.moveAfterLongPress(
    clip: TimelineClip,
    pixelsPerFrame: Float,
    onClipSelected: (String) -> Unit,
    onDrop: (String, Long) -> Unit,
    onHold: (String, Float) -> Unit = { _, _ -> }
): Modifier = composed {
    var visualDragX by remember(clip.id) { mutableFloatStateOf(0f) }
    var isReordering by remember(clip.id) { mutableStateOf(false) }
    graphicsLayer {
        translationX = if (isReordering) visualDragX else 0f
        scaleX = if (isReordering) 1.025f else 1f
        scaleY = if (isReordering) 1.025f else 1f
    }.pointerInput(clip.id, pixelsPerFrame) {
    var startX = 0f
    var totalDragX = 0f
    var totalDragY = 0f
    detectDragGesturesAfterLongPress(
        onDragStart = { offset ->
            startX = offset.x
            totalDragX = 0f
            totalDragY = 0f
            visualDragX = 0f
            isReordering = true
        },
        onDrag = { change, amount ->
            change.consume()
            totalDragX += amount.x
            totalDragY += amount.y
            visualDragX = totalDragX
        },
        onDragEnd = {
            isReordering = false
            visualDragX = 0f
            if (abs(totalDragX) >= 2f && abs(totalDragX) >= abs(totalDragY) && pixelsPerFrame > 0f) {
                // Apply the finger's horizontal movement to the clip start itself. This keeps
                // drops within the clip's current order as real position edits instead of snapping
                // back to the old ordering slot.
                val targetStart = TimelineClipMove.targetStartFrame(
                    clip.timelineStart, totalDragX, pixelsPerFrame, clip.duration
                )
                if (targetStart != null) onDrop(clip.id, targetStart)
            } else if (totalDragX * totalDragX + totalDragY * totalDragY < 4f) {
                onHold(clip.id, startX)
            }
        },
        onDragCancel = {
            isReordering = false
            visualDragX = 0f
        }
    )
    }
}

/** Builds a precise, undoable position edit for a long-press clip drag. */
internal object TimelineClipMove {
    fun targetStartFrame(
        originalStartFrame: Long,
        dragDeltaPixels: Float,
        pixelsPerFrame: Float,
        clipDurationFrames: Long
    ): Long? {
        if (originalStartFrame < 0L || clipDurationFrames < 0L ||
            !dragDeltaPixels.isFinite() || !pixelsPerFrame.isFinite() || pixelsPerFrame <= 0f
        ) return null
        val deltaFrames = TimelineUiGeometry.xToFrame(dragDeltaPixels, pixelsPerFrame)
        val movedStart = try {
            Math.addExact(originalStartFrame, deltaFrames)
        } catch (_: ArithmeticException) {
            if (deltaFrames < 0L) 0L else Long.MAX_VALUE - clipDurationFrames
        }
        return movedStart.coerceAtLeast(0L).coerceAtMost(Long.MAX_VALUE - clipDurationFrames)
    }

    /**
     * Finds the closest legal start when an occupied, locked clip prevents ripple displacement.
     * Keeps the requested frame whenever it fits and otherwise clamps to the nearest gap edge;
     * ties follow the direction of travel.
     */
    fun resolveStartFrame(
        project: TimelineProject,
        clipId: String,
        requestedFrame: Long,
        movingClipIds: Set<String> = setOf(clipId)
    ): Long? {
        if (requestedFrame < 0L) return null
        val track = project.tracks.firstOrNull { candidate -> candidate.clips.any { it.id == clipId } }
            ?: return null
        val clip = track.clips.firstOrNull { it.id == clipId } ?: return null
        if (clip.duration < 0L) return null
        val maxStart = Long.MAX_VALUE - clip.duration
        val target = requestedFrame.coerceAtMost(maxStart)
        if (track.type == com.example.timeline.core.TrackType.TEXT ||
            track.type == com.example.timeline.core.TrackType.ADJUSTMENT
        ) return target

        val occupied = track.clips.asSequence()
            .filter { it.id !in movingClipIds }
            .mapNotNull { other ->
                if (other.timelineStart < 0L || other.duration < 0L) return@mapNotNull null
                val end = try { Math.addExact(other.timelineStart, other.duration) }
                    catch (_: ArithmeticException) { Long.MAX_VALUE }
                other.timelineStart to end
            }
            .sortedBy { it.first }
            .toList()

        val candidates = mutableListOf<Long>()
        var gapStart = 0L
        for ((occupiedStart, occupiedEnd) in occupied) {
            val latestStart = occupiedStart - clip.duration
            if (latestStart >= gapStart) candidates += target.coerceIn(gapStart, latestStart)
            gapStart = maxOf(gapStart, occupiedEnd)
        }
        if (gapStart <= maxStart) candidates += target.coerceAtLeast(gapStart).coerceAtMost(maxStart)
        if (candidates.isEmpty()) return null

        val originalStart = clip.timelineStart
        val movingRight = target > originalStart
        return candidates.minWithOrNull(
            compareBy<Long> { distanceBetween(it, target) }
                .thenBy { candidate -> if (movingRight) -candidate else candidate }
        )
    }

    private fun distanceBetween(left: Long, right: Long): Long =
        if (left >= right) left - right else right - left

    fun commands(project: TimelineProject, clipId: String, targetStartFrame: Long): List<CoreV2EditCommand> {
        val track = project.tracks.firstOrNull { candidate -> candidate.clips.any { it.id == clipId } }
            ?: return emptyList()
        val clip = track.clips.firstOrNull { it.id == clipId } ?: return emptyList()
        if (track.isLocked || clip.isLocked || clip.isGroupLocked || targetStartFrame < 0L) return emptyList()
        val target = targetStartFrame.coerceAtMost(Long.MAX_VALUE - clip.duration)
        if (target == clip.timelineStart) return emptyList()
        if (track.type == com.example.timeline.core.TrackType.TEXT ||
            track.type == com.example.timeline.core.TrackType.ADJUSTMENT
        ) return listOf(moveCommand(clip.id, track.id, target))

        val targetEnd = try { Math.addExact(target, clip.duration) } catch (_: ArithmeticException) { return emptyList() }
        var cursor = targetEnd
        val displaced = mutableListOf<Pair<com.example.timeline.core.TimelineClip, Long>>()
        val others = track.clips.asSequence()
            .filter { it.id != clipId }
            .sortedWith(compareBy({ it.timelineStart }, { it.id }))
        for (other in others) {
            val oldEnd = try { Math.addExact(other.timelineStart, other.duration) }
                catch (_: ArithmeticException) { return emptyList() }
            if (oldEnd <= target) continue
            val newStart = maxOf(other.timelineStart, cursor)
            if (newStart != other.timelineStart) {
                if (other.isLocked || other.isGroupLocked) {
                    val nearest = resolveStartFrame(project, clipId, target) ?: return emptyList()
                    return if (nearest == clip.timelineStart) emptyList()
                    else listOf(moveCommand(clip.id, track.id, nearest))
                }
                displaced += other to newStart
            }
            cursor = try { Math.addExact(newStart, other.duration) } catch (_: ArithmeticException) { return emptyList() }
        }
        if (displaced.isEmpty()) return listOf(moveCommand(clip.id, track.id, target))

        // Park every changed clip beyond the current tail first. Then apply the final layout in
        // timeline order. This keeps each native intermediate edit overlap-free while the whole
        // batch remains one undoable operation and leaves the dragged clip at the exact frame.
        val changedClips = (displaced.map { it.first } + clip)
            .distinctBy { it.id }
            .sortedWith(compareBy({ it.timelineStart }, { it.id }))
        var parkCursor = track.clips.maxOfOrNull { it.timelineEnd } ?: 0L
        parkCursor = try { Math.addExact(parkCursor, 1L) } catch (_: ArithmeticException) { return fallbackMove(project, clip, track.id, target) }
        val commands = mutableListOf<CoreV2EditCommand>()
        for (changed in changedClips) {
            val parkEnd = try { Math.addExact(parkCursor, changed.duration) }
                catch (_: ArithmeticException) { return fallbackMove(project, clip, track.id, target) }
            commands += moveCommand(changed.id, track.id, parkCursor)
            parkCursor = try { Math.addExact(parkEnd, 1L) }
                catch (_: ArithmeticException) { return fallbackMove(project, clip, track.id, target) }
        }
        val finalStarts = displaced.associate { it.first.id to it.second } + (clip.id to target)
        commands += changedClips.sortedWith(compareBy({ finalStarts.getValue(it.id) }, { it.id }))
            .map { moveCommand(it.id, track.id, finalStarts.getValue(it.id)) }
        return commands
    }

    private fun fallbackMove(
        project: TimelineProject,
        clip: com.example.timeline.core.TimelineClip,
        trackId: String,
        target: Long
    ): List<CoreV2EditCommand> {
        val nearest = resolveStartFrame(project, clip.id, target) ?: return emptyList()
        return if (nearest == clip.timelineStart) emptyList() else listOf(moveCommand(clip.id, trackId, nearest))
    }

    private fun moveCommand(clipId: String, trackId: String, target: Long) = CoreV2EditCommand(
        kind = CoreV2EditKind.MOVE,
        clipId = clipId,
        targetTrackId = trackId,
        targetFrame = target
    )
}
