package com.example.timeline.export.resolver

import com.example.timeline.core.TimelineProject
import com.example.timeline.export.model.ExportSettings

class AudioRenderResolver(
    private val project: TimelineProject,
    private val settings: ExportSettings
) {
    fun getActiveAudioClipsAtTime(presentationTimeUs: Long): List<RenderClipInstance> {
        val outputFrameIndex = ExportFrameTimeMapper.timeUsToFrame(presentationTimeUs, settings.frameRate)
            ?: return emptyList()
        return TimelineRenderResolver(project, settings).createPlanForFrame(outputFrameIndex).activeAudioLayers
    }
}
