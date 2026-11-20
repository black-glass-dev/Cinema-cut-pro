package com.example.timeline.core

import com.example.model.adjustments.AdjustmentStack
import com.example.model.adjustments.TargetType
import com.example.timeline.core.transform.ClipTransform
import kotlinx.serialization.Serializable
import java.util.UUID

internal const val LINKED_GROUP_ID_METADATA_KEY = "linkedGroupId"

@Serializable
data class TimelineClip(
    val id: String = UUID.randomUUID().toString(),
    val trackId: String,
    val mediaId: String,
    val name: String,
    val sourceIn: Long,
    val sourceOut: Long,
    val timelineStart: Long,
    val isLocked: Boolean = false,
    val isEnabled: Boolean = true,
    val type: ClipType = ClipType.MEDIA,
    val volume: Float = 1.0f,
    val metadata: Map<String, String> = emptyMap(),
    val transform: ClipTransform = ClipTransform(),
    val adjustments: AdjustmentStack = AdjustmentStack("stack_$id", TargetType.CLIP, id),
    val colorGrade: com.example.model.colorgrade.ColorGradeStack =
        com.example.model.colorgrade.ColorGradeStack("grade_$id", com.example.model.colorgrade.ColorGradeTarget.CLIP, id),
    val colorLayers: com.example.model.colorgrade.ColorGradeLayerStack = com.example.model.colorgrade.ColorGradeLayerStack(),
    val markers: List<TimelineMarker> = emptyList(),
    /** Group lock is persisted per member so the native edit core can reject edits atomically. */
    val isGroupLocked: Boolean = false
) {
    /** Persistent link identity shared by clips in a linked audio/video group. */
    val linkedGroupId: String?
        get() = metadata[LINKED_GROUP_ID_METADATA_KEY]?.takeIf { it.isNotBlank() }

    val duration: Long
        get() = Math.subtractExact(sourceOut, sourceIn)

    val timelineEnd: Long
        get() = Math.addExact(timelineStart, duration)

    init {
        require(sourceIn >= 0L) { "sourceIn cannot be negative" }
        require(sourceOut >= sourceIn) { "sourceOut must be >= sourceIn" }
        require(timelineStart >= 0L) { "timelineStart cannot be negative" }
        val clipDuration = Math.subtractExact(sourceOut, sourceIn)
        require(timelineStart <= Long.MAX_VALUE - clipDuration) {
            "timelineStart plus clip duration overflows Long"
        }
    }
}
