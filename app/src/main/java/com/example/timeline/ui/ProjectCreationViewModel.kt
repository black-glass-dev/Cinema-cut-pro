package com.example.timeline.ui

import androidx.lifecycle.ViewModel
import com.example.timeline.core.ProjectAspectRatioPreset
import com.example.timeline.core.ProjectAudioSampleRate
import com.example.timeline.core.ProjectColorSpace
import com.example.timeline.core.ProjectFrameRatePreset
import com.example.timeline.core.Rational
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.update
import kotlin.math.roundToInt

data class ResolutionOption(val width: Int, val height: Int) {
    val display: String get() = "${width}×${height}"
    val description: String
        get() = when {
            width >= 3800 || height >= 3800 -> "4K UHD"
            width >= 2500 || height >= 2500 -> "QHD"
            width >= 1900 || height >= 1900 -> "Full HD"
            else -> "HD"
        }
}

data class CreateProjectUiState(
    val projectName: String = "Untitled",
    val aspectRatio: ProjectAspectRatioPreset = ProjectAspectRatioPreset.W_16_9,
    val customAspectWidth: Int = 16,
    val customAspectHeight: Int = 9,
    val customAspectWidthText: String = "16",
    val customAspectHeightText: String = "9",
    val lockAspectRatio: Boolean = true,
    val lockedAspectWidth: Int = 16,
    val lockedAspectHeight: Int = 9,
    val resolutionWidth: Int = 1920,
    val resolutionHeight: Int = 1080,
    val customResolutionWidthText: String = "1920",
    val customResolutionHeightText: String = "1080",
    val isCustomResolution: Boolean = false,
    val frameRatePreset: ProjectFrameRatePreset = ProjectFrameRatePreset.FPS_24,
    val customFrameRateText: String = "24",
    val isCustomFrameRate: Boolean = false,
    val colorSpace: ProjectColorSpace = ProjectColorSpace.REC_709,
    val audioSampleRate: ProjectAudioSampleRate = ProjectAudioSampleRate.HZ_48000,
    val resolutionOptions: List<ResolutionOption> = resolutionsFor(ProjectAspectRatioPreset.W_16_9)
) {
    val aspectWidth: Int
        get() = when (aspectRatio) {
            ProjectAspectRatioPreset.W_16_9 -> 16
            ProjectAspectRatioPreset.W_9_16 -> 9
            ProjectAspectRatioPreset.W_1_1 -> 1
            ProjectAspectRatioPreset.W_4_5 -> 4
            ProjectAspectRatioPreset.W_5_4 -> 5
            ProjectAspectRatioPreset.W_3_2 -> 3
            ProjectAspectRatioPreset.W_4_3 -> 4
            ProjectAspectRatioPreset.W_21_9 -> 21
            ProjectAspectRatioPreset.W_2_35_1 -> 235
            ProjectAspectRatioPreset.W_2_39_1 -> 239
            ProjectAspectRatioPreset.CUSTOM -> customAspectWidth
        }
    val aspectHeight: Int
        get() = when (aspectRatio) {
            ProjectAspectRatioPreset.W_16_9 -> 9
            ProjectAspectRatioPreset.W_9_16 -> 16
            ProjectAspectRatioPreset.W_1_1 -> 1
            ProjectAspectRatioPreset.W_4_5 -> 5
            ProjectAspectRatioPreset.W_5_4 -> 4
            ProjectAspectRatioPreset.W_3_2 -> 2
            ProjectAspectRatioPreset.W_4_3 -> 3
            ProjectAspectRatioPreset.W_21_9 -> 9
            ProjectAspectRatioPreset.W_2_35_1 -> 100
            ProjectAspectRatioPreset.W_2_39_1 -> 100
            ProjectAspectRatioPreset.CUSTOM -> customAspectHeight
        }
    val isValid: Boolean
        get() {
            val validName = projectName.isNotBlank()
            val validResolution = resolutionWidth in 2..16384 && resolutionHeight in 2..16384 &&
                resolutionWidth % 2 == 0 && resolutionHeight % 2 == 0 &&
                (!isCustomResolution || customResolutionWidthText.toIntOrNull() == resolutionWidth && customResolutionHeightText.toIntOrNull() == resolutionHeight)
            val validRatio = aspectWidth in 1..10000 && aspectHeight in 1..10000 &&
                (aspectRatio != ProjectAspectRatioPreset.CUSTOM || customAspectWidthText.toIntOrNull() == customAspectWidth && customAspectHeightText.toIntOrNull() == customAspectHeight)
            val fps = customFrameRateText.toDoubleOrNull()
            val validFps = !isCustomFrameRate || fps != null && fps.isFinite() && fps in 1.0..240.0
            return validName && validResolution && validRatio && validFps
        }
}

