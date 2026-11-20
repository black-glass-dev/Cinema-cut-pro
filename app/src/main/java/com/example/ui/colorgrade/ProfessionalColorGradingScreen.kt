package com.example.ui.colorgrade

import androidx.compose.foundation.background
import androidx.compose.foundation.gestures.detectTapGestures
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.foundation.horizontalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.ContentCopy
import androidx.compose.material.icons.filled.ContentPaste
import androidx.compose.material.icons.filled.MoreVert
import androidx.compose.material.icons.filled.Refresh
import androidx.compose.material.icons.outlined.Place
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.lifecycle.ViewModelProvider
import androidx.lifecycle.viewmodel.compose.viewModel
import com.example.model.colorgrade.ColorGradeStack
import com.example.timeline.ui.TimelineViewModel
import com.example.ui.ColorAdjustmentViewModel
import com.example.ui.ColorMatchViewModel
import com.example.ui.ColorLayerViewModel
import com.example.ui.editor.EditorViewModel

import com.example.timeline.engine.preview.TimelinePreviewController
import com.example.timeline.engine.preview.PreviewEngineType

import androidx.compose.material.icons.automirrored.filled.List
import kotlinx.coroutines.flow.sample

@OptIn(kotlinx.coroutines.FlowPreview::class, kotlinx.coroutines.ExperimentalCoroutinesApi::class)
@Composable
fun ProfessionalColorGradingScreen(
    timelineViewModel: TimelineViewModel,
    colorMatchViewModel: ColorMatchViewModel,
    scopeViewModel: com.example.ui.scopes.ScopeViewModel,
    previewController: TimelinePreviewController,
    editorViewModel: EditorViewModel
) {
    val colorViewModel: ColorAdjustmentViewModel = viewModel(
        factory = object : ViewModelProvider.Factory {
            @Suppress("UNCHECKED_CAST")
            override fun <T : androidx.lifecycle.ViewModel> create(modelClass: Class<T>): T {
                return ColorAdjustmentViewModel(timelineViewModel) as T
            }
        }
    )

    
    val layerViewModel: ColorLayerViewModel = viewModel(
        factory = object : ViewModelProvider.Factory {
            @Suppress("UNCHECKED_CAST")
            override fun <T : androidx.lifecycle.ViewModel> create(modelClass: Class<T>): T {
                return ColorLayerViewModel(timelineViewModel) as T
            }
        }
    )

    val uiState by timelineViewModel.uiState.collectAsState()
    val playheadFrame by timelineViewModel.playheadFrame.collectAsState()
    val isPlaying by timelineViewModel.isPlaying.collectAsState()

    val activeClip = if (uiState.selectedClipId != null) {
        uiState.project.tracks.flatMap { it.clips }.find { it.id == uiState.selectedClipId }
    } else {
        com.example.timeline.engine.GradingFrameResolver.resolve(uiState.project, playheadFrame)?.first
    }

    if (activeClip == null) {
        Box(modifier = Modifier.fillMaxSize().background(Color(0xFF1E1E22)), contentAlignment = Alignment.Center) {
            Text("Select a clip to grade", color = MaterialTheme.colorScheme.onSurfaceVariant)
        }
        return
    }

    LaunchedEffect(activeClip.id) {
        if (uiState.selectedClipId == null) timelineViewModel.selectClip(activeClip.id)
        if (playheadFrame < activeClip.timelineStart || playheadFrame >= activeClip.timelineEnd) {
            timelineViewModel.setPlayheadFrame(activeClip.timelineStart)
            editorViewModel.updatePlayheadFrame(activeClip.timelineStart)
        }
    }

    var showBefore by remember { mutableStateOf(false) }
    var showNodes by remember { mutableStateOf(false) }
    val layerStack = activeClip.colorLayers
    val selectedLayerId by layerViewModel.selectedLayerId.collectAsState()
    val activeLayer = layerStack.layers.find { it.id == selectedLayerId } ?: layerStack.layers.lastOrNull()
    LaunchedEffect(activeClip.id, layerStack.layers) {
        if (layerStack.layers.none { it.id == selectedLayerId }) {
            layerViewModel.selectLayer(layerStack.layers.lastOrNull()?.id)
        }
    }
    // The regular color panel edits the clip grade. Node grades are used only when the node stack
    // is open, preventing edits from disappearing into an unselected default layer.
    val currentGrade = if (showNodes) {
        activeLayer?.grade ?: activeClip.colorGrade
    } else {
        activeClip.colorGrade
    }
    val activeColorLayerId = if (showNodes) activeLayer?.id else null
    val activeAsset = uiState.project.mediaAssets.firstOrNull { it.assetId == activeClip.mediaId }
    val isMotionCamRaw = activeAsset?.let {
        com.example.timeline.media.MotionCamRawFormat.isMcrawAsset(it)
    } == true

    val engineType by previewController.engineTypeFlow.collectAsState()
    // Capture the user's requested engine before the temporary Media3 handoff. Looking at the
    // current engine here causes needsMedia3ForLiveGrade to turn false as soon as that handoff
    // succeeds, disposing the effect and switching back to the engine that cannot show the grade.
    val engineToRestore = remember(previewController) {
        previewController.engineTypeToRestoreAfterTemporaryPreview()
    }
    val needsMedia3ForLiveGrade = remember(engineToRestore, isMotionCamRaw) {
        com.example.timeline.engine.preview.requiresMedia3ForLiveGrade(engineToRestore, isMotionCamRaw)
    }
    val latestIsPlaying by rememberUpdatedState(isPlaying)
    DisposableEffect(previewController, engineToRestore, needsMedia3ForLiveGrade) {
        previewController.setColorGradingPreviewActive(true)
        if (needsMedia3ForLiveGrade) {
            previewController.setEngineType(PreviewEngineType.MEDIA3_FALLBACK)
            if (latestIsPlaying) previewController.play()
        }
        onDispose {
            previewController.setColorGradeBypassed(false)
            if (needsMedia3ForLiveGrade &&
                previewController.engineTypeFlow.value == PreviewEngineType.MEDIA3_FALLBACK &&
                engineToRestore != PreviewEngineType.MEDIA3_FALLBACK
            ) {
                previewController.setEngineType(engineToRestore)
                if (latestIsPlaying) previewController.play()
            }
            previewController.setColorGradingPreviewActive(false)
        }
    }
    LaunchedEffect(engineType) {
        if (needsMedia3ForLiveGrade && engineType != PreviewEngineType.MEDIA3_FALLBACK) {
            previewController.setEngineType(PreviewEngineType.MEDIA3_FALLBACK)
            if (isPlaying) previewController.play()
        }
    }

    var showPresets by remember { mutableStateOf(false) }
    var selectedTab by remember { mutableStateOf(0) }
    val tabs = listOf("Wheels", "Curves", "HSL", "LUT", "Match", "Scopes")

    val params = com.example.model.colorgrade.engine.ColorGradeAdjustmentMapper.applyClipGradeIfNotMirrored(
        base = activeClip.adjustments.evaluateParamsAtFrame(playheadFrame),
        sourceGrade = activeClip.colorGrade,
        frame = playheadFrame
    )
    val isAnalyzingScopes by scopeViewModel.isAnalyzing.collectAsState()

    fun primarySettingValue(parameterId: String): Float {
        if (!showNodes) {
            return when (parameterId) {
                "temperature" -> params.temperature
                "tint" -> params.tint
                "contrast" -> params.contrast
                "saturation" -> params.saturation
                "vibrance" -> params.vibrance
                else -> 0f
            }
        }
        val primary = currentGrade.primaryCorrections
        return when (parameterId) {
            "temperature" -> primary.temperature
            "tint" -> primary.tint
            "contrast" -> primary.contrast
            "saturation" -> primary.saturation
            "vibrance" -> primary.vibrance
            else -> 0f
        }
    }

    fun updatePrimarySetting(parameterId: String, value: Float) {
        if (!showNodes) {
            colorViewModel.updateAdjustmentParamLive(parameterId, value)
            return
        }
        val primary = currentGrade.primaryCorrections
        val updated = when (parameterId) {
            "temperature" -> primary.copy(temperature = value)
            "tint" -> primary.copy(tint = value)
            "contrast" -> primary.copy(contrast = value)
            "saturation" -> primary.copy(saturation = value)
            "vibrance" -> primary.copy(vibrance = value)
            else -> primary
        }
        if (updated != primary) {
            colorViewModel.updateColorGradeLive(
                currentGrade.copy(primaryCorrections = updated),
                activeColorLayerId
            )
        }
    }

    fun resetPrimarySetting(parameterId: String, value: Float) {
        if (!showNodes) {
            colorViewModel.updateAdjustmentParam(parameterId, value)
            return
        }
        colorViewModel.beginAdjustmentDrag()
        updatePrimarySetting(parameterId, value)
        colorViewModel.endAdjustmentDrag("Reset $parameterId")
    }

    LaunchedEffect(selectedTab) {
        if (selectedTab == 5) {
            androidx.compose.runtime.snapshotFlow {
                Triple(uiState.project, playheadFrame, activeClip.id)
            }
                .sample(100L)
                .collect { (project, frameNumber, _) ->
                    val frame = previewController.extractCurrentFrameBitmap() ?: return@collect
                    scopeViewModel.analyzeFrame(
                        frame,
                        com.example.timeline.engine.GradeEvaluationEngine.evaluateFrame(project, frameNumber),
                        com.example.model.adjustments.ColorPipelineSettings()
                    )
                }
        }
    }

    LaunchedEffect(showBefore) {
        previewController.setColorGradeBypassed(showBefore)
    }

    Box(modifier = Modifier.fillMaxSize()) {
        Column(
            modifier = Modifier
                .fillMaxSize()
                .background(Color(0xFF16161A))
        ) {
            ColorGradeToolbar(
                clipName = activeClip.name,
                showNodes = showNodes,
                showBefore = showBefore,
                onToggleNodes = { showNodes = !showNodes },
                onShowPresets = { showPresets = true },
                onToggleBefore = { showBefore = !showBefore },
                onCopy = { colorViewModel.copyAdjustmentsFromSelectedClip() },
                onPaste = { colorViewModel.pasteAdjustmentsToSelectedClip() },
                onReset = { colorViewModel.resetAllAdjustments() }
            )

            if (isMotionCamRaw) {
                MotionCamPreviewLimitationNotice()
            }

            ColorPageTabs(
                selectedTab = selectedTab,
                onTabSelected = { selectedTab = it },
                tabs = tabs,
                modifier = Modifier.horizontalScroll(rememberScrollState())
            )

        
        Row(modifier = Modifier.fillMaxSize().weight(1f)) {
            if (showNodes) {
                ColorLayerStackPanel(
                    clipId = activeClip.id,
                    layerStack = layerStack,
                    viewModel = layerViewModel,
                    modifier = Modifier.weight(0.35f).fillMaxHeight()
                )
            }
Box(
            modifier = Modifier
                .weight(if (showNodes) 0.65f else 1f)
                .fillMaxHeight()
                .verticalScroll(rememberScrollState())
                .padding(16.dp)
        ) {
            if (showNodes && activeLayer == null) {
                Text(
                    "Add a color node before editing node controls.",
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                    style = MaterialTheme.typography.bodySmall
                )
            } else {
                when (selectedTab) {
                0 -> {
                    // Wheels + Basic Light
                    Column(verticalArrangement = Arrangement.spacedBy(24.dp)) {
                        WheelsTabPanel(
                            gradeParams = currentGrade.primaryCorrections,
                            onParamsChange = { newParams ->
                                val newGrade = currentGrade.copy(primaryCorrections = newParams)
                                colorViewModel.updateColorGradeLive(newGrade, activeColorLayerId)
                            },
                            onDragStart = { colorViewModel.beginAdjustmentDrag() },
                            onDragEnd = { colorViewModel.endAdjustmentDrag(it) }
                        )

                        Spacer(modifier = Modifier.height(16.dp))

                        // Additional Primary Settings
                        Column(
                            modifier = Modifier
                                .fillMaxWidth()
                                .background(Color(0xFF1E1F22), shape = MaterialTheme.shapes.medium)
                                .padding(16.dp),
                            verticalArrangement = Arrangement.spacedBy(16.dp)
                        ) {
                            Text("PRIMARY SETTINGS", fontSize = 12.sp, fontWeight = FontWeight.Bold, color = Color.White, letterSpacing = 1.sp)
                            
                            Row(modifier = Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(16.dp)) {
                                Column(modifier = Modifier.weight(1f), verticalArrangement = Arrangement.spacedBy(12.dp)) {
                                    ColorControlSlider(
                                        "Temperature", primarySettingValue("temperature"), -1f..1f,
                                        { updatePrimarySetting("temperature", it) },
                                        { resetPrimarySetting("temperature", 0f) },
                                        onDragStart = { colorViewModel.beginAdjustmentDrag() },
                                        onDragEnd = { colorViewModel.endAdjustmentDrag("Temperature") }
                                    )
                                    ColorControlSlider(
                                        "Tint", primarySettingValue("tint"), -1f..1f,
                                        { updatePrimarySetting("tint", it) },
                                        { resetPrimarySetting("tint", 0f) },
                                        onDragStart = { colorViewModel.beginAdjustmentDrag() },
                                        onDragEnd = { colorViewModel.endAdjustmentDrag("Tint") }
                                    )
                                    ColorControlSlider(
                                        "Contrast", primarySettingValue("contrast"), -1f..1f,
                                        { updatePrimarySetting("contrast", it) },
                                        { resetPrimarySetting("contrast", 0f) },
                                        onDragStart = { colorViewModel.beginAdjustmentDrag() },
                                        onDragEnd = { colorViewModel.endAdjustmentDrag("Contrast") }
                                    )
                                }
                                Column(modifier = Modifier.weight(1f), verticalArrangement = Arrangement.spacedBy(12.dp)) {
                                    ColorControlSlider(
                                        "Saturation", primarySettingValue("saturation"), 0f..2f,
                                        { updatePrimarySetting("saturation", it) },
                                        { resetPrimarySetting("saturation", 1f) },
                                        onDragStart = { colorViewModel.beginAdjustmentDrag() },
                                        onDragEnd = { colorViewModel.endAdjustmentDrag("Saturation") },
                                        valueFormatter = { "${kotlin.math.round(it * 100f).toInt()}%" }
                                    )
                                    ColorControlSlider(
                                        "Vibrance", primarySettingValue("vibrance"), -1f..1f,
                                        { updatePrimarySetting("vibrance", it) },
                                        { resetPrimarySetting("vibrance", 0f) },
                                        onDragStart = { colorViewModel.beginAdjustmentDrag() },
                                        onDragEnd = { colorViewModel.endAdjustmentDrag("Vibrance") }
                                    )
                                    if (!showNodes) {
                                        ColorControlSlider(
                                            "Mid Detail", params.clarity, -1f..1f,
                                            { colorViewModel.updateAdjustmentParamLive("clarity", it) },
                                            { colorViewModel.updateAdjustmentParam("clarity", 0f) },
                                            onDragStart = { colorViewModel.beginAdjustmentDrag() },
                                            onDragEnd = { colorViewModel.endAdjustmentDrag("Clarity") }
                                        )
                                    }
                                }
                            }
                        }
                    }
                }
                1 -> {
                    // Curve LUTs are rendered by the Media3 preview/export shader. Separate multi-node
                    // grades and non-SDR exports are still rejected by the export validator.
                    Column(verticalArrangement = Arrangement.spacedBy(12.dp)) {
                        Text(
                            text = "Curves update the live preview and are supported in SDR H.264/MP4 export. Separate multi-node grades and unsupported export settings are checked before rendering.",
                            color = MaterialTheme.colorScheme.tertiary,
                            style = MaterialTheme.typography.bodySmall
                        )
                        CurvesPanel(
                            curveParams = currentGrade.curves,
                            onParamsChange = { newCurves ->
                                val newGrade = currentGrade.copy(curves = newCurves)
                                colorViewModel.updateColorGradeLive(newGrade, activeColorLayerId)
                            },
                            onDragStart = { colorViewModel.beginAdjustmentDrag() },
                            onDragEnd = { colorViewModel.endAdjustmentDrag("Curves") }
                        )
                    }
                }
                2 -> {
                    // HSL
                    Column(verticalArrangement = Arrangement.spacedBy(24.dp)) {
                        val isEyedropperActive by colorViewModel.isEyedropperActive.collectAsState()
                        HslPanel(
                            params = currentGrade.hslAdjustments,
                            onParamsChange = { newHsl ->
                                val newGrade = currentGrade.copy(hslAdjustments = newHsl)
                                colorViewModel.updateColorGradeLive(newGrade, activeColorLayerId)
                            },
                            onDragStart = { colorViewModel.beginAdjustmentDrag() },
                            onDragEnd = { colorViewModel.endAdjustmentDrag("HSL") },
                            isEyedropperActive = isEyedropperActive,
                            onToggleEyedropper = { colorViewModel.toggleEyedropper() }
                        )
                        
                        HorizontalDivider(color = Color.DarkGray)
                        
                        SelectiveColorPanel(
                            params = currentGrade.selectiveColor,
                            onParamsChange = { newSelective ->
                                val newGrade = currentGrade.copy(selectiveColor = newSelective)
                                colorViewModel.updateColorGradeLive(newGrade, activeColorLayerId)
                            },
                            onDragStart = { colorViewModel.beginAdjustmentDrag() },
                            onDragEnd = { colorViewModel.endAdjustmentDrag("Selective Color") }
                        )
                        
                        HorizontalDivider(color = Color.DarkGray)
                        
                        if (showNodes) {
                            Text(
                                "Skin-tone masking controls currently edit clip-level adjustments only.",
                                color = MaterialTheme.colorScheme.onSurfaceVariant,
                                style = MaterialTheme.typography.bodySmall
                            )
                        } else {
                            SkinToneProtectionPanel(
                                params = params,
                                viewModel = colorViewModel
                            )
                        }
                    }
                }
                3 -> {
                    // LUT
                    LutPanel(
                        selectedLutId = if (showNodes) {
                            currentGrade.lutStack.lutId.takeIf { currentGrade.lutStack.enabled }
                        } else {
                            params.lutId
                        },
                        intensity = if (showNodes) currentGrade.lutStack.intensity else params.lutIntensity,
                        onLutSelected = { id ->
                            if (showNodes && activeColorLayerId != null) {
                                colorViewModel.beginAdjustmentDrag()
                                colorViewModel.updateColorGradeLive(
                                    currentGrade.copy(
                                        lutStack = currentGrade.lutStack.copy(
                                            lutId = id,
                                            enabled = id != null
                                        )
                                    ),
                                    activeColorLayerId
                                )
                                colorViewModel.endAdjustmentDrag("Set Node LUT")
                            } else if (!showNodes) {
                                colorViewModel.setLutId(id)
                            }
                        },
                        onIntensityChanged = { value ->
                            if (showNodes && activeColorLayerId != null) {
                                colorViewModel.beginAdjustmentDrag()
                                colorViewModel.updateColorGradeLive(
                                    currentGrade.copy(
                                        lutStack = currentGrade.lutStack.copy(intensity = value)
                                    ),
                                    activeColorLayerId
                                )
                            } else if (!showNodes) {
                                colorViewModel.beginAdjustmentDrag()
                                colorViewModel.updateAdjustmentParamLive("lutIntensity", value)
                            }
                        },
                        onIntensityChangeFinished = {
                            colorViewModel.endAdjustmentDrag(if (showNodes) "Node LUT Intensity" else "LUT Intensity")
                        }
                    )
                }
                4 -> {
                    // Match
                    MatchPanel(
                        viewModel = colorMatchViewModel,
                        timelineViewModel = timelineViewModel
                    )
                }
                5 -> {
                    val scopeData by scopeViewModel.scopeData.collectAsState()
                    ScopesPanel(
                        scopeData = scopeData,
                        isAnalyzing = isAnalyzingScopes,
                        onClose = { selectedTab = 0 },
                        modifier = Modifier.fillMaxSize()
                    )
                }
                }
            }
        }
        } // end Column

        }
        if (showPresets) {
            Box(
                modifier = Modifier
                    .fillMaxSize()
                    .background(Color.Black.copy(alpha=0.6f)),
                contentAlignment = Alignment.Center
            ) {
                GradePresetPanel(
                    viewModel = colorViewModel,
                    onClose = { showPresets = false }
                )
            }
        }
    } // end outer Box
}


