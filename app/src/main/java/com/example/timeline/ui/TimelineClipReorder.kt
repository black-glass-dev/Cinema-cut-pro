package com.example.timeline.ui

import com.example.timeline.core.CoreV2EditCommand
import com.example.timeline.core.CoreV2EditKind
import com.example.timeline.core.CoreV2RippleScope
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
internal fun Modifier.reorderAfterLongPress(
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
            onClipSelected(clip.id)
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
            if (abs(totalDragX) >= 12f && pixelsPerFrame > 0f) {
                // The clip's local drag coordinates are offset from its timeline origin;
                // scroll cancels when converting the viewport position back to timeline space.
                val timelineX = TimelineUiGeometry.frameToX(clip.timelineStart, pixelsPerFrame) +
                    startX + totalDragX
                onDrop(clip.id, TimelineUiGeometry.xToFrame(timelineX, pixelsPerFrame).coerceAtLeast(0L))
            } else if (totalDragX * totalDragX + totalDragY * totalDragY < 144f) {
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

/** Builds one atomic, undoable same-track reorder while retaining the original gap pattern. */
internal object TimelineClipReorder {
    fun commands(project: TimelineProject, clipId: String, dropFrame: Long): List<CoreV2EditCommand> {
        val track = project.tracks.firstOrNull { candidate -> candidate.clips.any { it.id == clipId } }
            ?: return emptyList()
        if (track.isLocked) return emptyList()
        val original = track.clips.sortedWith(compareBy({ it.timelineStart }, { it.id }))
        if (original.size < 2 || original.any { it.isLocked || it.isGroupLocked }) return emptyList()
        // Overlapping clips are intentional layer comps, not a simple serial edit list.
        if (original.zipWithNext().any { (left, right) -> right.timelineStart < left.timelineEnd }) return emptyList()
        val dragged = original.firstOrNull { it.id == clipId } ?: return emptyList()
        val remaining = original.filterNot { it.id == clipId }.toMutableList()
        val insertionIndex = remaining.indexOfFirst { candidate ->
            val midpoint = candidate.timelineStart + candidate.duration / 2L
            dropFrame < midpoint
        }.let { if (it < 0) remaining.size else it }
        remaining.add(insertionIndex, dragged)
        if (remaining.map { it.id } == original.map { it.id }) return emptyList()

        // Existing gaps belong to timeline slots, not source clips. Preserve those slot gaps
        // as clips are reordered, so deliberate pauses are not silently removed.
        val gaps = original.zipWithNext().map { (left, right) ->
            (right.timelineStart - left.timelineEnd).coerceAtLeast(0L)
        }
        var cursor = original.first().timelineStart
        return remaining.mapIndexedNotNull { index, clip ->
            val target = cursor
            val command = if (target != clip.timelineStart) CoreV2EditCommand(
                kind = CoreV2EditKind.MOVE,
                clipId = clip.id,
                targetTrackId = track.id,
                targetFrame = target,
                ripple = CoreV2RippleScope.NONE
            ) else null
            cursor = try {
                Math.addExact(cursor, clip.duration)
            } catch (_: ArithmeticException) {
                return emptyList()
            }
            if (index < gaps.size) {
                cursor = try {
                    Math.addExact(cursor, gaps[index])
                } catch (_: ArithmeticException) {
                    return emptyList()
                }
            }
            command
        }
    }
}
