package com.example.ui

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.example.model.adjustments.AdjustmentStack
import com.example.timeline.core.SnapshotCommand
import com.example.timeline.ui.TimelineViewModel
import kotlinx.coroutines.flow.MutableStateFlow
import com.example.model.colorgrade.GradeAutosaveHook
import com.example.model.colorgrade.ColorGradeStack
import com.example.model.colorgrade.engine.ColorGradeAdjustmentMapper
import com.example.model.colorgrade.GradeKeyframeManager

class ColorAdjustmentViewModel(
    private val timelineViewModel: TimelineViewModel
) : ViewModel() {

    private var dragStartProject: com.example.timeline.core.TimelineProject? = null
    private var dragStartClipId: String? = null

    // UI state flows from timelineViewModel
    val dragInProgress = MutableStateFlow(false)
    val isEyedropperActive = MutableStateFlow(false)

    fun toggleEyedropper() {
        isEyedropperActive.value = !isEyedropperActive.value
    }

    fun applyEyedropperColor(r: Float, g: Float, b: Float) {
        val hsl = FloatArray(3)
        com.example.model.colorgrade.engine.ColorMath.rgb2hsl(r, g, b, hsl)
        
        val state = timelineViewModel.uiState.value
        val clipId = state.selectedClipId ?: return
        val track = state.project.tracks.firstOrNull { t -> t.clips.any { c -> c.id == clipId } } ?: return
        val clipIndex = track.clips.indexOfFirst { it.id == clipId }
        if (clipIndex != -1) {
            val clip = track.clips[clipIndex]
            val grade = clip.colorGrade
            val newHsl = grade.hslAdjustments.copy(
                enabled = true,
                hueCenter = hsl[0],
                saturationMin = (hsl[1] - 0.1f).coerceAtLeast(0f),
                saturationMax = (hsl[1] + 0.1f).coerceAtMost(1f),
                luminanceMin = (hsl[2] - 0.1f).coerceAtLeast(0f),
                luminanceMax = (hsl[2] + 0.1f).coerceAtMost(1f)
            )
            
            beginAdjustmentDrag()
            updateColorGradeLive(grade.copy(hslAdjustments = newHsl))
            endAdjustmentDrag("Eyedropper Sample")
            isEyedropperActive.value = false
        }
    }

    fun beginAdjustmentDrag() {
        if (dragInProgress.value) return
        val state = timelineViewModel.uiState.value
        dragStartProject = state.project
        dragStartClipId = state.selectedClipId
        dragInProgress.value = true
    }

    fun endAdjustmentDrag(actionType: String = "Color Grade Change") {
        val projectBefore = dragStartProject
        val stateAfter = timelineViewModel.uiState.value
        val targetClipId = dragStartClipId ?: stateAfter.selectedClipId

        // A color gesture can update more than colorGrade: the shared preview params, a selected
        // node, or both. Snapshot the complete metadata edit so undo/redo restores the rendered
        // state instead of reverting only the UI model while leaving mirrored shader values behind.
        if (projectBefore != null && projectBefore != stateAfter.project) {
            timelineViewModel.pushHistoryCommand(
                SnapshotCommand(
                    commandName = actionType,
                    projectBefore = projectBefore,
                    projectAfter = stateAfter.project,
                    affectedClipIds = targetClipId?.let(::listOf).orEmpty()
                )
            )
        }
        dragStartProject = null
        dragStartClipId = null
        dragInProgress.value = false

        GradeAutosaveHook.onGestureEnded(viewModelScope) {
            timelineViewModel.forceSaveProject()
        }
    }

    fun selectAdjustmentTarget(targetType: com.example.model.adjustments.TargetType, targetId: String) {
        // Implementation stub
    }

    fun getSelectedClipAdjustmentStack(): AdjustmentStack? {
        val selectedClipId = timelineViewModel.uiState.value.selectedClipId ?: return null
        return timelineViewModel.uiState.value.project.tracks.flatMap { it.clips }.find { it.id == selectedClipId }?.adjustments
    }

    private var clipboardAdjustments: AdjustmentStack? = null

    fun copyAdjustmentsFromSelectedClip() {
        clipboardAdjustments = getSelectedClipAdjustmentStack()?.copy()
    }

    fun pasteAdjustmentsToSelectedClip() {
        val stack = clipboardAdjustments ?: return
        val state = timelineViewModel.uiState.value
        val clipId = state.selectedClipId ?: return
        val track = state.project.tracks.firstOrNull { t -> t.clips.any { c -> c.id == clipId } } ?: return
        val clipIndex = track.clips.indexOfFirst { it.id == clipId }
        if (clipIndex < 0) return
        val currentStack = track.clips[clipIndex].adjustments
        val sourceClip = state.project.tracks.flatMap { it.clips }.find { it.adjustments.stackId == stack.stackId }
        val newGrade = sourceClip?.colorGrade?.copy()
            ?: com.example.model.colorgrade.ColorGradeHelper.defaultColorGrade(clipId)

        beginAdjustmentDrag()
        val newStack = currentStack.copy(params = stack.params.copy())
        val oldClip = track.clips[clipIndex]
        val newClip = oldClip.copy(adjustments = newStack, colorGrade = newGrade)
        val newTracks = state.project.tracks.map {
            if (it.id == track.id) {
                val clipsMutable = it.clips.toMutableList()
                clipsMutable[clipIndex] = newClip
                it.copy(clips = clipsMutable)
            } else it
        }
        timelineViewModel.updateStateWithoutHistory(state.project.copy(tracks = newTracks))
        endAdjustmentDrag("Paste Grade")
    }

    fun saveAdjustmentPreset(name: String) {
        val stack = getSelectedClipGrade() ?: return
        com.example.model.colorgrade.ColorGradePresetManager.savePreset(name, "", stack)
    }

    fun applyAdjustmentPreset(presetId: String) {
        val targetClipId = timelineViewModel.uiState.value.selectedClipId ?: return
        val presets = com.example.model.colorgrade.ColorGradePresetManager.presets.value.flatMap { it.presets }
        val preset = presets.find { it.presetId == presetId } ?: return
        val currentGrade = getSelectedClipGrade() ?: return
        
        val newGrade = com.example.model.colorgrade.ColorGradeHelper.applyPreset(currentGrade, preset)
        beginAdjustmentDrag()
        updateColorGradeLive(newGrade)
        endAdjustmentDrag("ApplyPresetCommand")
    }

    private fun getSelectedClipGrade(): ColorGradeStack? {
        val selectedClipId = timelineViewModel.uiState.value.selectedClipId ?: return null
        return timelineViewModel.uiState.value.project.tracks.flatMap { it.clips }.find { it.id == selectedClipId }?.colorGrade
    }

    fun addOrUpdateKeyframe(parameterId: String, frame: Long, value: Float) {
        val grade = getSelectedClipGrade() ?: return
        beginAdjustmentDrag()
        val newGrade = GradeKeyframeManager.addOrUpdateKeyframe(grade, parameterId, frame, value)
        updateColorGradeLive(newGrade)
        endAdjustmentDrag("AddKeyframeCommand")
    }

    fun removeKeyframe(parameterId: String, frame: Long) {
        val grade = getSelectedClipGrade() ?: return
        beginAdjustmentDrag()
        val newGrade = GradeKeyframeManager.removeKeyframe(grade, parameterId, frame)
        updateColorGradeLive(newGrade)
        endAdjustmentDrag("RemoveKeyframeCommand")
    }

    fun toggleBeforeAfter() {
        // Handled directly via UI state temporarily, but can be maintained here
    }

    fun createAdjustmentLayer() {
        val state = timelineViewModel.uiState.value
        val playheadFrame = timelineViewModel.playheadFrame.value
        val newClipId = java.util.UUID.randomUUID().toString()
        
        val newClip = com.example.timeline.core.TimelineClip(
            id = newClipId,
            name = "Adjustment Layer",
            trackId = "",
            mediaId = "adjustment_layer_internal",
            type = com.example.timeline.core.ClipType.ADJUSTMENT,
            timelineStart = playheadFrame,
            sourceIn = 0,
            sourceOut = 100,
            adjustments = AdjustmentStack(java.util.UUID.randomUUID().toString(), com.example.model.adjustments.TargetType.CLIP, newClipId)
        )
        
        var adjustmentTrack = state.project.tracks.find { it.type == com.example.timeline.core.TrackType.ADJUSTMENT }
        val newTracks = state.project.tracks.toMutableList()
        if (adjustmentTrack == null) {
            val newTrack = com.example.timeline.core.TimelineTrack(
                name = "Adjustment Track",
                type = com.example.timeline.core.TrackType.ADJUSTMENT,
                clips = emptyList()
            )
            val updatedClip = newClip.copy(trackId = newTrack.id)
            val newTrackWithClip = newTrack.copy(clips = listOf(updatedClip))
            newTracks.add(newTrackWithClip)
        } else {
            val updatedClip = newClip.copy(trackId = adjustmentTrack.id)
            val trackIndex = newTracks.indexOf(adjustmentTrack)
            val updatedTrack = adjustmentTrack.copy(clips = adjustmentTrack.clips + updatedClip)
            newTracks[trackIndex] = updatedTrack
        }
        
        val newProject = state.project.copy(tracks = newTracks)
        timelineViewModel.updateStateWithoutHistory(newProject) // or pushHistoryCommand based on design
    }

    fun applyAdjustmentToTrack() {
        // Implementation stub
    }

    fun evaluateCurrentFrameAdjustments(): com.example.model.adjustments.VideoAdjustmentParams =
        com.example.timeline.engine.GradeEvaluationEngine.evaluateFrame(
            timelineViewModel.uiState.value.project,
            timelineViewModel.playheadFrame.value
        )

    fun updateAdjustmentParamLive(parameterId: String, value: Float) {
        val state = timelineViewModel.uiState.value
        val clipId = state.selectedClipId ?: return
        val track = state.project.tracks.firstOrNull { t -> t.clips.any { c -> c.id == clipId } } ?: return
        val clipIndex = track.clips.indexOfFirst { it.id == clipId }
        
        if (clipIndex != -1) {
            val oldClip = track.clips[clipIndex]
            val newStack = oldClip.adjustments.updateParam(parameterId, value)
            val newClip = oldClip.copy(adjustments = newStack)
            val newTracks = state.project.tracks.map {
                if (it.id == track.id) {
                    val clipsMutable = it.clips.toMutableList()
                    clipsMutable[clipIndex] = newClip
                    it.copy(clips = clipsMutable)
                } else {
                    it
                }
            }
            val newProject = state.project.copy(tracks = newTracks)
            timelineViewModel.updateStateWithoutHistory(newProject)
        }
    }

    fun setLutId(lutId: String?) {
        val state = timelineViewModel.uiState.value
        val clipId = state.selectedClipId ?: return
        val track = state.project.tracks.firstOrNull { t -> t.clips.any { c -> c.id == clipId } } ?: return
        val clipIndex = track.clips.indexOfFirst { it.id == clipId }
        if (clipIndex < 0) return
        beginAdjustmentDrag()

        if (clipIndex != -1) {
            val oldClip = track.clips[clipIndex]
            val newParams = oldClip.adjustments.params.copy(lutId = lutId)
            val newStack = oldClip.adjustments.copy(params = newParams)
            val newClip = oldClip.copy(adjustments = newStack)
            val newTracks = state.project.tracks.map {
                if (it.id == track.id) {
                    val clipsMutable = it.clips.toMutableList()
                    clipsMutable[clipIndex] = newClip
                    it.copy(clips = clipsMutable)
                } else {
                    it
                }
            }
            val newProject = state.project.copy(tracks = newTracks)
            timelineViewModel.updateStateWithoutHistory(newProject)
        }
        endAdjustmentDrag("Set LUT")
    }

    fun updateColorGradeLive(
        newGrade: com.example.model.colorgrade.ColorGradeStack,
        colorLayerId: String? = null
    ) {
        val state = timelineViewModel.uiState.value
        val clipId = state.selectedClipId ?: return
        val track = state.project.tracks.firstOrNull { t -> t.clips.any { c -> c.id == clipId } } ?: return
        val clipIndex = track.clips.indexOfFirst { it.id == clipId }
        
        if (clipIndex != -1) {
            val oldClip = track.clips[clipIndex]
            
            // Node edits remain in the node stack. The clip-level grade and its mirrored shader
            // params are only updated when the regular (non-node) color panel is active.
            val newClip = if (colorLayerId == null) {
                val newParams = ColorGradeAdjustmentMapper.applyClipGradeChanges(
                    base = oldClip.adjustments.params,
                    previousGrade = oldClip.colorGrade,
                    newGrade = newGrade
                )
                oldClip.copy(
                    colorGrade = newGrade,
                    adjustments = oldClip.adjustments.copy(params = newParams)
                )
            } else {
                val newColorLayers = oldClip.colorLayers.copy(
                    layers = oldClip.colorLayers.layers.map { layer ->
                        if (layer.id == colorLayerId) layer.copy(grade = newGrade) else layer
                    }
                )
                oldClip.copy(colorLayers = newColorLayers)
            }
            val newTracks = state.project.tracks.map {
                if (it.id == track.id) {
                    val clipsMutable = it.clips.toMutableList()
                    clipsMutable[clipIndex] = newClip
                    it.copy(clips = clipsMutable)
                } else {
                    it
                }
            }
            val newProject = state.project.copy(tracks = newTracks)
            timelineViewModel.updateStateWithoutHistory(newProject)
            GradeAutosaveHook.onGradeChanged(viewModelScope) {
                timelineViewModel.forceSaveProject()
            }
        }
    }

    fun updateAdjustmentParam(parameterId: String, value: Float) {
        beginAdjustmentDrag()
        updateAdjustmentParamLive(parameterId, value)
        endAdjustmentDrag(parameterId)
    }

    fun resetAdjustmentParam(parameterId: String) {
        val state = timelineViewModel.uiState.value
        val targetClipId = state.selectedClipId ?: return
        val track = state.project.tracks.firstOrNull { t -> t.clips.any { c -> c.id == targetClipId } } ?: return
        val clipIndex = track.clips.indexOfFirst { it.id == targetClipId }
        if (clipIndex < 0) return
        beginAdjustmentDrag()

        if (clipIndex != -1) {
            val oldClip = track.clips[clipIndex]
            
            // Helper to pull default value correctly
            val defaultValue = when(parameterId) {
                "liftR", "liftG", "liftB" -> 0f
                "gammaR", "gammaG", "gammaB" -> 1f
                "gainR", "gainG", "gainB" -> 1f
                "offsetR", "offsetG", "offsetB" -> 0f
                "skinToneProtection" -> 0f
                "shadowBalanceR", "shadowBalanceG", "shadowBalanceB" -> 0f
                "midtoneBalanceR", "midtoneBalanceG", "midtoneBalanceB" -> 0f
                "highlightBalanceR", "highlightBalanceG", "highlightBalanceB" -> 0f
                "saturation" -> 1f
                "redChannelMultiplier", "greenChannelMultiplier", "blueChannelMultiplier" -> 1f
                else -> 0f // All other typical slider defaults are 0f
            }
            
            val newStack = oldClip.adjustments.updateParam(parameterId, defaultValue)
            val newClip = oldClip.copy(adjustments = newStack)
            val newTracks = state.project.tracks.map {
                if (it.id == track.id) {
                    val clipsMutable = it.clips.toMutableList()
                    clipsMutable[clipIndex] = newClip
                    it.copy(clips = clipsMutable)
                } else {
                    it
                }
            }
            val newProject = state.project.copy(tracks = newTracks)
            timelineViewModel.updateStateWithoutHistory(newProject)
        }
        
        endAdjustmentDrag(parameterId)
    }

    fun resetAllAdjustments() {
        val state = timelineViewModel.uiState.value
        val targetClipId = state.selectedClipId ?: return
        val track = state.project.tracks.firstOrNull { t -> t.clips.any { c -> c.id == targetClipId } } ?: return
        val clipIndex = track.clips.indexOfFirst { it.id == targetClipId }
        if (clipIndex < 0) return
        beginAdjustmentDrag()

        if (clipIndex != -1) {
            val oldClip = track.clips[clipIndex]
            val defaultStack = oldClip.adjustments.resetAll().copy(
                enabled = true,
                opacity = 1f,
                modifiedAt = System.currentTimeMillis()
            )
            val resetColorLayers = oldClip.colorLayers.copy(
                enabled = true,
                layers = oldClip.colorLayers.layers.map { layer ->
                    layer.copy(
                        enabled = true,
                        opacity = 1f,
                        grade = layer.grade.resetAllGrade()
                    )
                }
            )
            val newClip = oldClip.copy(
                adjustments = defaultStack,
                colorGrade = oldClip.colorGrade.resetAllGrade(),
                colorLayers = resetColorLayers
            )
            val newTracks = state.project.tracks.map {
                if (it.id == track.id) {
                    val clipsMutable = it.clips.toMutableList()
                    clipsMutable[clipIndex] = newClip
                    it.copy(clips = clipsMutable)
                } else {
                    it
                }
            }
            val newProject = state.project.copy(tracks = newTracks)
            timelineViewModel.updateStateWithoutHistory(newProject)
        }
        endAdjustmentDrag("All Adjustments")
    }
}
