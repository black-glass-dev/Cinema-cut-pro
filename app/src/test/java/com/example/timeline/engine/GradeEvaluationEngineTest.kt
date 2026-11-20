package com.example.timeline.engine

import com.example.model.adjustments.AdjustmentStack
import com.example.model.adjustments.TargetType
import com.example.model.adjustments.Vector3
import com.example.model.adjustments.VideoAdjustmentParams
import com.example.model.colorgrade.ColorGradeLayer
import com.example.model.colorgrade.ColorGradeLayerStack
import com.example.model.colorgrade.ColorGradeKeyframe
import com.example.model.colorgrade.ColorGradeParams
import com.example.model.colorgrade.ColorGradeStack
import com.example.model.colorgrade.ColorGradeTarget
import com.example.model.colorgrade.ColorLayerType
import com.example.model.colorgrade.ColorWheelParams
import com.example.timeline.core.ProjectSettings
import com.example.timeline.core.TimelineClip
import com.example.timeline.core.TimelineProject
import com.example.timeline.core.TimelineTrack
import com.example.timeline.core.TrackType
import org.junit.Assert.assertEquals
import org.junit.Test

class GradeEvaluationEngineTest {
    private fun grade(params: ColorGradeParams) = ColorGradeStack(
        targetType = ColorGradeTarget.CLIP,
        targetId = "clip",
        primaryCorrections = params
    )

    private fun project(clip: TimelineClip) = TimelineProject(
        id = "project",
        settings = ProjectSettings(projectId = "project", projectName = "Color test"),
        tracks = listOf(
            TimelineTrack(
                id = "video",
                name = "Video",
                type = TrackType.VIDEO,
                clips = listOf(clip)
            )
        )
    )

    private fun clip(
        layers: ColorGradeLayerStack,
        colorGrade: ColorGradeStack = grade(ColorGradeParams()),
        adjustments: VideoAdjustmentParams = VideoAdjustmentParams.default()
    ) = TimelineClip(
        id = "clip",
        trackId = "video",
        mediaId = "asset",
        name = "clip.mp4",
        sourceIn = 0L,
        sourceOut = 100L,
        timelineStart = 0L,
        adjustments = AdjustmentStack(
            stackId = "stack-clip",
            targetType = TargetType.CLIP,
            targetId = "clip",
            params = adjustments
        ),
        colorGrade = colorGrade,
        colorLayers = layers
    )

    @Test
    fun unmirroredClipGradeIsRestoredWithoutOverwritingOtherAdjustments() {
        val clipGrade = grade(
            ColorGradeParams(
                lift = ColorWheelParams(r = 0.25f),
                saturation = 1.4f
            )
        )
        val result = GradeEvaluationEngine.evaluateFrame(
            project(
                clip(
                    layers = ColorGradeLayerStack(layers = emptyList()),
                    colorGrade = clipGrade,
                    adjustments = VideoAdjustmentParams.default().copy(exposureStops = 0.35f)
                )
            ),
            frame = 0L
        )

        assertEquals(Vector3(0.25f, 0f, 0f), result.lift)
        assertEquals(1.4f, result.saturation, 0.00001f)
        assertEquals(0.35f, result.exposureStops, 0.00001f)
    }

    @Test
    fun mirroredClipGradeIsNotAppliedTwice() {
        val clipGrade = grade(
            ColorGradeParams(
                lift = ColorWheelParams(r = 0.25f),
                saturation = 1.4f
            )
        )
        val result = GradeEvaluationEngine.evaluateFrame(
            project(
                clip(
                    layers = ColorGradeLayerStack(layers = emptyList()),
                    colorGrade = clipGrade,
                    adjustments = VideoAdjustmentParams.default().copy(
                        lift = Vector3(0.25f, 0f, 0f),
                        saturation = 1.4f
                    )
                )
            ),
            frame = 0L
        )

        assertEquals(Vector3(0.25f, 0f, 0f), result.lift)
        assertEquals(1.4f, result.saturation, 0.00001f)
    }

