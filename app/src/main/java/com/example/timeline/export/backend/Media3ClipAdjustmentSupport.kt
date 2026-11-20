package com.example.timeline.export.backend

import com.example.model.adjustments.AdjustmentStack
import com.example.model.adjustments.VideoAdjustmentParams
import com.example.model.adjustments.Vector3
import com.example.model.adjustments.engine.VideoCurveTextureData

/**
 * Defines the deliberately small set of clip adjustments rendered by the Media3 export adapter.
 * Unsupported values are rejected during validation; they are never silently dropped.
 */
internal object Media3ClipAdjustmentSupport {
    private val defaults = VideoAdjustmentParams.default()

    fun hasRenderableAdjustments(stack: AdjustmentStack): Boolean =
        stack.enabled && hasRenderableAdjustments(stack.params)

    /**
     * The Media3 export path applies the exact same [VideoAdjustmentEffect] shader as preview,
     * so every parameter the shared GLSL renders is exportable. Anything non-default therefore
     * needs the effect; genuinely unsupported values are rejected by [validationErrors].
     */
    fun hasRenderableAdjustments(params: VideoAdjustmentParams): Boolean =
        params != defaults

    fun validationErrors(stack: AdjustmentStack): List<String> {
        if (!stack.enabled) return emptyList()

        val errors = mutableListOf<String>()
        if (stack.keyframes.isNotEmpty()) {
            errors += "Animated clip adjustments are not supported by this export path yet."
        }
        if (stack.blendMode != "NORMAL" || stack.opacity != 1f) {
            errors += "Clip-adjustment blend modes and opacity are not supported by this export path yet."
        }
        errors += validationErrors(stack.params)
        return errors
    }

    fun validationErrors(params: VideoAdjustmentParams): List<String> {
        val errors = mutableListOf<String>()
        val unsupported = unsupportedAdjustmentNames(params)
        if (unsupported.isNotEmpty()) {
            errors += "This export path does not support these clip adjustments yet: ${unsupported.joinToString()}."
        }

        val invalidValues = invalidSupportedAdjustmentNames(params)
        if (invalidValues.isNotEmpty()) {
            errors += "Clip-adjustment values are outside their supported ranges: ${invalidValues.joinToString()}."
        }

        val invalidCurves = VideoCurveTextureData.validationErrors(params.curves)
        if (invalidCurves.isNotEmpty()) {
            errors += "Color curves cannot be exported: ${invalidCurves.joinToString()}"
        }
        return errors
    }

