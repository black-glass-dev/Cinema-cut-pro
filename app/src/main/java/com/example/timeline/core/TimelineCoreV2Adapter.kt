package com.example.timeline.core

import com.example.timeline.engine.native.v2.NativeTimelineCoreV2
import java.math.BigInteger
import kotlin.math.ceil

private const val ERROR_INVALID_ARGUMENT = "TL_E_INVALID_ARGUMENT"
private const val ERROR_DUPLICATE_ID = "TL_E_DUPLICATE_ID"
private const val ERROR_OVERFLOW = "TL_E_OVERFLOW"
private const val ERROR_CORRUPT_DATA = "TL_E_CORRUPT_DATA"
private const val ERROR_NATIVE_UNAVAILABLE = "TL_E_NATIVE_UNAVAILABLE"
private const val EDIT_RESULT_HEADER_SIZE = 9
private const val EDIT_RESULT_CLIP_WIDTH = 7

/** The protocol codes intentionally match timeline_core_v2::EditKind. */
enum class CoreV2EditKind(val nativeCode: Long) {
    TRIM_START(0),
    TRIM_END(1),
    MOVE(2),
    INSERT(3),
    DELETE(4),
    SPLIT(5),
    SLIP(6),
    SLIDE(7),
    ROLL(8),
    SET_GROUP_LOCK(9)
}

enum class CoreV2RippleScope(val nativeCode: Long) {
    NONE(0),
    SAME_TRACK(1),
    ALL_TRACKS(2)
}

/** Command description only; all validation and edit calculations are performed by C++ v2. */
data class CoreV2EditCommand(
    val kind: CoreV2EditKind,
    val clipId: String? = null,
    val otherClipId: String? = null,
    val targetTrackId: String? = null,
    val targetFrame: Long = 0L,
    /** Signed edit delta for slip, slide, and roll. */
    val deltaFrames: Long = 0L,
    val minimumDurationFrames: Long = 1L,
    val ripple: CoreV2RippleScope = CoreV2RippleScope.NONE,
    val clipIds: List<String> = emptyList(),
    val insertedClip: TimelineClip? = null,
    /** Required only for SET_GROUP_LOCK; the native core owns validation and history. */
    val groupLocked: Boolean? = null
)

sealed class CoreV2EditOutcome {
    data class Applied(
        val project: TimelineProject,
        val changedClipIds: List<String>,
        /** FNV-1a state hash emitted by C++; this is not a cryptographic digest. */
        val projectHash: Long
    ) : CoreV2EditOutcome()

    data class Rejected(
        val errorCode: String,
        val clipId: String? = null,
        val trackId: String? = null
    ) : CoreV2EditOutcome()
}

sealed class CoreV2SnapOutcome {
    data class Snapped(
        val frame: Long,
        val didSnap: Boolean,
        val kindCode: Int,
        val targetId: String?,
        val distanceFrames: Long
    ) : CoreV2SnapOutcome()

    data class Rejected(val errorCode: String) : CoreV2SnapOutcome()
}

/**
 * Narrow conversion/transport layer between the persisted Kotlin DTOs and C++ Timeline Core v2.
 * The frame-based project model is translated with the project's rational frame rate; edit and
 * snap outcomes are never computed in Kotlin.
 */
object TimelineCoreV2Adapter {
    private val templateCacheLock = Any()
    private val historicalClipTemplates = linkedMapOf<String, LinkedHashMap<String, TimelineClip>>()
    private const val MAX_CACHED_PROJECTS = 8

    fun isAvailable(): Boolean = NativeTimelineCoreV2.isAvailable()

    /** Invalidates native snapshots after an out-of-band change to core timeline fields. */
    fun clearHistory(projectId: String) {
        if (projectId.isEmpty()) return
        synchronized(templateCacheLock) { historicalClipTemplates.remove(projectId) }
        if (NativeTimelineCoreV2.isAvailable()) {
            NativeTimelineCoreV2.clearHistory(StableIdTable.stableHash("project:$projectId"))
        }
    }

