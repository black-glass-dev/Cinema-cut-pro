package com.example.timeline.ui

import com.example.timeline.core.CoreV2EditCommand
import com.example.timeline.core.CoreV2EditKind

/** Converts an explicit playhead intent into a native command without calculating edit results. */
object TimelinePlayheadCommands {
    fun trimAtPlayhead(clipId: String, playheadFrame: Long, start: Boolean): CoreV2EditCommand? {
        if (clipId.isBlank() || playheadFrame < 0L) return null
        return CoreV2EditCommand(
            kind = if (start) CoreV2EditKind.TRIM_START else CoreV2EditKind.TRIM_END,
            clipId = clipId,
            targetFrame = playheadFrame
        )
    }
}
