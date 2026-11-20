package com.example.timeline.ui

import com.example.timeline.core.TimelineProject
import com.example.timeline.core.MoveClipCommand
import com.example.ui.editor.EditorViewModel

class SelectionToolController(
    private val timelineViewModel: TimelineViewModel,
    private val editorViewModel: EditorViewModel
) {

    private val linkedSelectionController = LinkedSelectionController()

    fun handleTap(clipId: String?, isMultiSelect: Boolean = false, linkedSelectionEnabled: Boolean = false) {
        val currentSelected = editorViewModel.selectedClipId.value
        if (clipId == null) {
            editorViewModel.selectClip(null)
            timelineViewModel.selectClip(null)
        } else {
            val project = timelineViewModel.uiState.value.project
            val clipsToSelect = linkedSelectionController.getLinkedClips(clipId, project, linkedSelectionEnabled)
            
            // For now, editorViewModel only supports single select easily, but timelineViewModel supports multi
            editorViewModel.selectClip(clipId)
            timelineViewModel.updateSelection(clipsToSelect)
        }
    }

    fun handleDragEnd(
        clipId: String,
        originalStart: Long,
        newStart: Long,
        linkedSelectionEnabled: Boolean = false
    ) {
        if (originalStart != newStart) {
            val project = timelineViewModel.uiState.value.project
            val clipsToMove = if (linkedSelectionEnabled) {
                linkedSelectionController.getLinkedClips(clipId, project, true)
            } else {
                setOf(clipId)
            }
            timelineViewModel.moveClipGroup(clipId, newStart, clipsToMove)
        }
    }
    }