    /** Compares exactly the DTO fields serialized into the native project protocol. */
    fun hasSameNativeState(first: TimelineProject, second: TimelineProject): Boolean {
        if (first.id != second.id) return false
        return try {
            val left = packProject(first, emptyList())
            val right = packProject(second, emptyList())
            left.projectId == right.projectId &&
                left.fpsNumerator == right.fpsNumerator &&
                left.fpsDenominator == right.fpsDenominator &&
                left.trackData.contentEquals(right.trackData) &&
                left.clipData.contentEquals(right.clipData) &&
                left.markerData.contentEquals(right.markerData)
        } catch (_: RuntimeException) {
            false
        }
    }

    fun applyEdit(project: TimelineProject, command: CoreV2EditCommand): CoreV2EditOutcome =
        applyEdits(project, listOf(command))

    /** Pure command evaluation for preview/test paths; it does not alter native history. */
    fun applyEdits(project: TimelineProject, commands: List<CoreV2EditCommand>): CoreV2EditOutcome =
        applyEditsInternal(project, commands, commitToHistory = false)

    /** One JNI call validates the batch and records one native history transaction. */
    fun commitEdits(project: TimelineProject, commands: List<CoreV2EditCommand>): CoreV2EditOutcome =
        applyEditsInternal(project, commands, commitToHistory = true)

    private fun applyEditsInternal(
        project: TimelineProject,
        commands: List<CoreV2EditCommand>,
        commitToHistory: Boolean
    ): CoreV2EditOutcome {
        if (!NativeTimelineCoreV2.isAvailable()) {
            return CoreV2EditOutcome.Rejected(ERROR_NATIVE_UNAVAILABLE)
        }
        if (commands.isEmpty()) return CoreV2EditOutcome.Rejected(ERROR_INVALID_ARGUMENT)

        return try {
            val packed = packProject(project, commands)
            val raw = if (commitToHistory) {
                NativeTimelineCoreV2.commitEdits(
                    packed.projectId,
                    packed.fpsNumerator,
                    packed.fpsDenominator,
                    packed.trackData,
                    packed.clipData,
                    packed.markerData,
                    packed.commandData
                )
            } else {
                NativeTimelineCoreV2.applyEdits(
                    packed.projectId,
                    packed.fpsNumerator,
                    packed.fpsDenominator,
                    packed.trackData,
                    packed.clipData,
                    packed.markerData,
                    packed.commandData
                )
            } ?: return CoreV2EditOutcome.Rejected(ERROR_NATIVE_UNAVAILABLE)
            val decoded = decodeEditResult(project, packed, raw)
            if (commitToHistory && decoded is CoreV2EditOutcome.Applied) {
                rememberTemplates(project.id, project.tracks.flatMap { it.clips })
                rememberTemplates(decoded.project.id, decoded.project.tracks.flatMap { it.clips })
                rememberTemplates(decoded.project.id, packed.clipTemplates.values)
            }
            decoded
        } catch (failure: AdapterFailure) {
            CoreV2EditOutcome.Rejected(failure.errorCode)
        } catch (_: ArithmeticException) {
            CoreV2EditOutcome.Rejected(ERROR_OVERFLOW)
        } catch (_: IllegalArgumentException) {
            CoreV2EditOutcome.Rejected(ERROR_INVALID_ARGUMENT)
        }
    }

    fun undo(project: TimelineProject): CoreV2EditOutcome = historyTransition(project, undo = true)

    fun redo(project: TimelineProject): CoreV2EditOutcome = historyTransition(project, undo = false)

