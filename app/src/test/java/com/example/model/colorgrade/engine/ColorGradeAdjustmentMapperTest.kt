package com.example.model.colorgrade.engine

import com.example.model.adjustments.Vector3
import com.example.model.adjustments.VideoAdjustmentParams
import com.example.model.colorgrade.ColorGradeLayer
import com.example.model.colorgrade.ColorGradeParams
import com.example.model.colorgrade.ColorGradeStack
import com.example.model.colorgrade.ColorLayerType
import com.example.model.colorgrade.ColorWheelParams
import com.example.model.colorgrade.CurvePoint
import com.example.model.colorgrade.CurveParams
import com.example.model.colorgrade.LutGradeParams
import org.junit.Assert.assertEquals
import org.junit.Test

class ColorGradeAdjustmentMapperTest {
    private fun grade(params: ColorGradeParams) = ColorGradeStack(
        targetType = com.example.model.colorgrade.ColorGradeTarget.CLIP,
        targetId = "clip",
        primaryCorrections = params
    )

    private fun layer(
        grade: ColorGradeStack,
        enabled: Boolean = true,
        opacity: Float = 1f
    ) = ColorGradeLayer(
        id = "node",
        name = "Node",
        type = ColorLayerType.PRIMARY_CORRECTION,
        enabled = enabled,
        opacity = opacity,
        grade = grade
    )

    @Test
    fun neutralLayerPreservesTheExistingAdjustmentBlock() {
        val base = VideoAdjustmentParams.default().copy(
            exposureStops = 0.4f,
            contrast = -0.2f,
            hslHue = floatArrayOf(0.1f, 0f, 0f, 0f, 0f, 0f, 0f, 0f)
        )

        val result = ColorGradeAdjustmentMapper.applyLayer(base, layer(grade(ColorGradeParams())), frame = 0L)

        assertEquals(base, result)
    }

    @Test
    fun primaryWheelAndSaturationNodeAreAppliedToPreviewParams() {
        val nodeGrade = grade(
            ColorGradeParams(
                lift = ColorWheelParams(r = 0.3f, g = -0.2f, b = 0.1f, luma = 0.1f),
                saturation = 1.4f
            )
        )

        val result = ColorGradeAdjustmentMapper.applyLayer(
            VideoAdjustmentParams.default(),
            layer(nodeGrade),
            frame = 0L
        )

        assertEquals(Vector3(0.4f, -0.1f, 0.2f), result.lift)
        assertEquals(1.4f, result.saturation, 0.00001f)
    }

    @Test
    fun nodeOpacityScalesWheelAndBasicColorChanges() {
        val nodeGrade = grade(
            ColorGradeParams(
                lift = ColorWheelParams(r = 0.4f),
                saturation = 1.5f
            )
        )

        val result = ColorGradeAdjustmentMapper.applyLayer(
            VideoAdjustmentParams.default(),
            layer(nodeGrade, opacity = 0.25f),
            frame = 0L
        )

        assertEquals(0.1f, result.lift.x, 0.00001f)
        assertEquals(1.125f, result.saturation, 0.00001f)
    }

    @Test
    fun disabledNodeAndDisabledGradeDoNotChangePreviewParams() {
        val base = VideoAdjustmentParams.default().copy(lift = Vector3(0.2f, 0f, 0f))
        val nodeGrade = grade(ColorGradeParams(lift = ColorWheelParams(r = 0.8f)))

        val disabledLayer = ColorGradeAdjustmentMapper.applyLayer(
            base,
            layer(nodeGrade, enabled = false),
            frame = 0L
        )
        val disabledGrade = ColorGradeAdjustmentMapper.applyLayer(
            base,
            layer(nodeGrade.copy(enabled = false)),
            frame = 0L
        )

        assertEquals(base, disabledLayer)
        assertEquals(base, disabledGrade)
    }

    @Test
    fun nodeLutSelectionAndIntensityReachPreviewParams() {
        val nodeGrade = grade(ColorGradeParams()).copy(
            lutStack = LutGradeParams(lutId = "test-lut", intensity = 0.6f)
        )

        val result = ColorGradeAdjustmentMapper.applyLayer(
            VideoAdjustmentParams.default(),
            layer(nodeGrade),
            frame = 0L
        )

        assertEquals("test-lut", result.lutId)
        assertEquals(0.6f, result.lutIntensity, 0.00001f)
    }

    @Test
    fun updatingCurvesPreservesUnrelatedClipAdjustments() {
        val base = VideoAdjustmentParams.default().copy(
            temperature = 0.4f,
            shadowColorBalance = Vector3(0.2f, 0f, 0f),
            hslHue = floatArrayOf(0.1f, 0f, 0f, 0f, 0f, 0f, 0f, 0f)
        )
        val before = grade(ColorGradeParams())
        val after = before.copy(
            curves = CurveParams(masterPoints = listOf(CurvePoint(0f, 0f), CurvePoint(1f, 0.7f)))
        )

        val result = ColorGradeAdjustmentMapper.applyClipGradeChanges(base, before, after)

        assertEquals(base.temperature, result.temperature, 0.00001f)
        assertEquals(base.shadowColorBalance, result.shadowColorBalance)
        assertEquals(base.hslHue!!.toList(), result.hslHue!!.toList())
        assertEquals(after.curves, result.curves)
    }

    @Test
    fun resettingClipGradeRemovesItsMirroredValues() {
        val before = grade(
            ColorGradeParams(
                lift = ColorWheelParams(r = 0.3f),
                saturation = 1.5f
            )
        )
        val base = PrimaryWheelAdjustmentMapper.apply(
            VideoAdjustmentParams.default(),
            before.primaryCorrections
        )
        val after = before.copy(primaryCorrections = ColorGradeParams())

        val result = ColorGradeAdjustmentMapper.applyClipGradeChanges(base, before, after)

        assertEquals(Vector3.ZERO, result.lift)
        assertEquals(1f, result.saturation, 0.00001f)
    }

    @Test
    fun curvesComposeWithEarlierGradeAtNodeOpacity() {
        val darkenHighlights = CurveParams(
            masterPoints = listOf(CurvePoint(0f, 0f), CurvePoint(1f, 0.5f))
        )
        val nodeGrade = grade(ColorGradeParams()).copy(curves = darkenHighlights)

        val result = ColorGradeAdjustmentMapper.applyLayer(
            VideoAdjustmentParams.default(),
            layer(nodeGrade, opacity = 0.5f),
            frame = 0L
        )

        assertEquals(0.75f, result.curves.masterPoints.last().y, 0.00001f)
    }
}
