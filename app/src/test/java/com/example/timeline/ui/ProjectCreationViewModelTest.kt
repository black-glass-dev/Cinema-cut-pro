package com.example.timeline.ui

import com.example.timeline.core.ProjectAspectRatioPreset
import com.example.timeline.core.ProjectFrameRatePreset
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class ProjectCreationViewModelTest {
    @Test
    fun ratioPresetUpdatesResolutionToTheSameCanvasShape() {
        val viewModel = ProjectCreationViewModel()
        val presets = listOf(
            ProjectAspectRatioPreset.W_16_9 to (16f / 9f),
            ProjectAspectRatioPreset.W_9_16 to (9f / 16f),
            ProjectAspectRatioPreset.W_1_1 to 1f,
            ProjectAspectRatioPreset.W_4_5 to (4f / 5f),
            ProjectAspectRatioPreset.W_5_4 to (5f / 4f),
            ProjectAspectRatioPreset.W_3_2 to (3f / 2f),
            ProjectAspectRatioPreset.W_4_3 to (4f / 3f),
            ProjectAspectRatioPreset.W_21_9 to (21f / 9f),
            ProjectAspectRatioPreset.W_2_35_1 to 2.35f,
            ProjectAspectRatioPreset.W_2_39_1 to 2.39f
        )
        presets.forEach { (preset, expectedRatio) ->
            viewModel.setAspectRatio(preset)
            val state = viewModel.uiState.value
            val actualRatio = state.resolutionWidth.toFloat() / state.resolutionHeight
            assertEquals(preset.name, expectedRatio, actualRatio, 0.006f)
            assertTrue(state.resolutionWidth % 2 == 0)
            assertTrue(state.resolutionHeight % 2 == 0)
        }
    }

    @Test
    fun lockedCustomAspectUsesTheRatioCapturedWhenLockWasEnabled() {
        val viewModel = ProjectCreationViewModel()
        viewModel.setAspectRatio(ProjectAspectRatioPreset.CUSTOM)
        viewModel.setCustomAspectWidth("1")
        viewModel.setCustomAspectWidth("16")
        assertEquals("16", viewModel.uiState.value.customAspectWidthText)
        assertEquals("9", viewModel.uiState.value.customAspectHeightText)

        viewModel.setAspectRatioLocked(false)
        viewModel.setCustomAspectWidth("4")
        viewModel.setCustomAspectHeight("3")
        viewModel.setAspectRatioLocked(true)
        viewModel.setCustomAspectWidth("43")
        assertEquals("32", viewModel.uiState.value.customAspectHeightText)
    }

    @Test
    fun customAspectAndResolutionAreValidatedAndOrientationCanBeChanged() {
        val viewModel = ProjectCreationViewModel()
        viewModel.setAspectRatio(ProjectAspectRatioPreset.CUSTOM)
        viewModel.setAspectRatioLocked(false)
        viewModel.setCustomAspectWidth("4")
        viewModel.setCustomAspectHeight("3")
        assertEquals(4, viewModel.uiState.value.aspectWidth)
        assertEquals(3, viewModel.uiState.value.aspectHeight)
        assertEquals(1920, viewModel.uiState.value.resolutionWidth)
        assertEquals(1440, viewModel.uiState.value.resolutionHeight)

        viewModel.selectCustomResolution()
        viewModel.setCustomResolutionWidth("1080")
        viewModel.setCustomResolutionHeight("1920")
        assertEquals(1080, viewModel.uiState.value.resolutionWidth)
        assertEquals(1920, viewModel.uiState.value.resolutionHeight)
        assertTrue(viewModel.uiState.value.isValid)

        viewModel.toggleResolutionOrientation()
        assertEquals(1920, viewModel.uiState.value.resolutionWidth)
        assertEquals(1080, viewModel.uiState.value.resolutionHeight)
        assertTrue(viewModel.uiState.value.isCustomResolution)
        assertTrue(viewModel.uiState.value.isValid)

        viewModel.setCustomResolutionWidth("")
        assertFalse(viewModel.uiState.value.isValid)
    }

    @Test
    fun customFrameRateProducesAValidatedRational() {
        val viewModel = ProjectCreationViewModel()
        viewModel.selectCustomFrameRate()
        viewModel.setCustomFrameRate("29.5")
        assertEquals(59, viewModel.customFrameRateRational().numerator)
        assertEquals(2, viewModel.customFrameRateRational().denominator)
        assertTrue(viewModel.uiState.value.isValid)

        viewModel.setCustomFrameRate("300")
        assertFalse(viewModel.uiState.value.isValid)
        viewModel.setFrameRate(ProjectFrameRatePreset.FPS_30)
        assertTrue(viewModel.uiState.value.isValid)
        assertFalse(viewModel.uiState.value.isCustomFrameRate)
    }
}
