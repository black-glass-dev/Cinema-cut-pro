package com.example.timeline.media

data class MediaAnalysisResult(
    val durationUs: Long,
    val width: Int,
    val height: Int,
    val rotation: Int,
    val frameRate: Float?,
    val defaultMimeType: String,
    val videoStreams: List<VideoStreamInfo>,
    val audioStreams: List<AudioStreamInfo>,
    val hasVideo: Boolean,
    val hasAudio: Boolean,
    val isVariableFrameRate: Boolean,
    val variableFrameRateDetectionComplete: Boolean = false
)