    @Test
    fun clipGradeKeyframesReachFrameEvaluation() {
        val keyframedGrade = grade(ColorGradeParams()).copy(
            keyframes = listOf(
                ColorGradeKeyframe(parameterId = "primaryCorrections.saturation", frame = 0L, value = 1f),
                ColorGradeKeyframe(parameterId = "primaryCorrections.saturation", frame = 10L, value = 1.8f)
            )
        )
        val result = GradeEvaluationEngine.evaluateFrame(
            project(clip(ColorGradeLayerStack(layers = emptyList()), colorGrade = keyframedGrade)),
            frame = 5L
        )

        assertEquals(1.4f, result.saturation, 0.00001f)
    }

    @Test
    fun activeColorNodeFlowsIntoFrameEvaluation() {
        val node = ColorGradeLayer(
            id = "node",
            name = "Primary node",
            type = ColorLayerType.PRIMARY_CORRECTION,
            grade = grade(ColorGradeParams(lift = ColorWheelParams(r = 0.25f)))
        )
        val result = GradeEvaluationEngine.evaluateFrame(
            project(clip(ColorGradeLayerStack(layers = listOf(node)))),
            frame = 0L
        )

        assertEquals(Vector3(0.25f, 0f, 0f), result.lift)
    }

    @Test
    fun disabledColorNodeOrStackHasNoPreviewEffect() {
        val node = ColorGradeLayer(
            id = "node",
            name = "Primary node",
            type = ColorLayerType.PRIMARY_CORRECTION,
            grade = grade(ColorGradeParams(lift = ColorWheelParams(r = 0.25f)))
        )
        val disabledNode = GradeEvaluationEngine.evaluateFrame(
            project(clip(ColorGradeLayerStack(layers = listOf(node.copy(enabled = false))))),
            frame = 0L
        )
        val disabledStack = GradeEvaluationEngine.evaluateFrame(
            project(clip(ColorGradeLayerStack(layers = listOf(node), enabled = false))),
            frame = 0L
        )

        assertEquals(Vector3.ZERO, disabledNode.lift)
        assertEquals(Vector3.ZERO, disabledStack.lift)
    }

    @Test
    fun olderMirroredNodeIsNotAppliedTwice() {
        val grade = grade(ColorGradeParams(lift = ColorWheelParams(r = 0.25f)))
        val legacyNode = ColorGradeLayer(
            id = "node",
            name = "Primary node",
            type = ColorLayerType.PRIMARY_CORRECTION,
            grade = grade
        )
        val clip = clip(
            layers = ColorGradeLayerStack(layers = listOf(legacyNode)),
            colorGrade = grade,
            adjustments = VideoAdjustmentParams.default().copy(lift = Vector3(0.25f, 0f, 0f))
        )
        val result = GradeEvaluationEngine.evaluateFrame(project(clip), frame = 0L)

        assertEquals(Vector3(0.25f, 0f, 0f), result.lift)
    }

    @Test
    fun clipGradeComposerMatchesProjectEvaluationWithoutAdjustmentLayers() {
        val node = ColorGradeLayer(
            id = "node",
            name = "Primary node",
            type = ColorLayerType.PRIMARY_CORRECTION,
            grade = grade(ColorGradeParams(contrast = 0.2f))
        )
        val gradedClip = clip(
            layers = ColorGradeLayerStack(layers = listOf(node)),
            colorGrade = grade(
                ColorGradeParams(
                    lift = ColorWheelParams(r = 0.25f),
                    saturation = 1.4f
                )
            ),
            adjustments = VideoAdjustmentParams.default().copy(exposureStops = 0.35f)
        )

        val composed = GradeEvaluationEngine.evaluateClipGrade(gradedClip, frame = 0L)
        val evaluated = GradeEvaluationEngine.evaluateFrame(project(gradedClip), frame = 0L)

        assertEquals(composed, evaluated)
        assertEquals(Vector3(0.25f, 0f, 0f), composed.lift)
        assertEquals(1.4f, composed.saturation, 0.00001f)
        assertEquals(0.35f, composed.exposureStops, 0.00001f)
    }
}
