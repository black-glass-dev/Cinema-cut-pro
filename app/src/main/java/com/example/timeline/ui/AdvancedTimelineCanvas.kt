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
import com.example.timeline.core.CoreV2EditCommand
import com.example.timeline.core.CoreV2EditOutcome
import com.example.timeline.core.CoreV2RippleScope
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
fun AdvancedTimelineCanvas(
    uiState: TimelineUiState,
    playheadFrameFlow: StateFlow<Long>,
    isPlaying: Boolean,
    activeTool: TimelineTool,
    snapEnabled: Boolean,
    linkedSelection: Boolean,
    onClipSelect: (String?) -> Unit,
    onNativeEditCommit: (List<CoreV2EditCommand>, String) -> Unit,
    onZoomChange: (Float) -> Unit,
    rippleScope: CoreV2RippleScope = CoreV2RippleScope.SAME_TRACK,
    onTrackLock: (String, Boolean) -> Unit,
    onTrackEnable: (String, Boolean) -> Unit,
    onTrackMute: (String, Boolean) -> Unit = { _, _ -> },
    onTrackSolo: (String, Boolean) -> Unit = { _, _ -> },
    onRenameTrack: (String, String) -> Unit,
    onDeleteTrack: (String) -> Unit,
    onMoveTrack: (String, Int) -> Unit,
    onPlayheadMoved: (Long) -> Unit,
    onBladeCut: (String, Long) -> Unit = { _, _ -> },
    onSplit: () -> Unit = {},
    onDelete: () -> Unit = {},
    onSetGroupLocked: (String, Boolean) -> Unit = { _, _ -> },
    onTrimStartToPlayhead: () -> Unit = {},
    onTrimEndToPlayhead: () -> Unit = {},
    modifier: Modifier = Modifier
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
    val trackHeight = 80f
    val trackPadding = 12f
    val contextMenuLockTarget = remember(uiState.project, contextMenuClipId) {
        contextMenuClipId?.let { TimelineGroupLockController.targetFor(uiState.project, it) }
    }

    fun updateNativePreview(action: TimelineDragAction, targetFrame: Long) {
        val clipId = actionClipId(action)
        val linkedIds = if (linkedSelection && clipId != null) {
            LinkedSelectionController().getLinkedClips(clipId, uiState.project, true)
        } else emptySet()
        val commands = TimelineGestureController.previewCommands(
            action = action,
            targetFrame = targetFrame,
            project = uiState.project,
            activeTool = activeTool,
            rippleScope = rippleScope,
            linkedClipIds = linkedIds
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
                    .firstOrNull { it.id == clipId }
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
            .background(Color(0xFF0A0A0A))
            .transformable(transformableState)
    ) {
        val viewportWidth = constraints.maxWidth
        val centerX = viewportWidth / 2f
        val rulerHeightPx = with(androidx.compose.ui.platform.LocalDensity.current) { 40.dp.toPx() }
        val renderProject = dragPreviewProject ?: uiState.project
        val contentHeightPx = rulerHeightPx + renderProject.tracks.size * (trackHeight + trackPadding)
        val maxScrollY = max(0f, contentHeightPx - constraints.maxHeight.toFloat())
        val visibleTimelineClips = remember(
            renderProject.tracks, scrollX, scrollY, pixelsPerFrame, constraints.maxWidth, constraints.maxHeight, rulerHeightPx
        ) {
            TimelineVirtualization.visibleClips(
                project = renderProject,
                scrollX = scrollX,
                scrollY = scrollY,
                viewportWidthPx = viewportWidth.toFloat(),
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
                        .width(80.dp)
                        .fillMaxHeight()
                        .background(Color(0xFF1E1E1E))
                        .padding(top = 40.dp)
                        .offset(y = with(androidx.compose.ui.platform.LocalDensity.current) { (-scrollY).toDp() })
                ) {
                    uiState.project.tracks.forEachIndexed { index, track ->
                        AdvancedTimelineTrackHeader(
                            track = track,
                            trackIndex = index,
                            onRename = { trackToRename = track; renameText = track.name },
                            onDelete = { onDeleteTrack(track.id) },
                            onMoveUp = { onMoveTrack(track.id, index - 1) },
                            onMoveDown = { onMoveTrack(track.id, index + 1) },
                            onToggleLock = { onTrackLock(track.id, track.isLocked) },
                            onToggleVisibility = { onTrackEnable(track.id, track.isVisible) },
                            onToggleMute = { onTrackMute(track.id, track.isMuted) },
                            onToggleSolo = { onTrackSolo(track.id, track.isSolo) },
                            modifier = Modifier
                                .height(with(androidx.compose.ui.platform.LocalDensity.current) { (trackHeight + trackPadding).toDp() })
                                .padding(bottom = with(androidx.compose.ui.platform.LocalDensity.current) { trackPadding.toDp() })
                        )
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
                                val tapY = offset.y + scrollY - 40.dp.toPx()
                                val hitClip = AdvancedTimelineGestureController.hitTest(tapX, tapY, uiState.project, pixelsPerFrame)
                                if (hitClip != null) {
                                    onClipSelect(hitClip.id)
                                    contextMenuClipId = hitClip.id
                                    contextMenuOffset = IntOffset(offset.x.toInt(), offset.y.toInt())
                                    contextMenuVisible = true
                                }
                            },
                            onTap = { offset ->
                                contextMenuVisible = false
                                contextMenuClipId = null
                                val tapX = offset.x + scrollX
                                val tapY = offset.y + scrollY - 40.dp.toPx()
                                val hitClip = AdvancedTimelineGestureController.hitTest(tapX, tapY, uiState.project, pixelsPerFrame)
                                val frame = TimelineUiGeometry.xToFrame(tapX, pixelsPerFrame)
                                if (activeTool == TimelineTool.BLADE) {
                                    if (hitClip != null) {
                                        onBladeCut(hitClip.id, max(0, frame))
                                    }
                                } else {
                                    onClipSelect(hitClip?.id)
                                    if (hitClip == null) {
                                        onPlayheadMoved(max(0, frame))
                                    }
                                }
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
                                dragAction = AdvancedTimelineGestureController.determineDragAction(
                                    startOffset = offset,
                                    scrollX = scrollX,
                                    scrollY = scrollY,
                                    rulerHeightPx = 40.dp.toPx(),
                                    project = uiState.project,
                                    pixelsPerFrame = pixelsPerFrame,
                                    selectedClipIds = uiState.selectedClipIds
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
                                        onPlayheadMoved(max(0, frame))
                                    }
                                    is TimelineDragAction.MoveClip -> {
                                        val currentX = change.position.x + scrollX
                                        val frame = TimelineUiGeometry.xToFrame(currentX, pixelsPerFrame)
                                        val rawStart = max(0L, frame - action.startOffsetFrames)
                                        val nextStart = if (snapEnabled) {
                                            AdvancedTimelineGestureController.snapFrame(
                                                rawStart, uiState.project, playheadFrameFlow.value, pixelsPerFrame,
                                                action.clip.id, uiState.snapSettings
                                            )
                                        } else rawStart
                                        if (nextStart == null) {
                                            dragAction = TimelineDragAction.None
                                            dragPreviewProject = null
                                            dragPreviewCommands = null
                                            dragGhostStartFrame = null
                                        } else {
                                            updateNativePreview(action, nextStart)
                                        }
                                    }
                                    is TimelineDragAction.TrimStart -> {
                                        val currentX = change.position.x + scrollX
                                        val frame = TimelineUiGeometry.xToFrame(currentX, pixelsPerFrame)
                                        val snapped = if (snapEnabled) {
                                            AdvancedTimelineGestureController.snapFrame(
                                                max(0L, frame), uiState.project, playheadFrameFlow.value, pixelsPerFrame,
                                                action.clip.id, uiState.snapSettings
                                            )
                                        } else max(0L, frame)
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
                                        val snapped = if (snapEnabled) {
                                            AdvancedTimelineGestureController.snapFrame(
                                                max(0L, frame), uiState.project, playheadFrameFlow.value, pixelsPerFrame,
                                                action.clip.id, uiState.snapSettings
                                            )
                                        } else max(0L, frame)
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
                                    val label = when (activeTool) {
                                        TimelineTool.RIPPLE -> "Ripple Edit"
                                        TimelineTool.ROLL -> "Roll Edit"
                                        TimelineTool.SLIP -> "Slip Edit"
                                        TimelineTool.SLIDE -> "Slide Edit"
                                        else -> when (dragAction) {
                                            is TimelineDragAction.MoveClip -> "Move Clip"
                                            is TimelineDragAction.TrimStart -> "Trim Start"
                                            is TimelineDragAction.TrimEnd -> "Trim End"
                                            else -> "Timeline Edit"
                                        }
                                    }
                                    onNativeEditCommit(commands, label)
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
                    Column(modifier = Modifier.fillMaxWidth().padding(top = 40.dp)) {
                        uiState.project.tracks.forEach {
                            Box(modifier = Modifier.fillMaxWidth().height(with(androidx.compose.ui.platform.LocalDensity.current) { trackHeight.toDp() }).background(Color(0xFF141414)))
                            Spacer(modifier = Modifier.height(with(androidx.compose.ui.platform.LocalDensity.current) { trackPadding.toDp() }))
                        }
                    }
                    
                    // Layout Clips
                    Layout(
                        content = {
                            visibleTimelineClips.forEach { visible ->
                                val clip = visible.clip
                                val mediaAsset = uiState.project.mediaAssets.find { it.assetId == clip.mediaId }

                                AdvancedTimelineClipView(
                                        clip = clip,
                                        mediaAsset = mediaAsset,
                                        isSelected = uiState.selectedClipIds.contains(clip.id),
                                        pixelsPerFrame = pixelsPerFrame,
                                        scrollX = scrollX,
                                        viewportWidth = viewportWidth.toFloat(),
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
                        onTrimStartToPlayhead = onTrimStartToPlayhead,
                        onTrimEndToPlayhead = onTrimEndToPlayhead,
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
                Box(modifier = Modifier.fillMaxWidth().height(40.dp)) {
                    AdvancedTimelineRuler(scrollX, viewportWidth, pixelsPerFrame, uiState.project.settings.getFpsRational())
                }
                
                TimelinePlayheadIndicator(
                    playheadFrameFlow = playheadFrameFlow,
                    pixelsPerFrame = pixelsPerFrame,
                    scrollX = scrollX,
                    frameToX = TimelineUiGeometry::frameToX
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
