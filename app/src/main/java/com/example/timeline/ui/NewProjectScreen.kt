package com.example.timeline.ui

import androidx.compose.foundation.Canvas
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ColumnScope
import androidx.compose.foundation.layout.ExperimentalLayoutApi
import androidx.compose.foundation.layout.FlowRow
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.imePadding
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Add
import androidx.compose.material.icons.filled.ArrowBack
import androidx.compose.material.icons.filled.Check
import androidx.compose.material.icons.filled.Edit
import androidx.compose.material.icons.filled.Settings
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Button
import androidx.compose.material3.ButtonDefaults
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.OutlinedTextFieldDefaults
import androidx.compose.material3.Surface
import androidx.compose.material3.Switch
import androidx.compose.material3.SwitchDefaults
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.lifecycle.viewmodel.compose.viewModel
import com.example.timeline.core.ProjectAspectRatioPreset
import com.example.timeline.core.ProjectAudioSampleRate
import com.example.timeline.core.ProjectColorSpace
import com.example.timeline.core.ProjectFrameRatePreset
import com.example.timeline.core.ProjectSettings
import kotlin.math.max

private val NewProjectPage = Color(0xFFF2F2F4)
private val NewProjectCard = Color.White
private val NewProjectLine = Color(0xFFE4E4E8)
private val NewProjectRed = Color(0xFFFF3B30)
private val NewProjectInk = Color(0xFF1A1A1E)
private val NewProjectMuted = Color(0xFF92929A)
private val NewProjectSelected = Color(0xFFFFF6F5)

