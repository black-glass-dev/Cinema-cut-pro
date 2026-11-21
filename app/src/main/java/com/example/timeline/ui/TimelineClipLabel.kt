package com.example.timeline.ui

import com.example.timeline.core.TimelineClip
import com.example.timeline.media.MediaAsset

/** Returns a short, readable label sized for a tiny timeline block; never shows fallback "Video N" text. */
internal fun timelineClipLabel(clip: TimelineClip, asset: MediaAsset?, widthDp: Float): String? {
    if (widthDp < 42f) return null
    val raw = (asset?.displayName ?: clip.name)
        .substringAfterLast('/')
        .substringBeforeLast('.', missingDelimiterValue = "")
        .trim()
    val meaningfulName = raw.takeIf { it.isNotBlank() && !it.matches(Regex("(?i)video\\s+\\d+")) }
    if (widthDp < 112f || meaningfulName == null) return "Clip"
    val estimatedCharacterCapacity = ((widthDp - 28f) / 6f).toInt().coerceAtLeast(0)
    return meaningfulName.takeIf { it.length <= estimatedCharacterCapacity } ?: "Clip"
}