    private fun historyTransition(project: TimelineProject, undo: Boolean): CoreV2EditOutcome {
        if (!NativeTimelineCoreV2.isAvailable()) {
            return CoreV2EditOutcome.Rejected(ERROR_NATIVE_UNAVAILABLE)
        }
        return try {
            val packed = packProject(project, emptyList())
            val raw = if (undo) {
                NativeTimelineCoreV2.undo(
                    packed.projectId,
                    packed.fpsNumerator,
                    packed.fpsDenominator,
                    packed.trackData,
                    packed.clipData,
                    packed.markerData
                )
            } else {
                NativeTimelineCoreV2.redo(
                    packed.projectId,
                    packed.fpsNumerator,
                    packed.fpsDenominator,
                    packed.trackData,
                    packed.clipData,
                    packed.markerData
                )
            } ?: return CoreV2EditOutcome.Rejected(ERROR_NATIVE_UNAVAILABLE)
            val decoded = decodeEditResult(project, packed, raw)
            if (decoded is CoreV2EditOutcome.Applied) {
                rememberTemplates(project.id, project.tracks.flatMap { it.clips })
                rememberTemplates(decoded.project.id, decoded.project.tracks.flatMap { it.clips })
            }
            decoded
        } catch (failure: AdapterFailure) {
            CoreV2EditOutcome.Rejected(failure.errorCode)
        } catch (_: ArithmeticException) {
            CoreV2EditOutcome.Rejected(ERROR_OVERFLOW)
        } catch (_: IllegalArgumentException) {
            CoreV2EditOutcome.Rejected(ERROR_INVALID_ARGUMENT)
        }
    }

    private fun rememberTemplates(projectId: String, clips: Iterable<TimelineClip>) {
        synchronized(templateCacheLock) {
            val projectCache = historicalClipTemplates.remove(projectId) ?: linkedMapOf()
            clips.forEach { clip -> projectCache[clip.id] = clip }
            historicalClipTemplates[projectId] = projectCache
            while (historicalClipTemplates.size > MAX_CACHED_PROJECTS) {
                val oldestProjectId = historicalClipTemplates.entries.first().key
                historicalClipTemplates.remove(oldestProjectId)
            }
        }
    }

    private fun cachedTemplates(projectId: String): List<TimelineClip> = synchronized(templateCacheLock) {
        val templates = historicalClipTemplates.remove(projectId) ?: return@synchronized emptyList()
        historicalClipTemplates[projectId] = templates
        templates.values.toList()
    }

    /** Preview uses the exact same pure native edit command as commit, without storing a result. */
    fun previewEdit(project: TimelineProject, command: CoreV2EditCommand): CoreV2EditOutcome =
        applyEdit(project, command)

    fun snap(
        project: TimelineProject,
        inputFrame: Long,
        playheadFrame: Long,
        thresholdFrames: Long,
        excludeClipId: String? = null,
        snapToStart: Boolean = true,
        snapToPlayhead: Boolean = true,
        snapToClipEdges: Boolean = true,
        snapToMarkers: Boolean = true,
        gridIntervalFrames: Long = 0L
    ): CoreV2SnapOutcome {
        if (!NativeTimelineCoreV2.isAvailable()) {
            return CoreV2SnapOutcome.Rejected(ERROR_NATIVE_UNAVAILABLE)
        }
        if (thresholdFrames < 0L || gridIntervalFrames < 0L) {
            return CoreV2SnapOutcome.Rejected(ERROR_INVALID_ARGUMENT)
        }
        return try {
            val packed = packProject(project, emptyList())
            var flags = 0
            if (snapToStart) flags = flags or 1
            if (snapToPlayhead) flags = flags or 2
            if (snapToClipEdges) flags = flags or 4
            if (snapToMarkers) flags = flags or 8
            if (gridIntervalFrames > 0L) flags = flags or 16
            val raw = NativeTimelineCoreV2.snap(
                packed.projectId,
                packed.fpsNumerator,
                packed.fpsDenominator,
                packed.trackData,
                packed.clipData,
                packed.markerData,
                inputFrame,
                playheadFrame,
                thresholdFrames,
                gridIntervalFrames,
                flags,
                excludeClipId?.let(packed.clipIds::idFor) ?: 0L
            ) ?: return CoreV2SnapOutcome.Rejected(ERROR_NATIVE_UNAVAILABLE)
            decodeSnapResult(raw, packed)
        } catch (failure: AdapterFailure) {
            CoreV2SnapOutcome.Rejected(failure.errorCode)
        } catch (_: ArithmeticException) {
            CoreV2SnapOutcome.Rejected(ERROR_OVERFLOW)
        } catch (_: IllegalArgumentException) {
            CoreV2SnapOutcome.Rejected(ERROR_INVALID_ARGUMENT)
        }
    }

