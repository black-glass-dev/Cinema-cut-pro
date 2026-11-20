package com.example.timeline.model

import kotlinx.serialization.Serializable
import java.util.UUID

@Serializable
enum class TrackType { VIDEO, AUDIO }

@Serializable
data class MediaItem(
    val id: String,
    val path: String,
    val durationFrames: Long,
    val type: TrackType
)

@Serializable
data class Marker(
    val id: String = UUID.randomUUID().toString(),
    val frame: Long,
    val colorHex: String,
    val note: String
)

@Serializable
@Deprecated(
    message = "Compile guard: migrate to com.example.timeline.core.TimelineClip and TimelineCoreV2Adapter",
    level = DeprecationLevel.ERROR
)
data class Clip(
    val id: String = UUID.randomUUID().toString(),
    val mediaId: String,
    val sourceIn: Long, // Start frame in original media
    val sourceOut: Long, // End frame in original media
    val timelineIn: Long, // Start frame on the timeline
    val label: String = "Clip",
    val type: TrackType = TrackType.VIDEO,
    val transform: TransformState = TransformState()
) {
    val duration: Long get() = sourceOut - sourceIn
    val timelineOut: Long get() = timelineIn + duration
}

@Serializable
@Suppress("DEPRECATION_ERROR")
@Deprecated(
    message = "Compile guard: migrate to com.example.timeline.core.TimelineTrack",
    level = DeprecationLevel.ERROR
)
data class Track(
    val id: String = UUID.randomUUID().toString(),
    val type: TrackType,
    val name: String,
    val clips: List<Clip> = emptyList()
)

@Serializable
@Suppress("DEPRECATION_ERROR")
@Deprecated(
    message = "Compile guard: migrate to com.example.timeline.core.TimelineProject",
    level = DeprecationLevel.ERROR
)
data class Timeline(
    val id: String = UUID.randomUUID().toString(),
    val name: String = "Sundance_Edit_Final",
    val fps: Int = 24,
    val tracks: List<Track> = emptyList(),
    val markers: List<Marker> = emptyList()
)

@Deprecated(
    message = "Compile guard: use com.example.timeline.core.FrameTimecode",
    level = DeprecationLevel.ERROR
)
object TimecodeUtil {
    fun format(frames: Long, fps: Int): String {
        val f = frames % fps
        val totalSeconds = frames / fps
        val s = totalSeconds % 60
        val m = (totalSeconds / 60) % 60
        val h = totalSeconds / 3600
        return String.format("%02d:%02d:%02d:%02d", h, m, s, f)
    }
}