@OptIn(ExperimentalLayoutApi::class)
@Composable
fun NewProjectScreen(
    onCreateProject: (ProjectSettings) -> Unit,
    onCancel: () -> Unit,
    modifier: Modifier = Modifier
) {
    val viewModel: ProjectCreationViewModel = viewModel()
    val state by viewModel.uiState.collectAsState()
    var showSettings by remember { mutableStateOf(false) }

    Surface(modifier = modifier.fillMaxSize(), color = NewProjectPage) {
        Column(modifier = Modifier.fillMaxSize().imePadding()) {
            Row(
                modifier = Modifier.fillMaxWidth().padding(horizontal = 16.dp, vertical = 8.dp).height(42.dp),
                verticalAlignment = Alignment.CenterVertically,
                horizontalArrangement = Arrangement.SpaceBetween
            ) {
                IconButton(
                    onClick = onCancel,
                    modifier = Modifier.size(38.dp).clip(RoundedCornerShape(9.dp)).background(Color(0xFFE6E6EA))
                ) { Icon(Icons.Default.ArrowBack, contentDescription = "Back", tint = NewProjectInk) }
                Text("New Project", color = NewProjectInk, style = MaterialTheme.typography.titleMedium, fontWeight = FontWeight.SemiBold)
                IconButton(
                    onClick = { showSettings = true },
                    modifier = Modifier.size(38.dp).clip(RoundedCornerShape(9.dp)).background(Color(0xFFE6E6EA))
                ) { Icon(Icons.Default.Settings, contentDescription = "Project settings", tint = NewProjectInk) }
            }

            Column(
                modifier = Modifier
                    .weight(1f)
                    .fillMaxWidth()
                    .verticalScroll(rememberScrollState())
                    .padding(horizontal = 22.dp)
                    .padding(bottom = 18.dp)
            ) {
                Text("Project Name", style = MaterialTheme.typography.labelLarge, color = NewProjectInk, fontWeight = FontWeight.SemiBold)
                Spacer(Modifier.height(5.dp))
                OutlinedTextField(
                    value = state.projectName,
                    onValueChange = viewModel::setProjectName,
                    singleLine = true,
                    leadingIcon = { Icon(Icons.Default.Edit, contentDescription = null, tint = NewProjectMuted, modifier = Modifier.size(18.dp)) },
                    modifier = Modifier.fillMaxWidth().height(52.dp),
                    shape = RoundedCornerShape(11.dp),
                    textStyle = MaterialTheme.typography.bodyMedium.copy(color = NewProjectInk, fontWeight = FontWeight.SemiBold),
                    colors = projectFieldColors()
                )
                Spacer(Modifier.height(11.dp))

                SectionHeader("Aspect Ratio", "Canvas shape")
                val ratios = listOf(
                    ProjectAspectRatioPreset.W_16_9 to ("16:9" to "Widescreen"),
                    ProjectAspectRatioPreset.W_9_16 to ("9:16" to "Vertical"),
                    ProjectAspectRatioPreset.W_1_1 to ("1:1" to "Square"),
                    ProjectAspectRatioPreset.W_4_5 to ("4:5" to "Social Portrait"),
                    ProjectAspectRatioPreset.W_5_4 to ("5:4" to "Medium format"),
                    ProjectAspectRatioPreset.W_3_2 to ("3:2" to "35mm film"),
                    ProjectAspectRatioPreset.W_4_3 to ("4:3" to "Standard TV"),
                    ProjectAspectRatioPreset.W_21_9 to ("21:9" to "Ultra Wide"),
                    ProjectAspectRatioPreset.W_2_35_1 to ("2.35:1" to "Cinematic"),
                    ProjectAspectRatioPreset.W_2_39_1 to ("2.39:1" to "Cinema Scope")
                )
                PresetGrid(
                    items = ratios,
                    selected = { state.aspectRatio == it.first },
                    title = { it.second.first },
                    subtitle = { it.second.second },
                    onSelect = { viewModel.setAspectRatio(it.first) }
                )
                CustomOptionRow(
                    title = "Custom",
                    subtitle = "Set your own aspect ratio",
                    selected = state.aspectRatio == ProjectAspectRatioPreset.CUSTOM,
                    onClick = { viewModel.setAspectRatio(ProjectAspectRatioPreset.CUSTOM) }
                )
                if (state.aspectRatio == ProjectAspectRatioPreset.CUSTOM) {
                    CustomPanel(title = "CUSTOM RATIO") {
                        Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                            NumberField("Width", state.customAspectWidthText, viewModel::setCustomAspectWidth, Modifier.weight(1f))
                            Text(":", color = NewProjectMuted, fontWeight = FontWeight.Bold)
                            NumberField("Height", state.customAspectHeightText, viewModel::setCustomAspectHeight, Modifier.weight(1f))
                        }
                        Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.SpaceBetween) {
                            Text("Lock ratio", style = MaterialTheme.typography.labelMedium, color = NewProjectInk)
                            Switch(
                                checked = state.lockAspectRatio,
                                onCheckedChange = viewModel::setAspectRatioLocked,
                                colors = SwitchDefaults.colors(checkedTrackColor = NewProjectRed, checkedThumbColor = Color.White)
                            )
                        }
                        ShapeSummary(state.aspectWidth, state.aspectHeight, "${state.aspectWidth}:${state.aspectHeight}  •  ${"%.2f".format(state.aspectWidth.toDouble() / max(1, state.aspectHeight))}")
                        Text("Enter width & height — the ratio updates automatically. Lock it to calculate one side from the other.", style = MaterialTheme.typography.labelSmall, color = NewProjectMuted, textAlign = TextAlign.Center)
                    }
                }
                Spacer(Modifier.height(12.dp))

                SectionHeader("Resolution", "Pixel size")
                PresetGrid(
                    items = state.resolutionOptions,
                    selected = { !state.isCustomResolution && state.resolutionWidth == it.width && state.resolutionHeight == it.height },
                    title = { it.display },
                    subtitle = { it.description },
                    onSelect = { viewModel.setResolution(it.width, it.height) }
                )
                CustomOptionRow(
                    title = "Custom",
                    subtitle = "Set your own resolution",
                    selected = state.isCustomResolution,
                    onClick = viewModel::selectCustomResolution
                )
                if (state.isCustomResolution) {
                    CustomPanel(title = "CUSTOM RESOLUTION") {
                        Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(7.dp)) {
                            NumberField("Width", state.customResolutionWidthText, viewModel::setCustomResolutionWidth, Modifier.weight(1f))
                            Text("×", color = NewProjectMuted, fontWeight = FontWeight.Bold)
                            NumberField("Height", state.customResolutionHeightText, viewModel::setCustomResolutionHeight, Modifier.weight(1f))
                        }
                        Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.SpaceBetween) {
                            Text("Orientation", style = MaterialTheme.typography.labelMedium, color = NewProjectInk)
                            Row(horizontalArrangement = Arrangement.spacedBy(5.dp)) {
                                OrientationChoice("Landscape", state.resolutionWidth >= state.resolutionHeight) { ensureOrientation(true, state.resolutionWidth, state.resolutionHeight, viewModel) }
                                OrientationChoice("Portrait", state.resolutionHeight > state.resolutionWidth) { ensureOrientation(false, state.resolutionWidth, state.resolutionHeight, viewModel) }
                            }
                        }
                        val megaPixels = state.resolutionWidth.toDouble() * state.resolutionHeight / 1_000_000.0
                        ShapeSummary(state.resolutionWidth, state.resolutionHeight, "${state.resolutionWidth} × ${state.resolutionHeight}  •  ${"%.2f".format(megaPixels)} MP")
                        Text("Enter width and height in pixels. Resolution must be even and no larger than 16,384 px.", style = MaterialTheme.typography.labelSmall, color = NewProjectMuted, textAlign = TextAlign.Center)
                    }
                }
                Spacer(Modifier.height(12.dp))

                SectionHeader("Frame Rate", "Frames per second")
                val frameRates = listOf(
                    ProjectFrameRatePreset.FPS_23_976 to ("23.976 fps" to "Film"),
                    ProjectFrameRatePreset.FPS_24 to ("24 fps" to "Cinematic"),
                    ProjectFrameRatePreset.FPS_25 to ("25 fps" to "PAL"),
                    ProjectFrameRatePreset.FPS_29_97 to ("29.97 fps" to "NTSC"),
                    ProjectFrameRatePreset.FPS_30 to ("30 fps" to "Standard"),
                    ProjectFrameRatePreset.FPS_50 to ("50 fps" to "Smooth"),
                    ProjectFrameRatePreset.FPS_59_94 to ("59.94 fps" to "NTSC HD"),
                    ProjectFrameRatePreset.FPS_60 to ("60 fps" to "Action"),
                    ProjectFrameRatePreset.FPS_120 to ("120 fps" to "Slow-motion")
                )
                PresetGrid(
                    items = frameRates,
                    selected = { !state.isCustomFrameRate && state.frameRatePreset == it.first },
                    title = { it.second.first },
                    subtitle = { it.second.second },
                    onSelect = { viewModel.setFrameRate(it.first) }
                )
                CustomOptionRow(
                    title = "Custom",
                    subtitle = "Set your own frame rate",
                    selected = state.isCustomFrameRate,
                    onClick = viewModel::selectCustomFrameRate
                )
                if (state.isCustomFrameRate) {
                    CustomPanel(title = "CUSTOM FRAME RATE") {
                        Row(verticalAlignment = Alignment.CenterVertically) {
                            Text("Frames per second", style = MaterialTheme.typography.labelMedium, color = NewProjectMuted)
                            Spacer(Modifier.width(10.dp))
                            NumberField("fps", state.customFrameRateText, viewModel::setCustomFrameRate, Modifier.width(132.dp), decimal = true)
                        }
                        FlowRow(horizontalArrangement = Arrangement.spacedBy(6.dp), verticalArrangement = Arrangement.spacedBy(5.dp)) {
                            listOf("24", "30", "50", "60", "120").forEach { preset ->
                                val selected = state.customFrameRateText.toDoubleOrNull() == preset.toDoubleOrNull()
                                Surface(
                                    modifier = Modifier.clip(CircleShape).clickable { viewModel.setCustomFrameRate(preset) },
                                    color = if (selected) NewProjectSelected else Color.White,
                                    shape = CircleShape,
                                    border = androidx.compose.foundation.BorderStroke(1.dp, if (selected) NewProjectRed else NewProjectLine)
                                ) { Text("$preset fps", modifier = Modifier.padding(horizontal = 10.dp, vertical = 5.dp), color = if (selected) NewProjectRed else NewProjectInk, style = MaterialTheme.typography.labelSmall) }
                            }
                        }
                        Text("Enter a frame rate from 1 to 240 fps, or tap a quick preset.", style = MaterialTheme.typography.labelSmall, color = NewProjectMuted, textAlign = TextAlign.Center)
                    }
                }
                Spacer(Modifier.height(12.dp))

                SectionHeader("Color Space", "Color profile")
                val colorSpaces = listOf(
                    ProjectColorSpace.REC_709 to ("Rec.709 SDR" to "Standard HD"),
                    ProjectColorSpace.REC_2020 to ("Rec.2020" to "Ultra HD"),
                    ProjectColorSpace.HDR_HLG to ("HDR HLG" to "Hybrid log"),
                    ProjectColorSpace.HDR_PQ to ("HDR PQ" to "Perceptual quantizer"),
                    ProjectColorSpace.LOG_MANAGED to ("Log" to "Log managed workflow"),
                    ProjectColorSpace.AUTOMATIC to ("Automatic" to "Detect from source")
                )
                PresetGrid(
                    items = colorSpaces,
                    selected = { state.colorSpace == it.first },
                    title = { it.second.first },
                    subtitle = { it.second.second },
                    onSelect = { viewModel.setColorSpace(it.first) }
                )
                Spacer(Modifier.height(12.dp))

                SectionHeader("Audio Sample Rate", "Hz")
                val sampleRates = listOf(
                    ProjectAudioSampleRate.HZ_44100 to ("44.1 kHz" to "Audio standard"),
                    ProjectAudioSampleRate.HZ_48000 to ("48 kHz" to "Video standard"),
                    ProjectAudioSampleRate.HZ_96000 to ("96 kHz" to "High fidelity")
                )
                PresetGrid(
                    items = sampleRates,
                    selected = { state.audioSampleRate == it.first },
                    title = { it.second.first },
                    subtitle = { it.second.second },
                    onSelect = { viewModel.setAudioSampleRate(it.first) }
                )
                Spacer(Modifier.height(14.dp))
            }

            Row(
                modifier = Modifier.fillMaxWidth().padding(horizontal = 22.dp, vertical = 12.dp),
                horizontalArrangement = Arrangement.spacedBy(10.dp)
            ) {
                Button(
                    onClick = onCancel,
                    modifier = Modifier.weight(0.22f).height(46.dp),
                    shape = RoundedCornerShape(10.dp),
                    colors = ButtonDefaults.buttonColors(containerColor = Color.White, contentColor = NewProjectInk),
                    contentPadding = PaddingValues(0.dp)
                ) { Text("Cancel", fontWeight = FontWeight.SemiBold) }
                Button(
                    onClick = {
                        val fps = if (state.isCustomFrameRate) viewModel.customFrameRateRational() else state.frameRatePreset.fps
                        onCreateProject(
                            ProjectSettings(
                                projectId = java.util.UUID.randomUUID().toString(),
                                projectName = state.projectName.trim(),
                                aspectRatioPreset = state.aspectRatio,
                                customAspectRatioWidth = state.aspectWidth,
                                customAspectRatioHeight = state.aspectHeight,
                                resolutionWidth = state.resolutionWidth,
                                resolutionHeight = state.resolutionHeight,
                                frameRate = if (state.isCustomFrameRate) ProjectFrameRatePreset.CUSTOM else state.frameRatePreset,
                                customFrameRateNumerator = fps.numerator,
                                customFrameRateDenominator = fps.denominator,
                                colorSpace = state.colorSpace,
                                workingColorSpace = state.colorSpace,
                                audioSampleRate = state.audioSampleRate
                            )
                        )
                    },
                    enabled = state.isValid,
                    modifier = Modifier.weight(0.78f).height(46.dp),
                    shape = RoundedCornerShape(10.dp),
                    colors = ButtonDefaults.buttonColors(containerColor = NewProjectRed, contentColor = Color.White, disabledContainerColor = Color(0xFFFFB8B4)),
                    contentPadding = PaddingValues(horizontal = 12.dp)
                ) { Text("Create Project", fontWeight = FontWeight.Bold) }
            }
        }
    }

    if (showSettings) {
        AlertDialog(
            onDismissRequest = { showSettings = false },
            title = { Text("New project defaults") },
            text = { Text("Choose the canvas shape, pixel size, frame rate and color profile before creating your project.") },
            confirmButton = { TextButton(onClick = { showSettings = false }) { Text("Done") } }
        )
    }
}

