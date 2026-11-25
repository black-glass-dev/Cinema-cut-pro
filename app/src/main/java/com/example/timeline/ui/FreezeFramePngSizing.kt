package com.example.timeline.ui

/** Keeps decoded frames at native detail up to 4K while bounding unusually large stills. */
internal object FreezeFramePngSizing {
    const val MAX_DIMENSION = 3840

    fun targetSize(width: Int, height: Int): Pair<Int, Int> {
        val safeWidth = width.coerceAtLeast(1)
        val safeHeight = height.coerceAtLeast(1)
        val scale = minOf(1.0, MAX_DIMENSION.toDouble() / maxOf(safeWidth, safeHeight).toDouble())
        return (safeWidth * scale).toInt().coerceAtLeast(1) to
            (safeHeight * scale).toInt().coerceAtLeast(1)
    }
}