    /** UI-only conversion from a pixel threshold to an integer frame threshold. */
    fun pixelThresholdToFrames(thresholdPixels: Float, pixelsPerFrame: Float): Long {
        if (!thresholdPixels.isFinite() || !pixelsPerFrame.isFinite() ||
            thresholdPixels <= 0f || pixelsPerFrame <= 0f
        ) return 0L
        val frames = ceil(thresholdPixels.toDouble() / pixelsPerFrame.toDouble())
        return if (frames >= Long.MAX_VALUE.toDouble()) Long.MAX_VALUE else frames.toLong().coerceAtLeast(1L)
    }

    private fun decodeEditResult(
        originalProject: TimelineProject,
        packed: PackedProject,
        raw: LongArray
    ): CoreV2EditOutcome {
        if (raw.size < EDIT_RESULT_HEADER_SIZE) {
            return CoreV2EditOutcome.Rejected(ERROR_CORRUPT_DATA)
        }
        val status = raw[0]
        if (status != 0L) {
            return CoreV2EditOutcome.Rejected(
                errorName(status),
                packed.clipIds.nameFor(raw[1]),
                packed.trackIds.nameFor(raw[2])
            )
        }
        val clipCount = raw[4].toIntOrNull() ?: return CoreV2EditOutcome.Rejected(ERROR_CORRUPT_DATA)
        val changedCount = raw[5].toIntOrNull() ?: return CoreV2EditOutcome.Rejected(ERROR_CORRUPT_DATA)
        if (clipCount < 0 || changedCount < 0) return CoreV2EditOutcome.Rejected(ERROR_CORRUPT_DATA)
        val expectedSize = EDIT_RESULT_HEADER_SIZE.toLong() +
            clipCount.toLong() * EDIT_RESULT_CLIP_WIDTH + changedCount.toLong()
        if (expectedSize != raw.size.toLong()) return CoreV2EditOutcome.Rejected(ERROR_CORRUPT_DATA)

        val templates = packed.clipTemplates
        val clipsByTrack = originalProject.tracks.associate { it.id to mutableListOf<TimelineClip>() }
        var cursor = EDIT_RESULT_HEADER_SIZE
        repeat(clipCount) {
            val id = packed.clipIds.nameFor(raw[cursor])
                ?: return CoreV2EditOutcome.Rejected(ERROR_CORRUPT_DATA)
            val trackId = packed.trackIds.nameFor(raw[cursor + 1])
                ?: return CoreV2EditOutcome.Rejected(ERROR_CORRUPT_DATA)
            val template = templates[id]
                ?: return CoreV2EditOutcome.Rejected(ERROR_CORRUPT_DATA)
            val flags = raw[cursor + 5]
            val linkedGroupId = template.linkedGroupId
            val expectedGroupId = linkedGroupId?.let(packed.groupIds::idFor) ?: 0L
            if (raw[cursor + 6] != expectedGroupId) {
                return CoreV2EditOutcome.Rejected(ERROR_CORRUPT_DATA)
            }
            val updated = template.copy(
                id = id,
                trackId = trackId,
                sourceIn = raw[cursor + 2],
                sourceOut = raw[cursor + 3],
                timelineStart = raw[cursor + 4],
                isLocked = flags and 1L != 0L,
                isGroupLocked = flags and 2L != 0L,
                isEnabled = flags and 4L != 0L
            )
            val destination = clipsByTrack[trackId]
                ?: return CoreV2EditOutcome.Rejected(ERROR_CORRUPT_DATA)
            destination += updated
            cursor += EDIT_RESULT_CLIP_WIDTH
        }
        val updatedTracks = originalProject.tracks.map { track ->
            // Preserve the exact native clip-vector order because it participates in project hashes.
            track.copy(clips = clipsByTrack.getValue(track.id))
        }
        val changedIds = mutableListOf<String>()
        repeat(changedCount) {
            val id = packed.clipIds.nameFor(raw[cursor++])
                ?: return CoreV2EditOutcome.Rejected(ERROR_CORRUPT_DATA)
            changedIds += id
        }
        return CoreV2EditOutcome.Applied(
            originalProject.copy(tracks = updatedTracks),
            changedIds,
            raw[3]
        )
    }