private fun ensureOrientation(landscape: Boolean, width: Int, height: Int, viewModel: ProjectCreationViewModel) {
    if (landscape && width < height || !landscape && height < width) viewModel.toggleResolutionOrientation()
}

@Composable
private fun SectionHeader(title: String, hint: String) {
    Row(modifier = Modifier.fillMaxWidth().padding(bottom = 6.dp), verticalAlignment = Alignment.CenterVertically) {
        Text(title, style = MaterialTheme.typography.titleSmall, color = NewProjectInk, fontWeight = FontWeight.Bold)
        Spacer(Modifier.weight(1f))
        Text(hint, style = MaterialTheme.typography.labelSmall, color = NewProjectMuted)
    }
}

@Composable
private fun <T> PresetGrid(
    items: List<T>,
    selected: (T) -> Boolean,
    title: (T) -> String,
    subtitle: (T) -> String,
    onSelect: (T) -> Unit
) {
    items.chunked(2).forEach { row ->
        Row(modifier = Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(8.dp)) {
            row.forEach { item ->
                PresetCard(
                    title = title(item), subtitle = subtitle(item), selected = selected(item),
                    onClick = { onSelect(item) }, modifier = Modifier.weight(1f)
                )
            }
            if (row.size == 1) Spacer(Modifier.weight(1f))
        }
        Spacer(Modifier.height(7.dp))
    }
}

