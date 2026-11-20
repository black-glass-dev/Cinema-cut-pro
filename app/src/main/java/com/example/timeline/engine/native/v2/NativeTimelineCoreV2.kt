package com.example.timeline.engine.native.v2

/** JNI entry points for the C++23 Timeline Core v2. No Kotlin edit/snap fallback is provided. */
object NativeTimelineCoreV2 {
    @Volatile private var available = false

    init {
        try {
            System.loadLibrary("native_timeline_core")
            available = true
        } catch (_: LinkageError) {
            available = false
        } catch (_: SecurityException) {
            available = false
        }
    }

    fun isAvailable(): Boolean = available

    fun markUnavailable() {
        available = false
    }

    internal fun applyEdits(
        projectId: Long,
        fpsNumerator: Long,
        fpsDenominator: Long,
        trackData: LongArray,
        clipData: LongArray,
        markerData: LongArray,
        commandData: LongArray
    ): LongArray? = nativeCall {
        nativeApplyEdits(
            projectId,
            fpsNumerator,
            fpsDenominator,
            trackData,
            clipData,
            markerData,
            commandData
        )
    }

    internal fun commitEdits(
        projectId: Long,
        fpsNumerator: Long,
        fpsDenominator: Long,
        trackData: LongArray,
        clipData: LongArray,
        markerData: LongArray,
        commandData: LongArray
    ): LongArray? = nativeCall {
        nativeCommitEdits(
            projectId,
            fpsNumerator,
            fpsDenominator,
            trackData,
            clipData,
            markerData,
            commandData
        )
    }

    internal fun clearHistory(projectId: Long) {
        if (!available) return
        try {
            nativeClearHistory(projectId)
        } catch (_: LinkageError) {
            markUnavailable()
        } catch (_: RuntimeException) {
            markUnavailable()
        }
    }

    internal fun undo(
        projectId: Long,
        fpsNumerator: Long,
        fpsDenominator: Long,
        trackData: LongArray,
        clipData: LongArray,
        markerData: LongArray
    ): LongArray? = nativeCall {
        nativeUndo(projectId, fpsNumerator, fpsDenominator, trackData, clipData, markerData)
    }

    internal fun redo(
        projectId: Long,
        fpsNumerator: Long,
        fpsDenominator: Long,
        trackData: LongArray,
        clipData: LongArray,
        markerData: LongArray
    ): LongArray? = nativeCall {
        nativeRedo(projectId, fpsNumerator, fpsDenominator, trackData, clipData, markerData)
    }

    internal fun snap(
        projectId: Long,
        fpsNumerator: Long,
        fpsDenominator: Long,
        trackData: LongArray,
        clipData: LongArray,
        markerData: LongArray,
        inputFrame: Long,
        playheadFrame: Long,
        thresholdFrames: Long,
        gridIntervalFrames: Long,
        flags: Int,
        excludeClipId: Long
    ): LongArray? = nativeCall {
        nativeSnap(
            projectId,
            fpsNumerator,
            fpsDenominator,
            trackData,
            clipData,
            markerData,
            inputFrame,
            playheadFrame,
            thresholdFrames,
            gridIntervalFrames,
            flags,
            excludeClipId
        )
    }

    private inline fun nativeCall(block: () -> LongArray): LongArray? {
        if (!available) return null
        return try {
            block()
        } catch (_: LinkageError) {
            markUnavailable()
            null
        } catch (_: RuntimeException) {
            markUnavailable()
            null
        }
    }

    external fun nativeApplyEdits(
        projectId: Long,
        fpsNumerator: Long,
        fpsDenominator: Long,
        trackData: LongArray,
        clipData: LongArray,
        markerData: LongArray,
        commandData: LongArray
    ): LongArray

    external fun nativeCommitEdits(
        projectId: Long,
        fpsNumerator: Long,
        fpsDenominator: Long,
        trackData: LongArray,
        clipData: LongArray,
        markerData: LongArray,
        commandData: LongArray
    ): LongArray

    external fun nativeClearHistory(projectId: Long)

    external fun nativeUndo(
        projectId: Long,
        fpsNumerator: Long,
        fpsDenominator: Long,
        trackData: LongArray,
        clipData: LongArray,
        markerData: LongArray
    ): LongArray

    external fun nativeRedo(
        projectId: Long,
        fpsNumerator: Long,
        fpsDenominator: Long,
        trackData: LongArray,
        clipData: LongArray,
        markerData: LongArray
    ): LongArray

    external fun nativeSnap(
        projectId: Long,
        fpsNumerator: Long,
        fpsDenominator: Long,
        trackData: LongArray,
        clipData: LongArray,
        markerData: LongArray,
        inputFrame: Long,
        playheadFrame: Long,
        thresholdFrames: Long,
        gridIntervalFrames: Long,
        flags: Int,
        excludeClipId: Long
    ): LongArray
}