private val aspectRatios = mapOf(
    ProjectAspectRatioPreset.W_16_9 to (16.0 / 9.0),
    ProjectAspectRatioPreset.W_9_16 to (9.0 / 16.0),
    ProjectAspectRatioPreset.W_1_1 to 1.0,
    ProjectAspectRatioPreset.W_4_5 to (4.0 / 5.0),
    ProjectAspectRatioPreset.W_5_4 to (5.0 / 4.0),
    ProjectAspectRatioPreset.W_3_2 to (3.0 / 2.0),
    ProjectAspectRatioPreset.W_4_3 to (4.0 / 3.0),
    ProjectAspectRatioPreset.W_21_9 to (21.0 / 9.0),
    ProjectAspectRatioPreset.W_2_35_1 to 2.35,
    ProjectAspectRatioPreset.W_2_39_1 to 2.39
)

private fun even(value: Double): Int = (value / 2.0).roundToInt().coerceAtLeast(1) * 2

private fun resolutionsFor(ratioPreset: ProjectAspectRatioPreset): List<ResolutionOption> {
    val ratio = aspectRatios[ratioPreset] ?: return listOf(ResolutionOption(1920, 1080))
    val longEdges = listOf(1280, 1920, 2560, 3840)
    return longEdges.map { longEdge ->
        if (ratio >= 1.0) ResolutionOption(longEdge, even(longEdge / ratio))
        else ResolutionOption(even(longEdge * ratio), longEdge)
    }
}

class ProjectCreationViewModel : ViewModel() {
    private val _uiState = MutableStateFlow(CreateProjectUiState())
    val uiState: StateFlow<CreateProjectUiState> = _uiState.asStateFlow()

    fun setProjectName(name: String) = _uiState.update { it.copy(projectName = name.take(80)) }

    fun setAspectRatio(ratio: ProjectAspectRatioPreset) {
        _uiState.update { current ->
            if (ratio == ProjectAspectRatioPreset.CUSTOM) {
                syncResolutionToAspectRatio(current.copy(aspectRatio = ratio))
            } else {
                val options = resolutionsFor(ratio)
                val selected = options.getOrNull(1) ?: options.first()
                current.copy(
                    aspectRatio = ratio,
                    resolutionOptions = options,
                    resolutionWidth = selected.width,
                    resolutionHeight = selected.height,
                    customResolutionWidthText = selected.width.toString(),
                    customResolutionHeightText = selected.height.toString(),
                    isCustomResolution = false
                )
            }
        }
    }

    fun setCustomAspectWidth(text: String) = _uiState.update { current ->
        val normalized = text.filter(Char::isDigit).take(5)
        val nextWidth = normalized.toIntOrNull()
        if (current.lockAspectRatio && nextWidth != null && current.lockedAspectWidth > 0 && current.lockedAspectHeight > 0) {
            val nextHeight = (nextWidth.toDouble() * current.lockedAspectHeight / current.lockedAspectWidth.toDouble()).roundToInt().coerceAtLeast(1)
            syncResolutionToAspectRatio(current.copy(customAspectWidthText = normalized, customAspectWidth = nextWidth, customAspectHeight = nextHeight, customAspectHeightText = nextHeight.toString()))
        } else syncResolutionToAspectRatio(current.copy(customAspectWidthText = normalized, customAspectWidth = nextWidth ?: 0))
    }

    fun setCustomAspectHeight(text: String) = _uiState.update { current ->
        val normalized = text.filter(Char::isDigit).take(5)
        val nextHeight = normalized.toIntOrNull()
        if (current.lockAspectRatio && nextHeight != null && current.lockedAspectWidth > 0 && current.lockedAspectHeight > 0) {
            val nextWidth = (nextHeight.toDouble() * current.lockedAspectWidth / current.lockedAspectHeight.toDouble()).roundToInt().coerceAtLeast(1)
            syncResolutionToAspectRatio(current.copy(customAspectHeightText = normalized, customAspectHeight = nextHeight, customAspectWidth = nextWidth, customAspectWidthText = nextWidth.toString()))
        } else syncResolutionToAspectRatio(current.copy(customAspectHeightText = normalized, customAspectHeight = nextHeight ?: 0))
    }