@Composable
private fun PresetCard(title: String, subtitle: String, selected: Boolean, onClick: () -> Unit, modifier: Modifier = Modifier) {
    Surface(
        modifier = modifier.height(49.dp).clip(RoundedCornerShape(10.dp)).clickable(onClick = onClick),
        shape = RoundedCornerShape(10.dp),
        color = if (selected) NewProjectSelected else NewProjectCard,
        border = androidx.compose.foundation.BorderStroke(1.dp, if (selected) NewProjectRed.copy(alpha = 0.7f) else NewProjectLine)
    ) {
        Box(modifier = Modifier.fillMaxSize().padding(start = 11.dp, end = 10.dp, top = 6.dp, bottom = 5.dp)) {
            Column(modifier = Modifier.align(Alignment.CenterStart)) {
                Text(title, color = NewProjectInk, style = MaterialTheme.typography.labelLarge, fontWeight = FontWeight.SemiBold, maxLines = 1, overflow = TextOverflow.Ellipsis)
                Text(subtitle, color = NewProjectMuted, style = MaterialTheme.typography.labelSmall, maxLines = 1, overflow = TextOverflow.Ellipsis)
            }
            if (selected) Box(Modifier.align(Alignment.TopEnd).padding(top = 1.dp).size(10.dp).background(NewProjectRed, CircleShape))
        }
    }
}

