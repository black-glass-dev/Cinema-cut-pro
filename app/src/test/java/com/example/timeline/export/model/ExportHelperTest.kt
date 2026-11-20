package com.example.timeline.export.model

import com.example.timeline.core.ProjectFrameRatePreset
import com.example.timeline.core.ProjectSettings
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

class ExportHelperTest {
    private fun settings(rate: ProjectFrameRatePreset = ProjectFrameRatePreset.FPS_24): ExportSettings {
        val projectSettings = ProjectSettings(
            projectId = "project",
            projectName = "Test",
            frameRate = rate
        )
        return ExportHelper.defaultExportSettings(projectSettings, "project")
    }

    @Test
    fun durationMicrosecondsProduceExactFrameAndByteEstimates() {
        val settings = settings()
        assertEquals(24L, ExportHelper.calculateTotalFrames(settings, 1_000_000L))
        assertEquals(1L, ExportHelper.calculateTotalFrames(settings, 1L))
        assertEquals(1_891_000L, ExportHelper.estimateFileSize(settings, 1_000_000L))
    }

    @Test
    fun invalidDurationAndSizeOverflowReject() {
        val settings = settings(ProjectFrameRatePreset.FPS_120)
        assertNull(ExportHelper.calculateTotalFrames(settings, -1L))
        assertNull(ExportHelper.estimateFileSize(settings, -1L))
        assertNull(ExportHelper.estimateFileSize(settings, Long.MAX_VALUE))
    }
}