    private fun syncResolutionToAspectRatio(state: CreateProjectUiState): CreateProjectUiState {
        if (state.isCustomResolution || state.aspectWidth <= 0 || state.aspectHeight <= 0) return state
        val ratio = state.aspectWidth.toDouble() / state.aspectHeight.toDouble()
        val longEdge = maxOf(state.resolutionWidth, state.resolutionHeight).coerceIn(2, 16384)
        val resolution = if (ratio >= 1.0) {
            ResolutionOption(longEdge, even(longEdge / ratio))
        } else {
            ResolutionOption(even(longEdge * ratio), longEdge)
        }
        val options = listOf(1280, 1920, 2560, 3840).map { edge ->
            if (ratio >= 1.0) ResolutionOption(edge, even(edge / ratio)) else ResolutionOption(even(edge * ratio), edge)
        }
        return state.copy(
            resolutionWidth = resolution.width,
            resolutionHeight = resolution.height,
            customResolutionWidthText = resolution.width.toString(),
            customResolutionHeightText = resolution.height.toString(),
            resolutionOptions = options
        )
    }

    fun setAspectRatioLocked(locked: Boolean) = _uiState.update { current ->
        if (locked) current.copy(
            lockAspectRatio = true,
            lockedAspectWidth = current.customAspectWidth.coerceAtLeast(1),
            lockedAspectHeight = current.customAspectHeight.coerceAtLeast(1)
        ) else current.copy(lockAspectRatio = false)
    }

    fun setResolution(width: Int, height: Int) {
        val safeWidth = width.coerceIn(2, 16384)
        val safeHeight = height.coerceIn(2, 16384)
        _uiState.update { it.copy(
            resolutionWidth = safeWidth,
            resolutionHeight = safeHeight,
            customResolutionWidthText = safeWidth.toString(),
            customResolutionHeightText = safeHeight.toString(),
            isCustomResolution = false
        ) }
    }

    fun selectCustomResolution() = _uiState.update { it.copy(isCustomResolution = true) }

    fun setCustomResolutionWidth(text: String) = updateCustomResolution(text, widthField = true)
    fun setCustomResolutionHeight(text: String) = updateCustomResolution(text, widthField = false)

    private fun updateCustomResolution(text: String, widthField: Boolean) = _uiState.update { current ->
        val normalized = text.filter(Char::isDigit).take(5)
        val value = normalized.toIntOrNull()
        val next = if (value == null || value !in 2..16384) current else if (widthField) {
            current.copy(resolutionWidth = value)
        } else current.copy(resolutionHeight = value)
        if (widthField) next.copy(customResolutionWidthText = normalized, isCustomResolution = true)
        else next.copy(customResolutionHeightText = normalized, isCustomResolution = true)
    }

    fun toggleResolutionOrientation() = _uiState.update { current ->
        current.copy(
            resolutionWidth = current.resolutionHeight,
            resolutionHeight = current.resolutionWidth,
            customResolutionWidthText = current.resolutionHeight.toString(),
            customResolutionHeightText = current.resolutionWidth.toString(),
            isCustomResolution = true
        )
    }

    fun setFrameRate(frameRate: ProjectFrameRatePreset) = _uiState.update {
        it.copy(frameRatePreset = frameRate, isCustomFrameRate = false, customFrameRateText = formatFrameRate(frameRate.fps))
    }

    fun selectCustomFrameRate() = _uiState.update {
        val currentRate = if (it.isCustomFrameRate) it.customFrameRateText else formatFrameRate(it.frameRatePreset.fps)
        it.copy(frameRatePreset = ProjectFrameRatePreset.CUSTOM, isCustomFrameRate = true, customFrameRateText = currentRate)
    }

    fun setCustomFrameRate(text: String) = _uiState.update {
        val normalized = text.filter { character -> character.isDigit() || character == '.' }.take(7)
        it.copy(frameRatePreset = ProjectFrameRatePreset.CUSTOM, isCustomFrameRate = true, customFrameRateText = normalized)
    }

    fun customFrameRateRational(): Rational {
        val fps = _uiState.value.customFrameRateText.toDoubleOrNull()?.takeIf { it.isFinite() && it > 0.0 } ?: 24.0
        val denominator = 1000
        val numerator = (fps * denominator).roundToInt().coerceIn(1, 240_000)
        val divisor = gcd(numerator, denominator)
        return Rational(numerator / divisor, denominator / divisor)
    }

    fun setColorSpace(colorSpace: ProjectColorSpace) = _uiState.update { it.copy(colorSpace = colorSpace) }
    fun setAudioSampleRate(sampleRate: ProjectAudioSampleRate) = _uiState.update { it.copy(audioSampleRate = sampleRate) }

    private fun formatFrameRate(rate: Rational): String {
        if (rate.denominator == 1) return rate.numerator.toString()
        return String.format(java.util.Locale.US, "%.3f", rate.toDouble())
            .trimEnd('0')
            .trimEnd('.')
    }

    private fun gcd(a: Int, b: Int): Int = if (b == 0) a else gcd(b, a % b)
}
