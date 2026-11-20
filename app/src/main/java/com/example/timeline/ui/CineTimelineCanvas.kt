package com.example.timeline.ui
import androidx.compose.material3.Text

import androidx.compose.foundation.background
import androidx.compose.foundation.gestures.*
import androidx.compose.foundation.layout.*
import androidx.compose.runtime.*
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.draw.clipToBounds
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.layout.Layout
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.compose.ui.text.font.FontWeight
import com.example.timeline.core.CoreV2EditCommand
import com.example.timeline.core.CoreV2EditOutcome
import com.example.timeline.core.TimelineCoreV2Adapter
import com.example.timeline.core.TimelineProject
import com.example.timeline.core.TrackType
import kotlin.math.max
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.input.pointer.util.VelocityTracker
import androidx.compose.ui.unit.IntOffset
import androidx.compose.ui.Alignment
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Add
import kotlinx.coroutines.flow.StateFlow



private fun actionClipId(action: TimelineDragAction): String? = when (action) {
    is TimelineDragAction.MoveClip -> action.clip.id
    is TimelineDragAction.TrimStart -> action.clip.id
    is TimelineDragAction.TrimEnd -> action.clip.id
    else -> null
}

@Composable
fun CineTimelineCanvas(
    onImportClick: () -> Unit = {},
    uiState: TimelineUiState,
    playheadFrameFlow: StateFlow<Long>,
    isPlaying: Boolean,
    modifier: Modifier = Modifier,
    onClipSelected: (String?) -> Unit,
    onPlayheadMoved: (Long) -> Unit,
    onNativeEditCommit: (List<CoreV2EditCommand>, String) -> Unit,
    onZoomChange: ((Float) -> Unit)? = null,
    onSplit: () -> Unit = {},
    onDelete: () -> Unit = {},
    onSetGroupLocked: (String, Boolean) -> Unit = { _, _ -> },
    onRenameTrack: (String, String) -> Unit = { _, _ -> },
    onDeleteTrack: (String) -> Unit = {},
    onMoveTrack: (String, Int) -> Unit = { _, _ -> }
) {
    var scrollX by remember { mutableStateOf(0f) }
    var scrollY by remember { mutableStateOf(0f) }
    var maxVerticalScroll by remember { mutableFloatStateOf(0f) }
    var isDragging by remember { mutableStateOf(false) }
    
    var contextMenuVisible by remember { mutableStateOf(false) }
    var contextMenuClipId by remember { mutableStateOf<String?>(null) }
    var contextMenuOffset by remember { mutableStateOf(IntOffset.Zero) }
    var dragAction by remember { mutableStateOf<TimelineDragAction>(TimelineDragAction.None) }
    var dragGhostStartFrame by remember { mutableStateOf<Long?>(null) }
    var dragGhostEndFrame by remember { mutableStateOf<Long?>(null) }
    var dragPreviewProject by remember { mutableStateOf<TimelineProject?>(null) }
    var dragPreviewCommands by remember { mutableStateOf<List<CoreV2EditCommand>?>(null) }
    var trackToRename by remember { mutableStateOf<com.example.timeline.core.TimelineTrack?>(null) }
    var renameText by remember { mutableStateOf("") }
    
    val pixelsPerFrame = uiState.pixelsPerFrame
    val trackHeightDp = TimelineDesignTokens.TrackHeight
    val trackPaddingDp = TimelineDesignTokens.TrackGap
    val trackHeight = with(androidx.compose.ui.platform.LocalDensity.current) { trackHeightDp.toPx() }
    val trackPadding = with(androidx.compose.ui.platform.LocalDensity.current) { trackPaddingDp.toPx() }
    val contextMenuLockTarget = remember(uiState.project, contextMenuClipId) {
        contextMenuClipId?.let { TimelineGroupLockController.targetFor(uiState.project, it) }
    }

    fun updateNativePreview(action: TimelineDragAction, targetFrame: Long) {
        val commands = TimelineGestureController.previewCommands(
            action = action,
            targetFrame = targetFrame,
            project = uiState.project
        )
        if (commands == null) {
            dragPreviewProject = null
            dragPreviewCommands = null
            dragGhostStartFrame = null
            dragGhostEndFrame = null
            return
        }
        when (val preview = TimelineCoreV2Adapter.applyEdits(uiState.project, commands)) {
            is CoreV2EditOutcome.Applied -> {
                dragPreviewProject = preview.project
                dragPreviewCommands = commands
                val updated = preview.project.tracks.asSequence().flatMap { it.clips.asSequence() }
                    .firstOrNull { it.id == actionClipId(action) }
                when (action) {
                    is TimelineDragAction.MoveClip,
                    is TimelineDragAction.TrimStart -> dragGhostStartFrame = updated?.timelineStart
                    is TimelineDragAction.TrimEnd -> dragGhostEndFrame = updated?.timelineEnd
                    else -> Unit
                }
            }
            is CoreV2EditOutcome.Rejected -> {
                dragPreviewProject = null
                dragPreviewCommands = null
                dragGhostStartFrame = null
                dragGhostEndFrame = null
            }
        }
    }

    // Transformable state for pinch zoom and pan
    val transformableState = rememberTransformableState { zoomChange, panChange, _ ->
        if (onZoomChange != null) {
            onZoomChange(pixelsPerFrame * zoomChange)
        }
        scrollX = max(0f, scrollX - panChange.x)
        scrollY = (scrollY - panChange.y).coerceIn(0f, maxVerticalScroll)
    }
    
    BoxWithConstraints(
        modifier = modifier
            .fillMaxSize()
            .clipToBounds()
            .background(TimelineDesignTokens.Pane)
            .transformable(transformableState)
    ) {
        val viewportWidth = constraints.maxWidth
        val trackHeaderWidth = if (uiState.project.tracks.isNotEmpty()) TimelineDesignTokens.TrackHeaderWidth else 0.dp
        val trackHeaderWidthPx = with(androidx.compose.ui.platform.LocalDensity.current) { trackHeaderWidth.toPx() }
        val timelineViewportWidth = (viewportWidth - trackHeaderWidthPx).coerceAtLeast(0f)
        val centerX = timelineViewportWidth / 2f
        val rulerHeightDp = TimelineDesignTokens.RulerHeight
        val rulerHeightPx = with(androidx.compose.ui.platform.LocalDensity.current) { rulerHeightDp.toPx() }
        val renderProject = dragPreviewProject ?: uiState.project
        val contentHeightPx = rulerHeightPx +
            renderProject.tracks.size * (trackHeight + trackPadding)
        val maxScrollY = max(0f, contentHeightPx - constraints.maxHeight.toFloat())
        val visibleTimelineClips = remember(
            renderProject.tracks, scrollX, scrollY, pixelsPerFrame, constraints.maxWidth, constraints.maxHeight, rulerHeightPx
        ) {
            TimelineVirtualization.visibleClips(
                project = renderProject,
                scrollX = scrollX,
                scrollY = scrollY,
                viewportWidthPx = timelineViewportWidth,
                viewportHeightPx = constraints.maxHeight.toFloat(),
                pixelsPerFrame = pixelsPerFrame,
                trackHeightPx = trackHeight,
                trackPaddingPx = trackPadding,
                rulerHeightPx = rulerHeightPx
            )
        }
        SideEffect {
            if (maxVerticalScroll != maxScrollY) maxVerticalScroll = maxScrollY
            val boundedScrollY = scrollY.coerceIn(0f, maxScrollY)
            if (scrollY != boundedScrollY) scrollY = boundedScrollY
        }
        
        val isFollowing by FollowPlayheadController.isFollowing.collectAsState()
        FollowPlayheadScrollEffect(
            playheadFrameFlow = playheadFrameFlow,
            pixelsPerFrame = pixelsPerFrame,
            centerX = centerX,
            currentScrollX = scrollX,
            isPlaying = isPlaying,
            isFollowing = isFollowing,
            isDragging = isDragging,
            transformableState = transformableState,
            frameToX = TimelineUiGeometry::frameToX,
            onScrollTo = { scrollX = it }
        )
        
        Row(modifier = Modifier.fillMaxSize()) {
            // Track Headers
            if (uiState.project.tracks.isNotEmpty()) {
                Column(
                    modifier = Modifier
                        .width(trackHeaderWidth)
                        .fillMaxHeight()
                        .background(TimelineDesignTokens.TrackHeader)
                ) {
                    Box(
                        modifier = Modifier
                            .fillMaxWidth()
                            .height(rulerHeightDp)
                            .background(TimelineDesignTokens.Ruler)
                            .padding(start = 10.dp),
                        contentAlignment = Alignment.CenterStart
                    ) {
                        Text(
                            text = "TRACKS",
                            color = Color(0xFF82828A),
                            fontSize = 9.sp,
                            fontWeight = FontWeight.SemiBold,
                            letterSpacing = 1.sp
                        )
                    }
                    Column(
                        modifier = Modifier
                            .fillMaxWidth()
                            .weight(1f)
                            .offset(y = with(androidx.compose.ui.platform.LocalDensity.current) { (-scrollY).toDp() })
                    ) {
                        uiState.project.tracks.forEachIndexed { index, track ->
                            LayerHeaderView(
                                track = track,
                                trackIndex = index,
                                onRename = { trackToRename = track; renameText = track.name },
                                onDelete = { onDeleteTrack(track.id) },
                                onMoveUp = { onMoveTrack(track.id, index - 1) },
                                onMoveDown = { onMoveTrack(track.id, index + 1) },
                                modifier = Modifier
                                    .height(trackHeightDp + trackPaddingDp)
                                    .padding(bottom = trackPaddingDp)
                            )
                        }
                    }
                }
            }
            
            // Timeline Content
            Box(
                modifier = Modifier
                    .weight(1f)
                    .fillMaxHeight()
                    
                    .pointerInput(pixelsPerFrame, scrollX, scrollY, uiState.selectedClipId) {
                        detectTapGestures(
                            onLongPress = { offset ->
                                val tapX = offset.x + scrollX
                                val tapY = offset.y + scrollY - rulerHeightPx
                                val hitClip = TimelineGestureController.hitTest(tapX, tapY, uiState.project, pixelsPerFrame, trackHeight, trackPadding)
                                if (hitClip != null) {
                                    onClipSelected(hitClip.id)
                                    contextMenuClipId = hitClip.id
                                    contextMenuOffset = IntOffset(offset.x.toInt(), offset.y.toInt())
                                    contextMenuVisible = true
                                }
                            },
                            onTap = { offset ->
                                contextMenuVisible = false
                                contextMenuClipId = null
                                val tapX = offset.x + scrollX
                                val tapY = offset.y + scrollY - rulerHeightPx
                                val hitClip = TimelineGestureController.hitTest(tapX, tapY, uiState.project, pixelsPerFrame, trackHeight, trackPadding)
                                onClipSelected(hitClip?.id)
                                // A tap on a clip must both select it and seek. Previously the
                                // hit-clip branch only selected, which made the red playhead appear
                                // unresponsive over the most frequently tapped part of the track.
                                onPlayheadMoved(TimelineUiGeometry.tapToPlayheadFrame(
                                    localX = offset.x,
                                    scrollX = scrollX,
                                    pixelsPerFrame = pixelsPerFrame,
                                    project = uiState.project
                                ))
                            }
                        )
                    }
                    .pointerInput(pixelsPerFrame, scrollX, scrollY, uiState.selectedClipId) {
                        detectDragGestures(
                            onDragStart = { offset ->
                                isDragging = true
                                contextMenuVisible = false
                                contextMenuClipId = null
                                dragPreviewProject = null
                                dragPreviewCommands = null
                                dragAction = TimelineGestureController.determineDragAction(
                                    startOffset = offset,
                                    scrollX = scrollX,
                                    scrollY = scrollY,
                                    rulerHeightPx = rulerHeightPx,
                                    project = uiState.project,
                                    pixelsPerFrame = pixelsPerFrame,
                                    selectedClipId = uiState.selectedClipId,
                                    playheadFrame = playheadFrameFlow.value,
                                    playheadHitRadiusPx = 24.dp.toPx(),
                                    trackHeightPx = trackHeight,
                                    trackPaddingPx = trackPadding
                                )
                                when (val action = dragAction) {
                                    is TimelineDragAction.MoveClip -> dragGhostStartFrame = action.clip.timelineStart
                                    is TimelineDragAction.TrimStart -> dragGhostStartFrame = action.clip.timelineStart
                                    is TimelineDragAction.TrimEnd -> dragGhostEndFrame = action.clip.timelineEnd
                                    else -> {}
                                }
                            },
                            onDrag = { change, dragAmount ->
                                change.consume()
                                if (!TimelineCoreV2Adapter.isAvailable() &&
                                    (dragAction is TimelineDragAction.MoveClip ||
                                        dragAction is TimelineDragAction.TrimStart ||
                                        dragAction is TimelineDragAction.TrimEnd)
                                ) {
                                    dragAction = TimelineDragAction.None
                                    dragPreviewProject = null
                                    dragPreviewCommands = null
                                    dragGhostStartFrame = null
                                    dragGhostEndFrame = null
                                } else when (val action = dragAction) {
                                    is TimelineDragAction.Pan -> {
                                        scrollX = max(0f, scrollX - dragAmount.x)
                                        scrollY = (scrollY - dragAmount.y).coerceIn(0f, maxVerticalScroll)
                                    }
                                    is TimelineDragAction.ScrubPlayhead -> {
                                        val frame = TimelineUiGeometry.xToFrame(change.position.x + scrollX, pixelsPerFrame)
                                        onPlayheadMoved(TimelineUiGeometry.clampPlayheadFrame(uiState.project, frame))
                                    }
                                    is TimelineDragAction.MoveClip -> {
                                        val currentX = change.position.x + scrollX
                                        val frame = TimelineUiGeometry.xToFrame(currentX, pixelsPerFrame)
                                        val rawStart = max(0L, frame - action.startOffsetFrames)
                                        val snapped = if (uiState.snapSettings.isSnappingEnabled) {
                                            TimelineGestureController.snapFrame(
                                                rawStart, uiState.project, playheadFrameFlow.value, pixelsPerFrame,
                                                action.clip.id, uiState.snapSettings
                                            )
                                        } else rawStart
                                        if (snapped == null) {
                                            dragAction = TimelineDragAction.None
                                            dragPreviewProject = null
                                            dragPreviewCommands = null
                                            dragGhostStartFrame = null
                                        } else {
                                            updateNativePreview(action, snapped)
                                        }
                                    }
                                    is TimelineDragAction.TrimStart -> {
                                        val currentX = change.position.x + scrollX
                                        val frame = TimelineUiGeometry.xToFrame(currentX, pixelsPerFrame)
                                        val boundedFrame = TimelineGestureController.clampTrimTarget(action, max(0L, frame), uiState.project)
                                        val snapped = if (uiState.snapSettings.isSnappingEnabled) {
                                            TimelineGestureController.snapFrame(
                                                boundedFrame, uiState.project, playheadFrameFlow.value, pixelsPerFrame,
                                                action.clip.id, uiState.snapSettings
                                            )?.let { TimelineGestureController.clampTrimTarget(action, it, uiState.project) }
                                        } else boundedFrame
                                        if (snapped == null) {
                                            dragAction = TimelineDragAction.None
                                            dragPreviewProject = null
                                            dragPreviewCommands = null
                                            dragGhostStartFrame = null
                                        } else {
                                            updateNativePreview(action, snapped)
                                        }
                                    }
                                    is TimelineDragAction.TrimEnd -> {
                                        val currentX = change.position.x + scrollX
                                        val frame = TimelineUiGeometry.xToFrame(currentX, pixelsPerFrame)
                                        val boundedFrame = TimelineGestureController.clampTrimTarget(action, max(0L, frame), uiState.project)
                                        val snapped = if (uiState.snapSettings.isSnappingEnabled) {
                                            TimelineGestureController.snapFrame(
                                                boundedFrame, uiState.project, playheadFrameFlow.value, pixelsPerFrame,
                                                action.clip.id, uiState.snapSettings
                                            )?.let { TimelineGestureController.clampTrimTarget(action, it, uiState.project) }
                                        } else boundedFrame
                                        if (snapped == null) {
                                            dragAction = TimelineDragAction.None
                                            dragPreviewProject = null
                                            dragPreviewCommands = null
                                            dragGhostEndFrame = null
                                        } else {
                                            updateNativePreview(action, snapped)
                                        }
                                    }
                                    TimelineDragAction.None -> {}
                                }
                            },
                            onDragEnd = {
                                isDragging = false
                                val commands = dragPreviewCommands
                                if (TimelineCoreV2Adapter.isAvailable() && commands != null) {
                                    onNativeEditCommit(commands, when (dragAction) {
                                        is TimelineDragAction.MoveClip -> "Move Clip"
                                        is TimelineDragAction.TrimStart -> "Trim Start"
                                        is TimelineDragAction.TrimEnd -> "Trim End"
                                        else -> "Timeline Edit"
                                    })
                                }
                                dragAction = TimelineDragAction.None
                                dragPreviewProject = null
                                dragPreviewCommands = null
                                dragGhostStartFrame = null
                                dragGhostEndFrame = null
                            },
                            onDragCancel = {
                                isDragging = false
                                dragAction = TimelineDragAction.None
                                dragPreviewProject = null
                                dragPreviewCommands = null
                                dragGhostStartFrame = null
                                dragGhostEndFrame = null
                            }
                        )
                    }) {
                // Scrollable container for tracks
                Box(modifier = Modifier.fillMaxSize().offset(y = with(androidx.compose.ui.platform.LocalDensity.current) { (-scrollY).toDp() })) {
                    // Track backgrounds
                    Column(modifier = Modifier.fillMaxWidth().padding(top = rulerHeightDp)) {
                        uiState.project.tracks.forEach {
                            Box(modifier = Modifier.fillMaxWidth().height(trackHeightDp).background(TimelineDesignTokens.Track))
                            Spacer(modifier = Modifier.height(trackPaddingDp))
                        }
                    }
                    
                    // Layout Clips
                    Layout(
                        content = {
                            visibleTimelineClips.forEach { visible ->
                                val clip = visible.clip
                                val mediaAsset = uiState.project.mediaAssets.find { it.assetId == clip.mediaId }

                                FilmStripClipView(
                                        clip = clip,
                                        mediaAsset = mediaAsset,
                                        isSelected = uiState.selectedClipId == clip.id,
                                        pixelsPerFrame = pixelsPerFrame,
                                        scrollX = scrollX,
                                        viewportWidth = timelineViewportWidth,
                                        frameRate = uiState.project.settings.getFpsRational(),
                                        modifier = Modifier
                                    )
                            }
                        }
                    ) { measurables, constraints ->
                        val layoutStartedAt = System.nanoTime()
                        val placeables = measurables.map { it.measure(constraints) }
                        
                        layout(constraints.maxWidth, constraints.maxHeight) {
                            visibleTimelineClips.forEachIndexed { index, visible ->
                                if (index < placeables.size) {
                                    val clip = visible.clip
                                    val left = (TimelineUiGeometry.frameToX(clip.timelineStart, pixelsPerFrame) - scrollX).toInt()
                                    val top = (visible.trackIndex * (trackHeight + trackPadding) + rulerHeightPx).toInt()
                                    placeables[index].placeRelative(x = left, y = top)
                                }
                            }
                            TimelineUiMetrics.reportLayoutTime((System.nanoTime() - layoutStartedAt) / 1_000_000L)
                        }
                    }
                }
                
                
                Box(modifier = Modifier.offset { contextMenuOffset }) {
                    TimelineContextMenu(
                        expanded = contextMenuVisible,
                        onDismissRequest = {
                            contextMenuVisible = false
                            contextMenuClipId = null
                        },
                        onSplit = onSplit,
                        onDelete = onDelete,
                        showGroupLock = contextMenuLockTarget != null,
                        groupLockLabel = contextMenuLockTarget?.actionLabel ?: "Lock Clip",
                        groupLockActionEnabled = TimelineCoreV2Adapter.isAvailable(),
                        onToggleGroupLock = {
                            contextMenuLockTarget?.let { target ->
                                onSetGroupLocked(target.clipId, !target.isLocked)
                            }
                        }
                    )
                }
                
                // Fixed top overlay for Ruler
                Box(modifier = Modifier.fillMaxWidth().height(rulerHeightDp)) {
                    TimelineRulerView(scrollX, timelineViewportWidth.toInt(), pixelsPerFrame, uiState.project.settings.getFpsRational())
                }
                
                // The playhead indicator observes only its own flow; clip layouts stay stable.
                TimelinePlayheadIndicator(
                    playheadFrameFlow = playheadFrameFlow,
                    pixelsPerFrame = pixelsPerFrame,
                    scrollX = scrollX,
                    frameToX = TimelineUiGeometry::frameToX,
                    startOffset = 0.dp
                )
            }
        }
    }
    
    trackToRename?.let { track ->
        androidx.compose.material3.AlertDialog(
            onDismissRequest = { trackToRename = null },
            title = { Text("Rename Layer") },
            text = {
                androidx.compose.material3.TextField(
                    value = renameText,
                    onValueChange = { renameText = it },
                    singleLine = true
                )
            },
            confirmButton = {
                androidx.compose.material3.TextButton(onClick = {
                    onRenameTrack(track.id, renameText)
                    trackToRename = null
                }) {
                    Text("Rename")
                }
            },
            dismissButton = {
                androidx.compose.material3.TextButton(onClick = { trackToRename = null }) {
                    Text("Cancel")
                }
            }
        )
    }
}