@Composable
private fun CustomOptionRow(title: String, subtitle: String, selected: Boolean, onClick: () -> Unit) {
    Surface(
        modifier = Modifier.fillMaxWidth().height(51.dp).clip(RoundedCornerShape(10.dp)).clickable(onClick = onClick),
        shape = RoundedCornerShape(10.dp),
        color = if (selected) NewProjectSelected else NewProjectCard,
        border = androidx.compose.foundation.BorderStroke(1.dp, if (selected) NewProjectRed.copy(alpha = 0.7f) else NewProjectLine)
    ) {
        Row(modifier = Modifier.fillMaxSize().padding(horizontal = 11.dp), verticalAlignment = Alignment.CenterVertically) {
            Box(Modifier.size(30.dp).clip(RoundedCornerShape(8.dp)).background(if (selected) NewProjectRed else Color(0xFFE6E6EA)), contentAlignment = Alignment.Center) {
                Icon(if (selected) Icons.Default.Check else Icons.Default.Add, contentDescription = null, tint = if (selected) Color.White else NewProjectInk, modifier = Modifier.size(18.dp))
            }
            Column(Modifier.weight(1f).padding(start = 9.dp)) {
                Text(title, color = NewProjectInk, style = MaterialTheme.typography.labelLarge, fontWeight = FontWeight.SemiBold)
                Text(subtitle, color = NewProjectMuted, style = MaterialTheme.typography.labelSmall)
            }
            if (selected) Icon(Icons.Default.Check, contentDescription = "Selected", tint = NewProjectRed, modifier = Modifier.size(17.dp))
        }
    }
    Spacer(Modifier.height(7.dp))
}