@Composable
private fun ColorGradeToolbar(
    clipName: String,
    showNodes: Boolean,
    showBefore: Boolean,
    onToggleNodes: () -> Unit,
    onShowPresets: () -> Unit,
    onToggleBefore: () -> Unit,
    onCopy: () -> Unit,
    onPaste: () -> Unit,
    onReset: () -> Unit
) {
    BoxWithConstraints(
        modifier = Modifier
            .fillMaxWidth()
            .background(Color(0xFF1E1E22))
            .padding(horizontal = 16.dp, vertical = 8.dp)
    ) {
        val compact = maxWidth < 600.dp
        if (compact) {
            Column(verticalArrangement = Arrangement.spacedBy(4.dp)) {
                ColorGradeToolbarTitle(clipName, Modifier.fillMaxWidth())
                Row(
                    modifier = Modifier.fillMaxWidth().horizontalScroll(rememberScrollState()),
                    horizontalArrangement = Arrangement.spacedBy(8.dp),
                    verticalAlignment = Alignment.CenterVertically
                ) {
                    ColorGradeToolbarActions(
                        showNodes, showBefore, onToggleNodes, onShowPresets,
                        onToggleBefore, onCopy, onPaste, onReset, compact = true
                    )
                }
            }
        } else {
            Row(
                modifier = Modifier.fillMaxWidth(),
                verticalAlignment = Alignment.CenterVertically,
                horizontalArrangement = Arrangement.SpaceBetween
            ) {
                ColorGradeToolbarTitle(clipName, Modifier.weight(1f))
                Row(horizontalArrangement = Arrangement.spacedBy(8.dp), verticalAlignment = Alignment.CenterVertically) {
                    ColorGradeToolbarActions(
                        showNodes, showBefore, onToggleNodes, onShowPresets,
                        onToggleBefore, onCopy, onPaste, onReset, compact = false
                    )
                }
            }
        }
    }
}

