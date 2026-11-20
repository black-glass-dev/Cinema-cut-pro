package com.example.timeline.engine

import com.example.model.adjustments.AdjustmentStack
import com.example.model.adjustments.VideoAdjustmentParams
import com.example.timeline.core.TimelineClip
import com.example.timeline.core.TimelineProject
import com.example.timeline.engine.preview.TimelineFrameResolver

object GradeEvaluationEngine {
    
    /**
     * Evaluates the complete grade at the given timeline frame.
     * Merges the clip grade with any applicable adjustment layers above it.
     */
    fun evaluateFrame(project: TimelineProject, frame: Long): VideoAdjustmentParams {
        val topmost = GradingFrameResolver.resolve(project, frame)
        if (topmost == null) {
            return VideoAdjustmentParams.default()
        }
        val (clip, trackIndex) = topmost

        var currentParams = evaluateClipGrade(clip, frame)

        // 5. Evaluate adjustment layers above clip
        val adjustmentLayers = AdjustmentLayerResolver.resolveLayers(project, frame, trackIndex)
        
        for (layer in adjustmentLayers) {
            // Evaluated layer params
            val layerParams = layer.adjustments.evaluateParamsAtFrame(frame)
            
            // Create a temp stack to use merge method
            val baseStack = AdjustmentStack(
                stackId = "base",
                targetType = com.example.model.adjustments.TargetType.CLIP,
                targetId = "base",
                params = currentParams
            )
            
            val layerStack = layer.adjustments.copy(params = layerParams)
            
            // Merge rules: The base stack applies the layer stack
            currentParams = baseStack.mergeWithAdjustmentLayer(layerStack)
        }
        
        return currentParams
    }

    /**
     * Evaluates one clip's own color composition: the shared adjustment block (including
     * keyframes), the persisted clip grade that may lack its mirror, and the clip's enabled
     * color nodes in order. This is the single composer shared by the Media3 preview evaluator
     * and the export backend so both render the same parameter block.
     */
    fun evaluateClipGrade(clip: TimelineClip, frame: Long): VideoAdjustmentParams {
        // Evaluate the clip's shared adjustment block at the current frame, then recover any
        // clip-grade values not mirrored into it by older projects. The mapper refreshes values
        // that still equal the static grade mirror (so grade keyframes work) without replacing
        // independent non-neutral adjustment controls.
        var currentParams = clip.adjustments.evaluateParamsAtFrame(frame)
        currentParams = com.example.model.colorgrade.engine.ColorGradeAdjustmentMapper
            .applyClipGradeIfNotMirrored(currentParams, clip.colorGrade, frame)

        // Color nodes are persisted separately from AdjustmentStack. Compose them into the same
        // parameter block consumed by the Media3 preview shader; previously their enabled state,
        // opacity, and order had no effect on preview pixels.
        if (clip.colorLayers.enabled) {
            var skippedLegacyMirroredNode = false
            for (layer in clip.colorLayers.layers) {
                if (!layer.enabled) continue
                // Older editor builds wrote an edited node into both colorGrade/adjustments and
                // the node stack. Apply that legacy single copy only once after upgrading.
                val mirrorsLegacyClipGrade = !skippedLegacyMirroredNode &&
                    layer.opacity == 1f && layer.grade == clip.colorGrade
                if (mirrorsLegacyClipGrade) {
                    skippedLegacyMirroredNode = true
                    continue
                }
                currentParams = com.example.model.colorgrade.engine.ColorGradeAdjustmentMapper.applyLayer(
                    base = currentParams,
                    layer = layer,
                    frame = frame
                )
            }
        }
        return currentParams
    }
}

