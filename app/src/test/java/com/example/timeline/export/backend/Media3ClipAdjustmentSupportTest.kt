package com.example.timeline.export.backend

import com.example.model.adjustments.AdjustmentKeyframe
import com.example.model.adjustments.AdjustmentStack
import com.example.model.adjustments.KeyframeInterpolation
import com.example.model.adjustments.TargetType
import com.example.model.adjustments.VideoAdjustmentParams
import com.example.model.adjustments.Vector3
import com.example.model.colorgrade.CurveParams
import com.example.model.colorgrade.CurvePoint
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class Media3ClipAdjustmentSupportTest {
    private fun stack(params: VideoAdjustmentParams = VideoAdjustmentParams.default()) =
        AdjustmentStack("stack", TargetType.CLIP, "clip", params = params)

    @Test
    fun primarySettingsAreSupportedByTheSharedGpuEffect() {
        val params = VideoAdjustmentParams.default().copy(
            contrast = 0.20f,
            saturation = 1.20f,
            vibrance = 0.31f,
            temperature = 0.26f,
            tint = 0.40f,
            lift = Vector3(0.10f, -0.05f, 0.02f),
            gamma = Vector3(1.10f, 0.95f, 1.20f),
            gain = Vector3(1.10f, 0.90f, 1.25f),
            offsetWheel = Vector3(0.03f, -0.02f, 0.01f),
            clarity = 0.03f
        )
        val adjustments = stack(params)

        assertTrue(Media3ClipAdjustmentSupport.validationErrors(adjustments).isEmpty())
        assertTrue(Media3ClipAdjustmentSupport.hasRenderableAdjustments(adjustments))
    }

    @Test
    fun curvesAreRenderedAndMalformedCurveDataIsRejectedBeforeExport() {
        val curves = CurveParams().copy(
            masterPoints = listOf(CurvePoint(0f, 0f), CurvePoint(0.5f, 0.7f), CurvePoint(1f, 1f))
        )
        val valid = stack(VideoAdjustmentParams.default().copy(curves = curves))
        assertTrue(Media3ClipAdjustmentSupport.validationErrors(valid).isEmpty())
        assertTrue(Media3ClipAdjustmentSupport.hasRenderableAdjustments(valid))

        val invalid = stack(VideoAdjustmentParams.default().copy(
            curves = CurveParams().copy(masterPoints = listOf(CurvePoint(0f, 0f), CurvePoint(0.5f, 0.4f), CurvePoint(0.5f, 1f)))
        ))
        assertTrue(
            Media3ClipAdjustmentSupport.validationErrors(invalid)
                .any { it.contains("Color curves cannot be exported") }
        )
    }

    @Test
    fun nonPositiveGammaWheelIsRejectedBeforeShaderExecution() {
        val invalid = stack(VideoAdjustmentParams.default().copy(gamma = Vector3(0f, 1f, 1f)))

        assertTrue(
            Media3ClipAdjustmentSupport.validationErrors(invalid)
                .any { it.contains("gamma") && it.contains("supported ranges") }
        )
    }

    @Test
    fun defaultOrDisabledAdjustmentsDoNotRequireRendering() {
        val defaults = stack()
        assertTrue(Media3ClipAdjustmentSupport.validationErrors(defaults).isEmpty())
        assertFalse(Media3ClipAdjustmentSupport.hasRenderableAdjustments(defaults))

        val disabled = stack(VideoAdjustmentParams.default().copy(grainAmount = 0.5f)).copy(enabled = false)
        assertTrue(Media3ClipAdjustmentSupport.validationErrors(disabled).isEmpty())
        assertFalse(Media3ClipAdjustmentSupport.hasRenderableAdjustments(disabled))
    }

    @Test
    fun unsupportedOrAnimatedAdjustmentsAreRejectedInsteadOfDropped() {
        val animated = stack(VideoAdjustmentParams.default().copy(contrast = 0.2f)).copy(
            keyframes = listOf(
                AdjustmentKeyframe(
                    keyframeId = "keyframe",
                    parameterId = "contrast",
                    frame = 12L,
                    value = 0.5f,
                    interpolation = KeyframeInterpolation.LINEAR
                )
            )
        )
        assertTrue(
            Media3ClipAdjustmentSupport.validationErrors(animated)
                .any { it.contains("Animated clip adjustments") }
        )
    }

    @Test
    fun advancedShaderRenderedAdjustmentsExportThroughTheSharedEffect() {
        val params = VideoAdjustmentParams.default().copy(
            highlights = 0.3f,
            shadows = -0.2f,
            whites = 0.15f,
            blacks = -0.1f,
            fade = 0.1f,
            dehaze = 0.2f,
            grainAmount = 0.25f,
            vignetteAmount = -0.4f,
            skinToneProtection = 0.5f,
            shadowColorBalance = Vector3(0.1f, 0f, -0.1f),
            hslHue = floatArrayOf(0.2f, 0f, 0f, 0f, 0f, 0f, 0f, 0f),
            hslSat = floatArrayOf(0f, 0.3f, 0f, 0f, 0f, 0f, 0f, 0f),
            hslLum = floatArrayOf(0f, 0f, 0.1f, 0f, 0f, 0f, 0f, 0f),
            lutId = "cached-lut",
            lutIntensity = 0.8f,
            qualEnabled = true,
            qualHueCenter = 0.35f,
            qualHueWidth = 0.2f,
            redChannelMultiplier = 1.1f,
            rgbChannelMixer = floatArrayOf(1.1f, 0f, 0f, 0f, 1f, 0f, 0f, 0f, 1f)
        )
        val adjustments = stack(params)

        assertTrue(
            Media3ClipAdjustmentSupport.validationErrors(adjustments).joinToString("\n"),
            Media3ClipAdjustmentSupport.validationErrors(adjustments).isEmpty()
        )
        assertTrue(Media3ClipAdjustmentSupport.hasRenderableAdjustments(params))
    }

    @Test
    fun outOfRangeAdvancedAdjustmentValuesAreRejectedBeforeShaderExecution() {
        val invalid = stack(
            VideoAdjustmentParams.default().copy(
                grainAmount = 1.5f,
                skinToneProtection = -0.2f,
                vignetteMidpoint = 2f
            )
        )

        val errors = Media3ClipAdjustmentSupport.validationErrors(invalid)
        assertTrue(errors.any { it.contains("grain amount") && it.contains("supported ranges") })
        assertTrue(errors.any { it.contains("skin-tone strength") && it.contains("supported ranges") })
        assertTrue(errors.any { it.contains("vignette midpoint") && it.contains("supported ranges") })
    }

    @Test
    fun neutralHslArraysAndDisabledQualifierDoNotBlockPrimaryCorrectionExport() {
        val params = VideoAdjustmentParams.default().copy(
            contrast = 0.2f,
            saturation = 1.2f,
            hslHue = FloatArray(8),
            hslSat = FloatArray(8),
            hslLum = FloatArray(8),
            qualEnabled = false,
            qualHueCenter = 0.9f,
            qualHueWidth = 0.3f,
            qualInvert = true,
            qualShowMatte = true,
            qualHueShift = 0.4f
        )

        assertTrue(Media3ClipAdjustmentSupport.validationErrors(stack(params)).isEmpty())
        assertTrue(Media3ClipAdjustmentSupport.hasRenderableAdjustments(params))
    }

    @Test
    fun activeSelectiveHslAndEnabledQualifierRemainRejectedUntilValidatedForExport() {
        val selectiveHsl = VideoAdjustmentParams.default().copy(
            hslHue = FloatArray(8).also { it[2] = 0.15f }
        )
        assertTrue(
            Media3ClipAdjustmentSupport.validationErrors(stack(selectiveHsl))
                .any { it.contains("selective HSL bands") }
        )

        val qualifier = VideoAdjustmentParams.default().copy(
            qualEnabled = true,
            qualHueShift = 0.2f
        )
        assertTrue(
            Media3ClipAdjustmentSupport.validationErrors(stack(qualifier))
                .any { it.contains("HSL qualifier") }
        )
    }

    @Test
    fun gpuSupportedAdjustmentsDoNotRequireNativeCppButRangesAreStillValidated() {
        val contrast = stack(VideoAdjustmentParams.default().copy(contrast = 0.2f))
        assertTrue(Media3ClipAdjustmentSupport.validationErrors(contrast).isEmpty())

        val invalid = stack(VideoAdjustmentParams.default().copy(contrast = 1.5f))
        assertTrue(
            Media3ClipAdjustmentSupport.validationErrors(invalid)
                .any { it.contains("outside their supported ranges") }
        )
    }
}