    private fun invalidSupportedAdjustmentNames(params: VideoAdjustmentParams): List<String> = buildList {
        addIfOutOfRange("exposure", params.exposureStops, -5f, 5f)
        addIfOutOfRange("brightness", params.brightness, -1f, 1f)
        addIfOutOfRange("contrast", params.contrast, -1f, 1f)
        addIfOutOfRange("highlights", params.highlights, -1f, 1f)
        addIfOutOfRange("shadows", params.shadows, -1f, 1f)
        addIfOutOfRange("whites", params.whites, -1f, 1f)
        addIfOutOfRange("blacks", params.blacks, -1f, 1f)
        addIfOutOfRange("fade", params.fade, 0f, 1f)
        addIfOutOfRange("saturation", params.saturation, 0f, 2f)
        addIfOutOfRange("vibrance", params.vibrance, -1f, 1f)
        addIfOutOfRange("temperature", params.temperature, -1f, 1f)
        addIfOutOfRange("tint", params.tint, -1f, 1f)
        params.temperatureKelvin?.let { kelvin ->
            addIfOutOfRange("Kelvin temperature", kelvin, 2000f, 50000f)
        }
        addIfOutOfRange("sharpness", params.sharpness, 0f, 1f)
        addIfOutOfRange("mid detail", params.clarity, -1f, 1f)
        addIfOutOfRange("structure", params.structure, -1f, 1f)
        addIfOutOfRange("dehaze", params.dehaze, -1f, 1f)
        addIfOutOfRange("grain amount", params.grainAmount, 0f, 1f)
        addIfOutOfRange("grain size", params.grainSize, 0f, 1f)
        addIfOutOfRange("grain roughness", params.grainRoughness, 0f, 1f)
        addIfOutOfRange("vignette amount", params.vignetteAmount, -1f, 1f)
        addIfOutOfRange("vignette midpoint", params.vignetteMidpoint, 0f, 1f)
        addIfOutOfRange("vignette feather", params.vignetteFeather, 0f, 1f)
        addIfOutOfRange("vignette roundness", params.vignetteRoundness, -1f, 1f)
        addVectorIfOutOfRange("lift", params.lift, -1f, 1f)
        addVectorIfOutOfRange("gamma", params.gamma, 0.1f, 2f)
        addVectorIfOutOfRange("gain", params.gain, 0f, 2f)
        addVectorIfOutOfRange("offset wheel", params.offsetWheel, -1f, 1f)
        addIfOutOfRange("skin-tone strength", params.skinToneProtection, 0f, 1f)
        addIfOutOfRange("skin-tone hue center", params.skinToneHueCenter, 0f, 1f)
        addIfOutOfRange("skin-tone hue width", params.skinToneHueWidth, 0f, 1f)
        addVectorIfOutOfRange("shadow balance", params.shadowColorBalance, -1f, 1f)
        addVectorIfOutOfRange("midtone balance", params.midtoneColorBalance, -1f, 1f)
        addVectorIfOutOfRange("highlight balance", params.highlightColorBalance, -1f, 1f)
        addIfOutOfRange("qualifier hue center", params.qualHueCenter, 0f, 1f)
        addIfOutOfRange("qualifier hue width", params.qualHueWidth, 0f, 1f)
        addIfOutOfRange("qualifier hue feather", params.qualHueFeather, 0f, 1f)
        addIfOutOfRange("qualifier saturation minimum", params.qualSatMin, 0f, 1f)
        addIfOutOfRange("qualifier saturation maximum", params.qualSatMax, 0f, 1f)
        addIfOutOfRange("qualifier saturation feather", params.qualSatFeather, 0f, 1f)
        addIfOutOfRange("qualifier luminance minimum", params.qualLumMin, 0f, 1f)
        addIfOutOfRange("qualifier luminance maximum", params.qualLumMax, 0f, 1f)
        addIfOutOfRange("qualifier luminance feather", params.qualLumFeather, 0f, 1f)
        addIfOutOfRange("qualifier saturation", params.qualSat, 0f, 2f)
        addIfOutOfRange("qualifier hue shift", params.qualHueShift, -1f, 1f)
        addIfOutOfRange("qualifier luminance", params.qualLum, -1f, 1f)
        addIfOutOfRange("qualifier contrast", params.qualContrast, -1f, 1f)
        addIfOutOfRange("qualifier temperature", params.qualTemp, -1f, 1f)
        addIfOutOfRange("qualifier tint", params.qualTint, -1f, 1f)
        addVectorIfOutOfRange("channel multipliers", Vector3(
            params.redChannelMultiplier,
            params.greenChannelMultiplier,
            params.blueChannelMultiplier
        ), 0f, 4f)
        addIfOutOfRange("channel offsets", params.redOffset, -1f, 1f)
        addIfOutOfRange("channel offsets", params.greenOffset, -1f, 1f)
        addIfOutOfRange("channel offsets", params.blueOffset, -1f, 1f)
    }

    private fun MutableList<String>.addIfOutOfRange(
        name: String,
        value: Float,
        minimum: Float,
        maximum: Float
    ) {
        if (!value.isFinite() || value < minimum || value > maximum) add(name)
    }

    private fun MutableList<String>.addVectorIfOutOfRange(
        name: String,
        value: Vector3,
        minimum: Float,
        maximum: Float
    ) {
        if (listOf(value.x, value.y, value.z).any { !it.isFinite() || it < minimum || it > maximum }) add(name)
    }

    /**
     * Lists only parameters the shared preview/export GLSL effect does not render. The export
     * pipeline applies the same `VideoAdjustmentEffect` as preview, so this list stays
     * deliberately tiny; everything else is range-checked above and rendered by the shared
     * shader instead of being dropped.
     */
    private fun unsupportedAdjustmentNames(params: VideoAdjustmentParams): List<String> = buildList {
        // The legacy string label is not consumed by the shared shader (it renders
        // inputLogProfile/inputColorSpace); reject it instead of silently ignoring it.
        if (params.inputLogType != defaults.inputLogType) add("legacy input log type label")
    }
}
