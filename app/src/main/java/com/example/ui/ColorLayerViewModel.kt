package com.example.ui

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.example.model.colorgrade.ColorGradeLayer
import com.example.model.colorgrade.ColorGradeLayerStack
import com.example.model.colorgrade.ColorLayerType
import com.example.model.colorgrade.ColorGradeStack
import com.example.timeline.core.SnapshotCommand
import com.example.timeline.core.TimelineProject
import com.example.timeline.ui.TimelineViewModel
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import java.util.UUID

class ColorLayerViewModel(private val timelineViewModel: TimelineViewModel) : ViewModel() {
    private val _selectedLayerId = MutableStateFlow<String?>(null)
    private var opacityGestureStartProject: TimelineProject? = null
    private var opacityGestureClipId: String? = null
    val selectedLayerId: StateFlow<String?> = _selectedLayerId.asStateFlow()

    fun selectLayer(id: String?) {
        _selectedLayerId.value = id
    }

    private fun updateClipLayerStack(clipId: String, newStack: ColorGradeLayerStack) {
        val state = timelineViewModel.uiState.value
        val track = state.project.tracks.firstOrNull { t -> t.clips.any { it.id == clipId } } ?: return
        val clip = track.clips.find { it.id == clipId } ?: return

        val newClip = clip.copy(colorLayers = newStack)
        val newTracks = state.project.tracks.map { t ->
            if (t.id == track.id) {
                t.copy(clips = t.clips.map { if (it.id == clipId) newClip else it })
            } else t
        }
        val updatedProject = state.project.copy(tracks = newTracks)
        if (opacityGestureStartProject != null) {
            timelineViewModel.updateStateWithoutHistory(updatedProject)
        } else {
            timelineViewModel.pushHistoryCommand(
                SnapshotCommand(
                    commandName = "Edit Color Layers",
                    projectBefore = state.project,
                    projectAfter = updatedProject,
                    affectedClipIds = listOf(clipId)
                )
            )
        }
    }

    fun beginLayerOpacityChange(clipId: String) {
        if (opacityGestureStartProject != null) return
        opacityGestureStartProject = timelineViewModel.uiState.value.project
        opacityGestureClipId = clipId
    }

    fun endLayerOpacityChange() {
        val projectBefore = opacityGestureStartProject
        val projectAfter = timelineViewModel.uiState.value.project
        val clipId = opacityGestureClipId
        if (projectBefore != null && projectBefore != projectAfter) {
            timelineViewModel.pushHistoryCommand(
                SnapshotCommand(
                    commandName = "Adjust Color Layer Opacity",
                    projectBefore = projectBefore,
                    projectAfter = projectAfter,
                    affectedClipIds = clipId?.let(::listOf).orEmpty()
                )
            )
        }
        opacityGestureStartProject = null
        opacityGestureClipId = null
    }

    fun addLayer(clipId: String, type: ColorLayerType, name: String) {
        val state = timelineViewModel.uiState.value
        val clip = state.project.tracks.flatMap { it.clips }.find { it.id == clipId } ?: return
        val stack = clip.colorLayers
        val newLayer = ColorGradeLayer(id = UUID.randomUUID().toString(), name = name, type = type)
        val newStack = stack.copy(layers = stack.layers + newLayer)
        updateClipLayerStack(clipId, newStack)
        _selectedLayerId.value = newLayer.id
    }

    fun removeLayer(clipId: String, layerId: String) {
        val state = timelineViewModel.uiState.value
        val clip = state.project.tracks.flatMap { it.clips }.find { it.id == clipId } ?: return
        val stack = clip.colorLayers
        val newStack = stack.copy(layers = stack.layers.filter { it.id != layerId })
        updateClipLayerStack(clipId, newStack)
        if (_selectedLayerId.value == layerId) {
            _selectedLayerId.value = newStack.layers.lastOrNull()?.id
        }
    }

    fun renameLayer(clipId: String, layerId: String, newName: String) {
        val state = timelineViewModel.uiState.value
        val clip = state.project.tracks.flatMap { it.clips }.find { it.id == clipId } ?: return
        val stack = clip.colorLayers
        val newStack = stack.copy(layers = stack.layers.map { if (it.id == layerId) it.copy(name = newName) else it })
        updateClipLayerStack(clipId, newStack)
    }

    fun toggleLayerEnabled(clipId: String, layerId: String, enabled: Boolean) {
        val state = timelineViewModel.uiState.value
        val clip = state.project.tracks.flatMap { it.clips }.find { it.id == clipId } ?: return
        val stack = clip.colorLayers
        val newStack = stack.copy(layers = stack.layers.map { if (it.id == layerId) it.copy(enabled = enabled) else it })
        updateClipLayerStack(clipId, newStack)
    }

    fun updateLayerOpacity(clipId: String, layerId: String, opacity: Float) {
        beginLayerOpacityChange(clipId)
        val state = timelineViewModel.uiState.value
        val clip = state.project.tracks.flatMap { it.clips }.find { it.id == clipId } ?: return
        val stack = clip.colorLayers
        val newStack = stack.copy(layers = stack.layers.map { if (it.id == layerId) it.copy(opacity = opacity) else it })
        updateClipLayerStack(clipId, newStack)
    }

    fun reorderLayers(clipId: String, fromIndex: Int, toIndex: Int) {
        val state = timelineViewModel.uiState.value
        val clip = state.project.tracks.flatMap { it.clips }.find { it.id == clipId } ?: return
        val stack = clip.colorLayers
        val mutableList = stack.layers.toMutableList()
        val item = mutableList.removeAt(fromIndex)
        mutableList.add(toIndex, item)
        val newStack = stack.copy(layers = mutableList)
        updateClipLayerStack(clipId, newStack)
    }

    fun updateLayerGrade(clipId: String, layerId: String, newGrade: ColorGradeStack) {
        val state = timelineViewModel.uiState.value
        val clip = state.project.tracks.flatMap { it.clips }.find { it.id == clipId } ?: return
        val stack = clip.colorLayers
        val newStack = stack.copy(layers = stack.layers.map { if (it.id == layerId) it.copy(grade = newGrade) else it })
        updateClipLayerStack(clipId, newStack)
    }
}