    private fun decodeSnapResult(raw: LongArray, packed: PackedProject): CoreV2SnapOutcome {
        if (raw.size != 6) return CoreV2SnapOutcome.Rejected(ERROR_CORRUPT_DATA)
        if (raw[0] != 0L) return CoreV2SnapOutcome.Rejected(errorName(raw[0]))
        val targetId = packed.clipIds.nameFor(raw[4]) ?: packed.markerIds.nameFor(raw[4])
        return CoreV2SnapOutcome.Snapped(
            frame = raw[2],
            didSnap = raw[1] != 0L,
            kindCode = raw[3].toInt(),
            targetId = targetId,
            distanceFrames = raw[5]
        )
    }

    internal fun packProject(project: TimelineProject, commands: List<CoreV2EditCommand>): PackedProject {
        val fps = project.settings.getFpsRational()
        val fpsNumerator = fps.numerator.toLong()
        val fpsDenominator = fps.denominator.toLong()
        if (fpsNumerator <= 0L || fpsDenominator <= 0L) throw AdapterFailure(ERROR_INVALID_ARGUMENT)

        val trackIds = StableIdTable("track")
        val clipIds = StableIdTable("clip")
        val markerIds = StableIdTable("marker")
        val groupIds = StableIdTable("group")
        val templates = linkedMapOf<String, TimelineClip>()
        val clipsRequiringKnownBounds = clipsRequiringKnownMediaBounds(project, commands)
        project.tracks.forEach { track ->
            trackIds.idFor(track.id)
            track.clips.forEach { clip ->
                clipIds.idFor(clip.id)
                templates[clip.id] = clip
            }
        }
        cachedTemplates(project.id).forEach { clip ->
            clipIds.idFor(clip.id)
            templates.putIfAbsent(clip.id, clip)
        }
        project.markers.forEach { marker -> markerIds.idFor(marker.id) }
        commands.forEach { command ->
            command.clipId?.let(clipIds::idFor)
            command.otherClipId?.let { otherId ->
                clipIds.idFor(otherId)
                if (command.kind == CoreV2EditKind.SPLIT && templates[otherId] == null) {
                    val sourceId = command.clipId ?: throw AdapterFailure(ERROR_INVALID_ARGUMENT)
                    val source = templates[sourceId] ?: throw AdapterFailure(ERROR_INVALID_ARGUMENT)
                    templates[otherId] = source.copy(id = otherId)
                }
            }
            command.targetTrackId?.let(trackIds::idFor)
            command.clipIds.forEach { clipId -> clipIds.idFor(clipId) }
            command.insertedClip?.let { inserted ->
                clipIds.idFor(inserted.id)
                trackIds.idFor(inserted.trackId)
                templates[inserted.id] = inserted
            }
        }

        val trackData = ArrayList<Long>(project.tracks.size * 2)
        project.tracks.forEach { track ->
            var flags = 0L
            if (track.isLocked) flags = flags or 1L
            if (track.type == TrackType.TEXT || track.type == TrackType.ADJUSTMENT) flags = flags or 2L
            trackData += trackIds.idFor(track.id)
            trackData += flags
        }

        val clipData = ArrayList<Long>(project.tracks.sumOf { it.clips.size } * 9)
        project.tracks.forEach { track ->
            track.clips.forEach { clip ->
                appendClipRecord(
                    project,
                    clip,
                    clipData,
                    trackIds,
                    clipIds,
                    groupIds,
                    requireKnownMediaBounds = clip.id in clipsRequiringKnownBounds
                )
            }
        }

        val markerData = ArrayList<Long>(project.markers.size * 2)
        project.markers.forEach { marker ->
            markerData += markerIds.idFor(marker.id)
            markerData += marker.frame
        }

        val commandData = ArrayList<Long>()
        commandData += commands.size.toLong()
        commands.forEach { command ->
            commandData += command.kind.nativeCode
            commandData += command.clipId?.let(clipIds::idFor) ?: 0L
            commandData += command.otherClipId?.let(clipIds::idFor) ?: 0L
            commandData += command.targetTrackId?.let(trackIds::idFor) ?: 0L
            commandData += command.targetFrame
            commandData += if (command.kind == CoreV2EditKind.SET_GROUP_LOCK) {
                val groupLocked = command.groupLocked ?: throw AdapterFailure(ERROR_INVALID_ARGUMENT)
                if (groupLocked) 1L else 0L
            } else {
                if (command.groupLocked != null) throw AdapterFailure(ERROR_INVALID_ARGUMENT)
                command.deltaFrames
            }
            commandData += command.minimumDurationFrames
            commandData += command.ripple.nativeCode
            commandData += command.clipIds.size.toLong()
            commandData += if (command.insertedClip == null) 0L else 1L
            command.clipIds.forEach { commandData += clipIds.idFor(it) }
            command.insertedClip?.let { inserted ->
                appendClipRecord(
                    project,
                    inserted.copy(trackId = command.targetTrackId ?: inserted.trackId),
                    commandData,
                    trackIds,
                    clipIds,
                    groupIds,
                    requireKnownMediaBounds = true
                )
            }
        }

        return PackedProject(
            projectId = StableIdTable.stableHash("project:${project.id}"),
            fpsNumerator = fpsNumerator,
            fpsDenominator = fpsDenominator,
            trackData = trackData.toLongArray(),
            clipData = clipData.toLongArray(),
            markerData = markerData.toLongArray(),
            commandData = commandData.toLongArray(),
            trackIds = trackIds,
            clipIds = clipIds,
            markerIds = markerIds,
            groupIds = groupIds,
            clipTemplates = templates
        )
    }