@Composable
private fun CustomPanel(title: String, content: @Composable ColumnScope.() -> Unit) {
    Column(
        modifier = Modifier.fillMaxWidth().padding(bottom = 7.dp).clip(RoundedCornerShape(10.dp))
            .background(Color.White).border(1.dp, NewProjectRed.copy(alpha = 0.7f), RoundedCornerShape(10.dp))
            .padding(horizontal = 12.dp, vertical = 10.dp),
        verticalArrangement = Arrangement.spacedBy(8.dp)
    ) {
        Text(title, color = Color(0xFF5D5D64), style = MaterialTheme.typography.labelSmall, fontWeight = FontWeight.Bold)
        content()
    }
}

@Composable
private fun NumberField(
    label: String,
    value: String,
    onValueChange: (String) -> Unit,
    modifier: Modifier = Modifier,
    decimal: Boolean = false
) {
    OutlinedTextField(
        value = value,
        onValueChange = onValueChange,
        modifier = modifier.height(44.dp),
        singleLine = true,
        placeholder = { Text(label, color = NewProjectMuted, style = MaterialTheme.typography.labelSmall) },
        textStyle = MaterialTheme.typography.bodyMedium.copy(color = NewProjectInk, fontWeight = FontWeight.SemiBold, textAlign = TextAlign.Center),
        keyboardOptions = KeyboardOptions(keyboardType = if (decimal) KeyboardType.Decimal else KeyboardType.Number),
        shape = RoundedCornerShape(8.dp),
        colors = projectFieldColors()
    )
}

@Composable
private fun OrientationChoice(label: String, selected: Boolean, onClick: () -> Unit) {
    Surface(
        modifier = Modifier.clip(CircleShape).clickable(onClick = onClick),
        shape = CircleShape,
        color = if (selected) NewProjectRed else Color(0xFFEDEDF0)
    ) {
        Text(label, modifier = Modifier.padding(horizontal = 10.dp, vertical = 5.dp), color = if (selected) Color.White else NewProjectMuted, style = MaterialTheme.typography.labelSmall)
    }
}

@Composable
private fun ShapeSummary(width: Int, height: Int, summary: String) {
    Row(
        modifier = Modifier.fillMaxWidth().height(36.dp).clip(RoundedCornerShape(7.dp)).background(Color(0xFFF2F2F4)),
        horizontalArrangement = Arrangement.Center, verticalAlignment = Alignment.CenterVertically
    ) {
        Canvas(Modifier.size(width = 32.dp, height = 25.dp)) {
            if (width > 0 && height > 0) {
                val ratio = width.toFloat() / height.toFloat()
                val w = if (ratio >= size.width / size.height) size.width else size.height * ratio
                val h = w / ratio
                drawRect(Color(0xFF1A1A1E), topLeft = androidx.compose.ui.geometry.Offset((size.width - w) / 2, (size.height - h) / 2), size = androidx.compose.ui.geometry.Size(w, h))
            }
        }
        Spacer(Modifier.width(7.dp))
        Text(summary, color = NewProjectInk, style = MaterialTheme.typography.labelMedium, fontWeight = FontWeight.SemiBold)
    }
}

@Composable
private fun projectFieldColors() = OutlinedTextFieldDefaults.colors(
    focusedBorderColor = NewProjectRed,
    unfocusedBorderColor = NewProjectLine,
    focusedContainerColor = Color.White,
    unfocusedContainerColor = Color.White,
    cursorColor = NewProjectRed,
    focusedTextColor = NewProjectInk,
    unfocusedTextColor = NewProjectInk
)