@Composable
private fun ColorGradeToolbarTitle(clipName: String, modifier: Modifier = Modifier) {
    Column(modifier = modifier) {
        Text(
            "COLOR GRADE",
            fontSize = 11.sp,
            fontWeight = FontWeight.Bold,
            color = MaterialTheme.colorScheme.primary,
            letterSpacing = 1.sp,
            maxLines = 1
        )
        Text(clipName, fontSize = 14.sp, color = Color.White, maxLines = 1)
    }
}

@Composable
private fun RowScope.ColorGradeToolbarActions(
    showNodes: Boolean,
    showBefore: Boolean,
    onToggleNodes: () -> Unit,
    onShowPresets: () -> Unit,
    onToggleBefore: () -> Unit,
    onCopy: () -> Unit,
    onPaste: () -> Unit,
    onReset: () -> Unit,
    compact: Boolean
) {
    if (compact) {
        var showMoreActions by remember { mutableStateOf(false) }

        TextButton(
            onClick = onToggleNodes,
            modifier = Modifier.heightIn(min = 40.dp),
            contentPadding = PaddingValues(horizontal = 6.dp, vertical = 0.dp),
            colors = ButtonDefaults.textButtonColors(
                contentColor = if (showNodes) MaterialTheme.colorScheme.primary else MaterialTheme.colorScheme.onSurfaceVariant
            )
        ) {
            Text("Nodes", fontSize = 12.sp)
        }
        TextButton(
            onClick = onShowPresets,
            modifier = Modifier.heightIn(min = 40.dp),
            contentPadding = PaddingValues(horizontal = 6.dp, vertical = 0.dp)
        ) {
            Text("Presets", fontSize = 12.sp)
        }
        TextButton(
            onClick = onToggleBefore,
            modifier = Modifier.heightIn(min = 40.dp),
            contentPadding = PaddingValues(horizontal = 6.dp, vertical = 0.dp)
        ) {
            Text(if (showBefore) "Show After" else "Show Before", fontSize = 12.sp, maxLines = 1)
        }
        Box {
            IconButton(onClick = { showMoreActions = true }) {
                Icon(Icons.Default.MoreVert, contentDescription = "More color grading actions")
            }
            DropdownMenu(
                expanded = showMoreActions,
                onDismissRequest = { showMoreActions = false }
            ) {
                DropdownMenuItem(
                    text = { Text("Copy grade") },
                    leadingIcon = { Icon(Icons.Default.ContentCopy, contentDescription = null) },
                    onClick = { showMoreActions = false; onCopy() }
                )
                DropdownMenuItem(
                    text = { Text("Paste grade") },
                    leadingIcon = { Icon(Icons.Default.ContentPaste, contentDescription = null) },
                    onClick = { showMoreActions = false; onPaste() }
                )
                DropdownMenuItem(
                    text = { Text("Reset all adjustments") },
                    leadingIcon = { Icon(Icons.Default.Refresh, contentDescription = null, tint = MaterialTheme.colorScheme.error) },
                    onClick = { showMoreActions = false; onReset() }
                )
            }
        }
    } else {
        Button(
            onClick = onToggleNodes,
            colors = ButtonDefaults.buttonColors(
                containerColor = if (showNodes) MaterialTheme.colorScheme.primary else MaterialTheme.colorScheme.surfaceVariant
            ),
            contentPadding = PaddingValues(horizontal = 12.dp, vertical = 4.dp),
            shape = androidx.compose.foundation.shape.RoundedCornerShape(6.dp)
        ) {
            Text("Nodes", fontSize = 12.sp, color = if (showNodes) MaterialTheme.colorScheme.onPrimary else Color.LightGray)
        }
        Button(
            onClick = onShowPresets,
            colors = ButtonDefaults.buttonColors(containerColor = MaterialTheme.colorScheme.surfaceVariant),
            contentPadding = PaddingValues(horizontal = 12.dp, vertical = 4.dp),
            shape = androidx.compose.foundation.shape.RoundedCornerShape(6.dp)
        ) {
            Icon(imageVector = Icons.AutoMirrored.Filled.List, contentDescription = null, modifier = Modifier.size(16.dp))
            Spacer(modifier = Modifier.width(4.dp))
            Text("Presets", fontSize = 12.sp)
        }
        TextButton(
            onClick = onToggleBefore,
            shape = androidx.compose.foundation.shape.RoundedCornerShape(6.dp)
        ) {
            Text(if (showBefore) "Show After" else "Show Before", fontSize = 12.sp, maxLines = 1)
        }
        IconButton(onClick = onCopy, modifier = Modifier.size(32.dp)) {
            Icon(Icons.Default.ContentCopy, contentDescription = "Copy grade")
        }
        IconButton(onClick = onPaste, modifier = Modifier.size(32.dp)) {
            Icon(Icons.Default.ContentPaste, contentDescription = "Paste grade")
        }
        IconButton(onClick = onReset, modifier = Modifier.size(32.dp)) {
            Icon(Icons.Default.Refresh, contentDescription = "Reset all adjustments", tint = MaterialTheme.colorScheme.error)
        }
    }
}