    private fun appendClipRecord(
        project: TimelineProject,
        clip: TimelineClip,
        output: MutableList<Long>,
        trackIds: StableIdTable,
        clipIds: StableIdTable,
        groupIds: StableIdTable,
        requireKnownMediaBounds: Boolean = false
    ) {
        var flags = 0L
        if (clip.isLocked) flags = flags or 1L
        if (clip.isGroupLocked) flags = flags or 2L
        if (clip.isEnabled) flags = flags or 4L
        output += clipIds.idFor(clip.id)
        output += trackIds.idFor(clip.trackId)
        output += clip.sourceIn
        output += clip.sourceOut
        output += clip.timelineStart
        output += 0L
        output += availableEndFrame(project, clip, requireKnownMediaBounds)
        output += flags
        output += clip.linkedGroupId?.let(groupIds::idFor) ?: 0L
    }


    private fun clipsRequiringKnownMediaBounds(
        project: TimelineProject,
        commands: List<CoreV2EditCommand>
    ): Set<String> {
        val required = linkedSetOf<String>()
        commands.forEach { command ->
            when (command.kind) {
                CoreV2EditKind.TRIM_START,
                CoreV2EditKind.TRIM_END,
                CoreV2EditKind.SLIP -> command.clipId?.let(required::add)
                CoreV2EditKind.ROLL -> {
                    command.clipId?.let(required::add)
                    command.otherClipId?.let(required::add)
                }
                CoreV2EditKind.SLIDE -> {
                    val sourceTrackId = command.clipId?.let { id ->
                        project.tracks.firstOrNull { track -> track.clips.any { it.id == id } }?.id
                    }
                    if (sourceTrackId != null) {
                        project.tracks.firstOrNull { it.id == sourceTrackId }
                            ?.clips?.forEach { required += it.id }
                    }
                }
                CoreV2EditKind.INSERT -> command.insertedClip?.id?.let(required::add)
                else -> Unit
            }
        }
        return required
    }

