package com.example.timeline.core

internal fun CoreV2EditOutcome.toV2TimelineResult(originalProject: TimelineProject): TimelineResult = when (this) {
    is CoreV2EditOutcome.Applied -> TimelineResult(project)
    is CoreV2EditOutcome.Rejected -> TimelineResult(originalProject, errorCode)
}

internal fun CoreV2EditOutcome.toV2SplitResult(
    originalProject: TimelineProject,
    newClipIds: List<String>
): SplitResult = when (this) {
    is CoreV2EditOutcome.Applied -> SplitResult(project, newClipIds)
    is CoreV2EditOutcome.Rejected -> SplitResult(originalProject, emptyList(), errorCode)
}

internal fun TrimMode.toCoreV2RippleScope(): CoreV2RippleScope = when (this) {
    TrimMode.NORMAL -> CoreV2RippleScope.NONE
    TrimMode.RIPPLE -> CoreV2RippleScope.SAME_TRACK
    TrimMode.RIPPLE_ALL_TRACKS -> CoreV2RippleScope.ALL_TRACKS
}
