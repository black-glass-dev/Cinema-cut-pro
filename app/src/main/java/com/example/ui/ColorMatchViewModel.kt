package com.example.ui

import android.content.Context
import android.graphics.Bitmap
import android.media.MediaMetadataRetriever
import android.net.Uri
import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.example.model.adjustments.colormatch.ColorMatchEngine
import com.example.model.adjustments.colormatch.ColorMatchParams
import com.example.model.adjustments.commands.SetAdjustmentParameterCommand
import com.example.model.adjustments.AdjustmentStack
import com.example.timeline.core.TimelineClip
import com.example.timeline.core.TimelineProject
import com.example.timeline.engine.preview.TimelineFrameResolver
import com.example.timeline.media.MediaAsset
import com.example.timeline.ui.TimelineViewModel
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

class ColorMatchViewModel(
    private val timelineViewModel: TimelineViewModel,
    private val context: Context
) : ViewModel() {

    private val _colorMatchParams = MutableStateFlow(ColorMatchParams())
    val colorMatchParams: StateFlow<ColorMatchParams> = _colorMatchParams.asStateFlow()

    private val _referenceClipId = MutableStateFlow<String?>(null)
    val referenceClipId: StateFlow<String?> = _referenceClipId.asStateFlow()

    private val _isMatching = MutableStateFlow(false)
    val isMatching: StateFlow<Boolean> = _isMatching.asStateFlow()

    fun updateParams(params: ColorMatchParams) {
        _colorMatchParams.value = params
    }

    fun setReferenceClipId(id: String) {
        _referenceClipId.value = id
    }

    fun performMatch() {
        val refClipId = _referenceClipId.value ?: return
        val state = timelineViewModel.uiState.value
        val targetClipId = state.selectedClipId ?: return
        val project = state.project

        val refClipResult = findClipAndAsset(project, refClipId) ?: return
        val targetClipResult = findClipAndAsset(project, targetClipId) ?: return

        _isMatching.value = true

        viewModelScope.launch {
            try {
                // Determine appropriate frame bounds to sample
                val refTimeUs = calculateMidpointSourceTimeUs(project, refClipResult.first) ?: return@launch
                val targetTimeUs = calculateMidpointSourceTimeUs(project, targetClipResult.first) ?: return@launch

                val refBitmap = extractFrame(refClipResult.second.originalUriString, refTimeUs)
                val targetBitmap = extractFrame(targetClipResult.second.originalUriString, targetTimeUs)

                if (refBitmap != null && targetBitmap != null) {
                    val currentTargetParams = targetClipResult.first.adjustments.params
                    val newParams = ColorMatchEngine.match(
                        source = targetBitmap,
                        reference = refBitmap,
                        params = _colorMatchParams.value,
                        currentAdjustments = currentTargetParams
                    )

                    // Execute the matched parameters update on the main thread via project state update
                    withContext(Dispatchers.Main) {
                        applyMatchedParams(targetClipId, targetClipResult.first, newParams, project)
                    }
                }
            } finally {
                _isMatching.value = false
            }
        }
    }

    private fun applyMatchedParams(clipId: String, oldClip: TimelineClip, newParams: com.example.model.adjustments.VideoAdjustmentParams, project: TimelineProject) {
        val track = project.tracks.firstOrNull { t -> t.clips.any { c -> c.id == clipId } } ?: return
        val clipIndex = track.clips.indexOfFirst { it.id == clipId }

        if (clipIndex != -1) {
            val oldStack = oldClip.adjustments
            val newStack = oldStack.copy(params = newParams)
            val newClip = oldClip.copy(adjustments = newStack)

            val newTracks = project.tracks.map {
                if (it.id == track.id) {
                    val clipsMutable = it.clips.toMutableList()
                    clipsMutable[clipIndex] = newClip
                    it.copy(clips = clipsMutable)
                } else {
                    it
                }
            }
            val newProject = project.copy(tracks = newTracks)

            // Register command for Undo/Redo
            val cmd = SetAdjustmentParameterCommand(
                commandName = "Color Match",
                targetClipId = clipId,
                projectBefore = project,
                projectAfter = newProject
            )
            timelineViewModel.pushHistoryCommand(cmd)
        }
    }

    private fun findClipAndAsset(project: TimelineProject, clipId: String): Pair<TimelineClip, MediaAsset>? {
        val clip = project.tracks.flatMap { it.clips }.find { it.id == clipId } ?: return null
        val asset = project.mediaAssets.find { it.assetId == clip.mediaId } ?: return null
        return Pair(clip, asset)
    }

    private fun calculateMidpointSourceTimeUs(project: TimelineProject, clip: TimelineClip): Long? {
        val fps = project.settings.getFpsRational()
        val midFrame = try {
            Math.addExact(clip.sourceIn, clip.duration / 2L)
        } catch (_: ArithmeticException) {
            return null
        }
        return TimelineFrameResolver.convertFrameToMicroseconds(midFrame, fps.numerator, fps.denominator)
    }

    private suspend fun extractFrame(uriString: String, timeUs: Long): Bitmap? = withContext(Dispatchers.IO) {
        var retriever: MediaMetadataRetriever? = null
        try {
            retriever = MediaMetadataRetriever()
            retriever.setDataSource(context, Uri.parse(uriString))
            retriever.getFrameAtTime(timeUs, MediaMetadataRetriever.OPTION_CLOSEST_SYNC)
        } catch (e: Exception) {
            if (com.example.BuildConfig.DEBUG) e.printStackTrace()
            null
        } finally {
            retriever?.release()
        }
    }
}