    private fun availableEndFrame(
        project: TimelineProject,
        clip: TimelineClip,
        requireKnownMediaBounds: Boolean
    ): Long {
        if (clip.sourceIn < 0L || clip.sourceOut <= 0L || clip.sourceOut < clip.sourceIn) {
            throw AdapterFailure(ERROR_INVALID_ARGUMENT)
        }
        val asset = project.mediaAssets.firstOrNull { it.assetId == clip.mediaId }
        val durationUs = asset?.metadata?.durationUs ?: 0L
        if (durationUs <= 0L) {
            if (requireKnownMediaBounds) throw AdapterFailure(ERROR_INVALID_ARGUMENT)
            // A time-only edit may use the already referenced source endpoint as a conservative
            // upper bound. It must never infer additional media beyond sourceOut.
            return clip.sourceOut
        }
        val fps = project.settings.getFpsRational()
        if (fps.numerator <= 0 || fps.denominator <= 0) throw AdapterFailure(ERROR_INVALID_ARGUMENT)
        val numerator = BigInteger.valueOf(durationUs)
            .multiply(BigInteger.valueOf(fps.numerator.toLong()))
        val denominator = BigInteger.valueOf(1_000_000L)
            .multiply(BigInteger.valueOf(fps.denominator.toLong()))
        return numerator.divide(denominator).longValueExact()
    }

    private fun errorName(code: Long): String = when (code.toInt()) {
        1 -> "TL_E_INVALID_ARGUMENT"
        2 -> ERROR_OVERFLOW
        3 -> "TL_E_INVALID_RANGE"
        4 -> "TL_E_INVALID_PROJECT"
        5 -> ERROR_DUPLICATE_ID
        6 -> "TL_E_CLIP_NOT_FOUND"
        7 -> "TL_E_TRACK_NOT_FOUND"
        8 -> "TL_E_LOCKED_TRACK"
        9 -> "TL_E_LOCKED_CLIP"
        10 -> "TL_E_LOCKED_GROUP"
        11 -> "TL_E_SOURCE_OUT_OF_BOUNDS"
        12 -> "TL_E_MIN_DURATION"
        13 -> "TL_E_OVERLAP"
        14 -> "TL_E_NOT_ADJACENT"
        15 -> "TL_E_INVALID_EDIT"
        16 -> "TL_E_NO_UNDO"
        17 -> "TL_E_NO_REDO"
        18 -> "TL_E_ALLOCATION_FAILURE"
        19 -> "TL_E_UNSUPPORTED_VERSION"
        20 -> ERROR_CORRUPT_DATA
        21 -> "TL_E_LIMIT_EXCEEDED"
        22 -> "TL_E_HISTORY_CONFLICT"
        else -> "TL_E_UNKNOWN"
    }

    internal data class PackedProject(
        val projectId: Long,
        val fpsNumerator: Long,
        val fpsDenominator: Long,
        val trackData: LongArray,
        val clipData: LongArray,
        val markerData: LongArray,
        val commandData: LongArray,
        val trackIds: StableIdTable,
        val clipIds: StableIdTable,
        val markerIds: StableIdTable,
        val groupIds: StableIdTable,
        val clipTemplates: Map<String, TimelineClip>
    )

    internal class StableIdTable(private val namespace: String) {
        private val idByName = linkedMapOf<String, Long>()
        private val nameById = hashMapOf<Long, String>()

        fun idFor(name: String): Long {
            if (name.isEmpty()) throw AdapterFailure(ERROR_INVALID_ARGUMENT)
            idByName[name]?.let { return it }
            val id = stableHash("$namespace:$name")
            val collision = nameById[id]
            if (collision != null && collision != name) throw AdapterFailure(ERROR_DUPLICATE_ID)
            idByName[name] = id
            nameById[id] = name
            return id
        }

        fun nameFor(id: Long): String? = nameById[id]

        companion object {
            fun stableHash(value: String): Long {
                var hash = -3_750_763_034_362_895_579L // FNV-1a offset basis, signed bit representation.
                value.toByteArray(Charsets.UTF_8).forEach { byte ->
                    hash = (hash xor (byte.toInt() and 0xff).toLong()) * 1_099_511_628_211L
                }
                return if (hash == 0L) 1L else hash
            }
        }
    }

    private class AdapterFailure(val errorCode: String) : RuntimeException(errorCode)
}

private fun Long.toIntOrNull(): Int? = if (this in Int.MIN_VALUE.toLong()..Int.MAX_VALUE.toLong()) toInt() else null
