package com.example.timeline.export.resolver

import com.example.timeline.core.Rational
import com.example.timeline.core.TimelineProject
import com.example.timeline.core.TrackType
import com.example.timeline.engine.preview.TimelineFrameResolver
import com.example.timeline.export.model.ExportSettings

class TimelineRenderResolver(
    private val project: TimelineProject,
    private val exportSettings: ExportSettings
) {

    fun createPlanForFrame(outputFrameIndex: Long): RenderFramePlan {
        val outputTimeUs = requireNotNull(
            ExportFrameTimeMapper.outputFrameToTimeUs(outputFrameIndex, exportSettings)
        ) { "Output frame timestamp is invalid or overflows Long" }
        val projectFpsRational = project.settings.getFpsRational()

        // Convert outputTimeUs back to the project's timeline frame with exact integer math.
        val projectFrame = requireNotNull(
            ExportFrameTimeMapper.timeUsToProjectFrame(outputTimeUs, projectFpsRational)
        ) { "Project frame conversion is invalid or overflows Long" }

        val activeVideoLayers = mutableListOf<RenderClipInstance>()
        val activeAudioLayers = mutableListOf<RenderClipInstance>()
        val requiredSourceFrames = mutableMapOf<String, Long>()

        // Process tracks bottom to top for predictable z-indexing
        project.tracks.forEachIndexed { trackIndex, track ->
            if (track.isMuted && track.type == TrackType.AUDIO) return@forEachIndexed
            if (track.isLocked) {
                // Actually locked track can still be rendered, but we might skip hidden ones
            }
            if (!track.isVisible && track.type == TrackType.VIDEO) return@forEachIndexed

            val isVideoTrack = track.type == TrackType.VIDEO
            val isAudioTrack = track.type == TrackType.AUDIO
            
            // Find active clips on this track at projectFrame
            for (clip in track.clips) {
                if (!clip.isEnabled) continue
                
                if (projectFrame >= clip.timelineStart && projectFrame < clip.timelineEnd) {
                    val mediaAsset = project.mediaAssets.find { it.assetId == clip.mediaId }
                    if (mediaAsset == null) {
                        // Skip or could be an adjustment layer clip with no specific media
                        if (clip.type != com.example.timeline.core.ClipType.MEDIA) {
                             activeVideoLayers.add(
                                RenderClipInstance(
                                    clip = clip,
                                    mediaAsset = null,
                                    timelineTrackId = track.id,
                                    zIndex = trackIndex,
                                    evaluateTransform = clip.transform.evaluateTransformAtFrame(projectFrame),
                                    sourceTimeUs = 0L,
                                    sourceFrame = 0L,
                                    isAudioEnabled = false,
                                    isVideoEnabled = true,
                                    useProxyMode = false
                                )
                             )
                        }
                        continue
                    }

                    // Map projectFrame back to clip's sourceFrame using checked integer offsets.
                    val sourceFrame = requireNotNull(TimelineFrameResolver.resolveSourceFrame(projectFrame, clip)) {
                        "Source frame conversion overflows Long for clip ${clip.id}"
                    }
                    val sourceTimeUs = requireNotNull(
                        ExportFrameTimeMapper.projectFrameToTimeUs(sourceFrame, projectFpsRational)
                    ) { "Source timestamp conversion overflows Long for clip ${clip.id}" }

                    val useProxy = exportSettings.allowProxyExport && mediaAsset.proxyInfo != null && mediaAsset.proxyInfo.isProxyGenerated
                    
                    val isAudioEnabled = isAudioTrack || (isVideoTrack && mediaAsset.assetType == com.example.timeline.media.MediaAssetType.VIDEO) && !track.isMuted
                    
                    val clipInstance = RenderClipInstance(
                        clip = clip,
                        mediaAsset = mediaAsset,
                        timelineTrackId = track.id,
                        zIndex = trackIndex,
                        evaluateTransform = clip.transform.evaluateTransformAtFrame(projectFrame),
                        sourceTimeUs = sourceTimeUs,
                        sourceFrame = sourceFrame,
                        isAudioEnabled = isAudioEnabled,
                        isVideoEnabled = isVideoTrack,
                        useProxyMode = useProxy
                    )
                    
                    requiredSourceFrames[mediaAsset.assetId] = sourceFrame

                    if (isVideoTrack) {
                        activeVideoLayers.add(clipInstance)
                    }
                    if (isAudioEnabled) {
                        activeAudioLayers.add(clipInstance)
                    }
                }
            }
        }

        // Return plan
        return RenderFramePlan(
            outputFrameIndex = outputFrameIndex,
            outputTimeUs = outputTimeUs,
            projectFrame = projectFrame,
            activeVideoLayers = activeVideoLayers,
            activeAudioLayers = activeAudioLayers,
            backgroundColor = project.settings.canvasBackgroundColor,
            resolutionWidth = exportSettings.resolutionWidth,
            resolutionHeight = exportSettings.resolutionHeight,
            frameRate = Rational(
                exportSettings.frameRate.numerator,
                exportSettings.frameRate.denominator
            ),
            requiredSourceFrames = requiredSourceFrames
        )
    }
}
