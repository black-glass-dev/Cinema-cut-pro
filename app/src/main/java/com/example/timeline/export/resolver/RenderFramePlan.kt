package com.example.timeline.export.resolver

import com.example.timeline.core.Rational

data class RenderFramePlan(
    val outputFrameIndex: Long,
    val outputTimeUs: Long,
    val projectFrame: Long,
    val activeVideoLayers: List<RenderClipInstance>, 
    val activeAudioLayers: List<RenderClipInstance>,
    val backgroundColor: String,
    val resolutionWidth: Int,
    val resolutionHeight: Int,
    val frameRate: Rational,
    val requiredSourceFrames: Map<String, Long> 
)
