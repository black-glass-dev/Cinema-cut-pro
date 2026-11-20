package com.example.model.colorgrade.engine

import com.example.model.adjustments.ColorSpaceProfile
import com.example.model.adjustments.LogProfile
import com.example.model.adjustments.Vector3
import com.example.model.adjustments.VideoAdjustmentParams
import com.example.model.adjustments.engine.ToneMappingMode
import com.example.model.colorgrade.ColorGradeStack
import com.example.model.colorgrade.ColorGradeParams
import com.example.model.colorgrade.ColorRangeParams
import com.example.model.colorgrade.ColorGradeLayer
import com.example.model.colorgrade.ColorMatchParams
import com.example.model.colorgrade.CurveParams
import com.example.model.colorgrade.CurvePoint
import com.example.model.colorgrade.HdrToneMappingParams
import com.example.model.colorgrade.HslQualifierParams
import com.example.model.colorgrade.LogTransformParams
import com.example.model.colorgrade.LutGradeParams
import com.example.model.colorgrade.SelectiveColorParams
import com.example.model.colorgrade.SkinToneProtectionParams

/**
 * Maps a color-grade node onto the shared Media3 adjustment parameters used by preview.
 * The renderer currently accepts one parameter block, so nodes are composed in order here.
 */
object ColorGradeAdjustmentMapper {
    fun applyLayer(
        base: VideoAdjustmentParams,
        layer: ColorGradeLayer,
        frame: Long
    ): VideoAdjustmentParams {
        if (!layer.enabled) return base
        return applyGrade(base, layer.grade, layer.opacity, frame)
    }

    /** Apply one grade at the requested opacity without mutating the source model. */
    fun applyGrade(
        base: VideoAdjustmentParams,
        sourceGrade: ColorGradeStack,
        layerOpacity: Float = 1f,
        frame: Long = 0L
    ): VideoAdjustmentParams {
        if (!sourceGrade.enabled) return base
        val amount = (sourceGrade.opacity * layerOpacity).coerceIn(0f, 1f)
        if (amount <= 0f) return base

        val grade = sourceGrade.evaluateGradeAtFrame(frame)
        if (isNeutral(grade)) return base
        val overlay = toAdjustmentParams(grade)

        fun add(current: Float, value: Float): Float = current + value * amount
        fun mix(current: Float, value: Float): Float = current + (value - current) * amount
        fun addVector(current: Vector3, value: Vector3, minimum: Float, maximum: Float) = Vector3(
            add(current.x, value.x).coerceIn(minimum, maximum),
            add(current.y, value.y).coerceIn(minimum, maximum),
            add(current.z, value.z).coerceIn(minimum, maximum)
        )
        fun multiplyVector(current: Vector3, value: Vector3, minimum: Float, maximum: Float) = Vector3(
            (current.x * (1f + (value.x - 1f) * amount)).coerceIn(minimum, maximum),
            (current.y * (1f + (value.y - 1f) * amount)).coerceIn(minimum, maximum),
            (current.z * (1f + (value.z - 1f) * amount)).coerceIn(minimum, maximum)
        )

        val qualifier = grade.hslAdjustments
        val hasQualifier = qualifier.enabled || qualifier.showMatte
        val qualifierAmount = if (hasQualifier) amount else 0f
        val baseHue = base.hslHue ?: FloatArray(8)
        val baseSat = base.hslSat ?: FloatArray(8)
        val baseLum = base.hslLum ?: FloatArray(8)
        val overlayHue = overlay.hslHue ?: FloatArray(8)
        val overlaySat = overlay.hslSat ?: FloatArray(8)
        val overlayLum = overlay.hslLum ?: FloatArray(8)
        val hslHue = blendBands(baseHue, overlayHue, amount)
        val hslSat = blendBands(baseSat, overlaySat, amount)
        val hslLum = blendBands(baseLum, overlayLum, amount)

        val incomingHasLut = overlay.lutId != null
        val lutId = if (incomingHasLut) overlay.lutId else base.lutId
        val lutIntensity = when {
            !incomingHasLut -> base.lutIntensity
            base.lutId == overlay.lutId -> mix(base.lutIntensity, overlay.lutIntensity, amount)
            else -> overlay.lutIntensity * amount
        }.coerceIn(0f, 1f)

        val hasInputTransform = grade.inputTransform != LogTransformParams()
        val hasToneMappingTransform = grade.hdrToneMapping.enabled ||
            grade.inputTransform.toneMappingMode != LogTransformParams().toneMappingMode
        val inputTransformOverridesBase = hasInputTransform && amount >= 0.5f
        val toneMappingOverridesBase = hasToneMappingTransform && amount >= 0.5f
        val rolloffOverridesBase = amount >= 0.5f && (
            grade.hdrToneMapping.enabled ||
                grade.inputTransform.highlightRolloff != LogTransformParams().highlightRolloff
            )

        return base.copy(
            exposureStops = add(base.exposureStops, overlay.exposureStops),
            brightness = add(base.brightness, overlay.brightness).coerceIn(-1f, 1f),
            contrast = add(base.contrast, overlay.contrast).coerceIn(-1f, 1f),
            highlights = add(base.highlights, overlay.highlights).coerceIn(-1f, 1f),
            shadows = add(base.shadows, overlay.shadows).coerceIn(-1f, 1f),
            whites = add(base.whites, overlay.whites).coerceIn(-1f, 1f),
            blacks = add(base.blacks, overlay.blacks).coerceIn(-1f, 1f),
            fade = add(base.fade, overlay.fade).coerceIn(0f, 1f),
            saturation = (base.saturation * (1f + (overlay.saturation - 1f) * amount)).coerceIn(0f, 2f),
            vibrance = add(base.vibrance, overlay.vibrance).coerceIn(-1f, 1f),
            temperature = add(base.temperature, overlay.temperature).coerceIn(-1f, 1f),
            temperatureKelvin = if (overlay.temperatureKelvin != null && amount >= 0.5f) {
                overlay.temperatureKelvin
            } else {
                base.temperatureKelvin
            },
            tint = add(base.tint, overlay.tint).coerceIn(-1f, 1f),
            sharpness = add(base.sharpness, overlay.sharpness).coerceIn(0f, 1f),
            clarity = add(base.clarity, overlay.clarity).coerceIn(-1f, 1f),
            structure = add(base.structure, overlay.structure).coerceIn(-1f, 1f),
            dehaze = add(base.dehaze, overlay.dehaze).coerceIn(-1f, 1f),
            grainAmount = add(base.grainAmount, overlay.grainAmount).coerceIn(0f, 1f),
            grainSize = base.grainSize,
            grainRoughness = base.grainRoughness,
            vignetteAmount = add(base.vignetteAmount, overlay.vignetteAmount).coerceIn(-1f, 1f),
            vignetteMidpoint = base.vignetteMidpoint,
            vignetteFeather = base.vignetteFeather,
            vignetteRoundness = base.vignetteRoundness,
            lift = addVector(base.lift, overlay.lift, -1f, 1f),
            gamma = multiplyVector(base.gamma, overlay.gamma, 0.1f, 2f),
            gain = multiplyVector(base.gain, overlay.gain, 0f, 2f),
            offsetWheel = addVector(base.offsetWheel, overlay.offsetWheel, -1f, 1f),
            skinToneProtection = (1f - (1f - base.skinToneProtection) *
                (1f - overlay.skinToneProtection * amount)).coerceIn(0f, 1f),
            skinToneHueCenter = if (overlay.skinToneProtection > 0f && amount >= 0.5f) {
                overlay.skinToneHueCenter
            } else {
                base.skinToneHueCenter
            },
            skinToneHueWidth = if (overlay.skinToneProtection > 0f && amount >= 0.5f) {
                overlay.skinToneHueWidth
            } else {
                base.skinToneHueWidth
            },
            shadowColorBalance = addVector(base.shadowColorBalance, overlay.shadowColorBalance, -1f, 1f),
            midtoneColorBalance = addVector(base.midtoneColorBalance, overlay.midtoneColorBalance, -1f, 1f),
            highlightColorBalance = addVector(base.highlightColorBalance, overlay.highlightColorBalance, -1f, 1f),
            shadowLumaRange = if (
                (grade.primaryCorrections.shadows.lumaMin != 0f ||
                    grade.primaryCorrections.shadows.lumaMax != 1f) && amount >= 0.5f
            ) overlay.shadowLumaRange else base.shadowLumaRange,
            highlightLumaRange = if (
                (grade.primaryCorrections.highlights.lumaMin != 0f ||
                    grade.primaryCorrections.highlights.lumaMax != 1f) && amount >= 0.5f
            ) overlay.highlightLumaRange else base.highlightLumaRange,
            hslHue = if (hslHue.any { it != 0f } || base.hslHue != null) hslHue else null,
            hslSat = if (hslSat.any { it != 0f } || base.hslSat != null) hslSat else null,
            hslLum = if (hslLum.any { it != 0f } || base.hslLum != null) hslLum else null,
            curves = blendCurves(base.curves, overlay.curves, amount),
            lutId = lutId,
            lutIntensity = lutIntensity,
            inputLogProfile = if (inputTransformOverridesBase) overlay.inputLogProfile else base.inputLogProfile,
            inputColorSpace = if (inputTransformOverridesBase) overlay.inputColorSpace else base.inputColorSpace,
            outputColorSpace = if (inputTransformOverridesBase) overlay.outputColorSpace else base.outputColorSpace,
            toneMappingMode = if (toneMappingOverridesBase) overlay.toneMappingMode else base.toneMappingMode,
            highlightRolloff = if (rolloffOverridesBase) overlay.highlightRolloff else base.highlightRolloff,
            qualEnabled = base.qualEnabled || (qualifier.enabled && qualifierAmount > 0f),
            qualHueCenter = if (hasQualifier && amount >= 0.5f) qualifier.hueCenter else base.qualHueCenter,
            qualHueWidth = if (hasQualifier && amount >= 0.5f) qualifier.hueWidth else base.qualHueWidth,
            qualHueFeather = if (hasQualifier && amount >= 0.5f) qualifier.hueFeather else base.qualHueFeather,
            qualSatMin = if (hasQualifier && amount >= 0.5f) qualifier.saturationMin else base.qualSatMin,
            qualSatMax = if (hasQualifier && amount >= 0.5f) qualifier.saturationMax else base.qualSatMax,
            qualSatFeather = if (hasQualifier && amount >= 0.5f) qualifier.saturationFeather else base.qualSatFeather,
            qualLumMin = if (hasQualifier && amount >= 0.5f) qualifier.luminanceMin else base.qualLumMin,
            qualLumMax = if (hasQualifier && amount >= 0.5f) qualifier.luminanceMax else base.qualLumMax,
            qualLumFeather = if (hasQualifier && amount >= 0.5f) qualifier.luminanceFeather else base.qualLumFeather,
            qualInvert = if (hasQualifier && amount >= 0.5f) qualifier.invertMask else base.qualInvert,
            qualShowMatte = if (qualifier.showMatte && amount >= 0.5f) true else base.qualShowMatte,
            qualHueShift = add(base.qualHueShift, qualifier.hueShift * qualifierAmount),
            qualSat = if (qualifierAmount > 0f) {
                (base.qualSat * (1f + (qualifier.saturation - 1f) * qualifierAmount)).coerceIn(0f, 2f)
            } else {
                base.qualSat
            },
            qualLum = add(base.qualLum, qualifier.luminance * qualifierAmount).coerceIn(-1f, 1f),
            qualContrast = add(base.qualContrast, qualifier.contrast * qualifierAmount).coerceIn(-1f, 1f),
            qualTemp = add(base.qualTemp, qualifier.temperature * qualifierAmount).coerceIn(-1f, 1f),
            qualTint = add(base.qualTint, qualifier.tint * qualifierAmount).coerceIn(-1f, 1f)
        )
    }

    /**
     * Updates the clip-level preview block when the regular grading controls change. Only values
     * changed by this grade are replaced; unrelated controls stored in the shared adjustment
     * model survive edits to curves, qualifier settings, or color ranges.
     */
    fun applyClipGradeChanges(
        base: VideoAdjustmentParams,
        previousGrade: ColorGradeStack,
        newGrade: ColorGradeStack
    ): VideoAdjustmentParams {
        val previous = if (previousGrade.enabled) previousGrade else previousGrade.copy(
            primaryCorrections = ColorGradeParams(),
            curves = CurveParams(),
            hslAdjustments = HslQualifierParams(),
            selectiveColor = SelectiveColorParams()
        )
        val next = if (newGrade.enabled) newGrade else newGrade.copy(
            primaryCorrections = ColorGradeParams(),
            curves = CurveParams(),
            hslAdjustments = HslQualifierParams(),
            selectiveColor = SelectiveColorParams()
        )
        val primary = PrimaryWheelAdjustmentMapper.applyChanges(
            base = base,
            previousGrade = previous.primaryCorrections,
            newGrade = next.primaryCorrections
        )

        fun chooseVector(existing: Vector3, oldValue: Vector3, newValue: Vector3): Vector3 = when {
            oldValue != newValue -> newValue
            existing == Vector3.ZERO && newValue != Vector3.ZERO -> newValue
            else -> existing
        }
        fun selectiveBands(params: com.example.model.colorgrade.SelectiveColorParams, field: Int): FloatArray {
            val ranges = listOf(
                params.red, params.orange, params.yellow, params.green,
                params.cyan, params.blue, params.purple, params.magenta
            )
            return FloatArray(8) { index ->
                val range = ranges[index]
                when (field) {
                    0 -> range.hueShift
                    1 -> range.saturation
                    else -> range.luminance
                }
            }
        }
        fun applyBands(existing: FloatArray?, oldValues: FloatArray, newValues: FloatArray): FloatArray? {
            val hasNewValue = newValues.any { it != 0f }
            return when {
                !oldValues.contentEquals(newValues) -> if (hasNewValue) newValues.copyOf() else null
                (existing == null || existing.none { it != 0f }) && hasNewValue -> newValues.copyOf()
                else -> existing
            }
        }
        fun chooseRange(
            existing: FloatArray,
            oldRange: ColorRangeParams,
            newRange: ColorRangeParams,
            defaultRange: FloatArray
        ): FloatArray {
            val oldHasRange = oldRange.lumaMin != 0f || oldRange.lumaMax != 1f
            val newHasRange = newRange.lumaMin != 0f || newRange.lumaMax != 1f
            if (oldHasRange != newHasRange || oldRange.lumaMin != newRange.lumaMin ||
                oldRange.lumaMax != newRange.lumaMax
            ) {
                return if (newHasRange) floatArrayOf(newRange.lumaMin, newRange.lumaMax)
                else defaultRange.copyOf()
            }
            if (existing.contentEquals(defaultRange) && newHasRange) {
                return floatArrayOf(newRange.lumaMin, newRange.lumaMax)
            }
            return existing
        }
        fun qualifierFrom(params: VideoAdjustmentParams) = HslQualifierParams(
            enabled = params.qualEnabled,
            hueCenter = params.qualHueCenter,
            hueWidth = params.qualHueWidth,
            hueFeather = params.qualHueFeather,
            saturationMin = params.qualSatMin,
            saturationMax = params.qualSatMax,
            saturationFeather = params.qualSatFeather,
            luminanceMin = params.qualLumMin,
            luminanceMax = params.qualLumMax,
            luminanceFeather = params.qualLumFeather,
            invertMask = params.qualInvert,
            showMatte = params.qualShowMatte,
            hueShift = params.qualHueShift,
            saturation = params.qualSat,
            luminance = params.qualLum,
            contrast = params.qualContrast,
            temperature = params.qualTemp,
            tint = params.qualTint
        )
        val oldQualifier = previous.hslAdjustments
        val newQualifier = next.hslAdjustments
        val currentQualifier = qualifierFrom(primary)
        val applyQualifier = oldQualifier != newQualifier ||
            (currentQualifier == HslQualifierParams() && newQualifier != HslQualifierParams())
        val oldCurves = previous.curves
        val newCurves = next.curves
        val curves = when {
            oldCurves != newCurves -> newCurves
            primary.curves == CurveParams() && newCurves != CurveParams() -> newCurves
            else -> primary.curves
        }
        val oldShadows = previous.primaryCorrections.shadows
        val newShadows = next.primaryCorrections.shadows
        val oldMidtones = previous.primaryCorrections.midtones
        val newMidtones = next.primaryCorrections.midtones
        val oldHighlights = previous.primaryCorrections.highlights
        val newHighlights = next.primaryCorrections.highlights
        val oldSelectiveHue = selectiveBands(previous.selectiveColor, 0)
        val newSelectiveHue = selectiveBands(next.selectiveColor, 0)
        val oldSelectiveSat = selectiveBands(previous.selectiveColor, 1)
        val newSelectiveSat = selectiveBands(next.selectiveColor, 1)
        val oldSelectiveLum = selectiveBands(previous.selectiveColor, 2)
        val newSelectiveLum = selectiveBands(next.selectiveColor, 2)

        return primary.copy(
            shadowColorBalance = chooseVector(
                primary.shadowColorBalance, rangeVector(oldShadows), rangeVector(newShadows)
            ),
            midtoneColorBalance = chooseVector(
                primary.midtoneColorBalance, rangeVector(oldMidtones), rangeVector(newMidtones)
            ),
            highlightColorBalance = chooseVector(
                primary.highlightColorBalance, rangeVector(oldHighlights), rangeVector(newHighlights)
            ),
            shadowLumaRange = chooseRange(
                primary.shadowLumaRange, oldShadows, newShadows, VideoAdjustmentParams.default().shadowLumaRange
            ),
            highlightLumaRange = chooseRange(
                primary.highlightLumaRange, oldHighlights, newHighlights,
                VideoAdjustmentParams.default().highlightLumaRange
            ),
            hslHue = applyBands(primary.hslHue, oldSelectiveHue, newSelectiveHue),
            hslSat = applyBands(primary.hslSat, oldSelectiveSat, newSelectiveSat),
            hslLum = applyBands(primary.hslLum, oldSelectiveLum, newSelectiveLum),
            qualEnabled = if (applyQualifier) newQualifier.enabled else primary.qualEnabled,
            qualHueCenter = if (applyQualifier) newQualifier.hueCenter else primary.qualHueCenter,
            qualHueWidth = if (applyQualifier) newQualifier.hueWidth else primary.qualHueWidth,
            qualHueFeather = if (applyQualifier) newQualifier.hueFeather else primary.qualHueFeather,
            qualSatMin = if (applyQualifier) newQualifier.saturationMin else primary.qualSatMin,
            qualSatMax = if (applyQualifier) newQualifier.saturationMax else primary.qualSatMax,
            qualSatFeather = if (applyQualifier) newQualifier.saturationFeather else primary.qualSatFeather,
            qualLumMin = if (applyQualifier) newQualifier.luminanceMin else primary.qualLumMin,
            qualLumMax = if (applyQualifier) newQualifier.luminanceMax else primary.qualLumMax,
            qualLumFeather = if (applyQualifier) newQualifier.luminanceFeather else primary.qualLumFeather,
            qualInvert = if (applyQualifier) newQualifier.invertMask else primary.qualInvert,
            qualShowMatte = if (applyQualifier) newQualifier.showMatte else primary.qualShowMatte,
            qualHueShift = if (applyQualifier) newQualifier.hueShift else primary.qualHueShift,
            qualSat = if (applyQualifier) newQualifier.saturation else primary.qualSat,
            qualLum = if (applyQualifier) newQualifier.luminance else primary.qualLum,
            qualContrast = if (applyQualifier) newQualifier.contrast else primary.qualContrast,
            qualTemp = if (applyQualifier) newQualifier.temperature else primary.qualTemp,
            qualTint = if (applyQualifier) newQualifier.tint else primary.qualTint,
            curves = curves
        )
    }

    /**
     * Supplies clip-grade values that were saved without their shared adjustment mirror (for
     * example, older projects created before the Media3 preview mapping existed). If a value is
     * already the current grade's mirror, refresh it from the frame-evaluated grade so keyframes
     * work. Preserve a non-neutral adjustment value that does not match the grade; it may be an
     * independent adjustment authored through the basic color controls.
     */
    fun applyClipGradeIfNotMirrored(
        base: VideoAdjustmentParams,
        sourceGrade: ColorGradeStack,
        frame: Long
    ): VideoAdjustmentParams {
        if (!sourceGrade.enabled || isNeutral(sourceGrade)) return base

        val defaults = VideoAdjustmentParams.default()
        val staticParams = applyGrade(
            defaults,
            sourceGrade.copy(keyframes = emptyList())
        )
        val evaluatedParams = applyGrade(defaults, sourceGrade, frame = frame)

        fun <T> mergeValue(current: T, staticValue: T, evaluatedValue: T, neutralValue: T): T =
            if (current == neutralValue || current == staticValue) evaluatedValue else current

        fun sameArray(left: FloatArray?, right: FloatArray?): Boolean = when {
            left == null -> right == null
            right == null -> false
            else -> left.contentEquals(right)
        }

        fun isNeutralArray(value: FloatArray?, neutralValue: FloatArray?): Boolean =
            sameArray(value, neutralValue) ||
                (neutralValue == null && value != null && value.all { it == 0f })

        fun mergeArray(
            current: FloatArray?,
            staticValue: FloatArray?,
            evaluatedValue: FloatArray?,
            neutralValue: FloatArray?
        ): FloatArray? = if (
            isNeutralArray(current, neutralValue) || sameArray(current, staticValue)
        ) {
            evaluatedValue?.copyOf()
        } else {
            current
        }

        return base.copy(
            exposureStops = mergeValue(
                base.exposureStops, staticParams.exposureStops, evaluatedParams.exposureStops, defaults.exposureStops
            ),
            contrast = mergeValue(base.contrast, staticParams.contrast, evaluatedParams.contrast, defaults.contrast),
            saturation = mergeValue(base.saturation, staticParams.saturation, evaluatedParams.saturation, defaults.saturation),
            vibrance = mergeValue(base.vibrance, staticParams.vibrance, evaluatedParams.vibrance, defaults.vibrance),
            temperature = mergeValue(base.temperature, staticParams.temperature, evaluatedParams.temperature, defaults.temperature),
            tint = mergeValue(base.tint, staticParams.tint, evaluatedParams.tint, defaults.tint),
            lift = mergeValue(base.lift, staticParams.lift, evaluatedParams.lift, defaults.lift),
            gamma = mergeValue(base.gamma, staticParams.gamma, evaluatedParams.gamma, defaults.gamma),
            gain = mergeValue(base.gain, staticParams.gain, evaluatedParams.gain, defaults.gain),
            offsetWheel = mergeValue(base.offsetWheel, staticParams.offsetWheel, evaluatedParams.offsetWheel, defaults.offsetWheel),
            skinToneProtection = mergeValue(
                base.skinToneProtection, staticParams.skinToneProtection,
                evaluatedParams.skinToneProtection, defaults.skinToneProtection
            ),
            skinToneHueCenter = mergeValue(
                base.skinToneHueCenter, staticParams.skinToneHueCenter,
                evaluatedParams.skinToneHueCenter, defaults.skinToneHueCenter
            ),
            skinToneHueWidth = mergeValue(
                base.skinToneHueWidth, staticParams.skinToneHueWidth,
                evaluatedParams.skinToneHueWidth, defaults.skinToneHueWidth
            ),
            shadowColorBalance = mergeValue(
                base.shadowColorBalance, staticParams.shadowColorBalance,
                evaluatedParams.shadowColorBalance, defaults.shadowColorBalance
            ),
            midtoneColorBalance = mergeValue(
                base.midtoneColorBalance, staticParams.midtoneColorBalance,
                evaluatedParams.midtoneColorBalance, defaults.midtoneColorBalance
            ),
            highlightColorBalance = mergeValue(
                base.highlightColorBalance, staticParams.highlightColorBalance,
                evaluatedParams.highlightColorBalance, defaults.highlightColorBalance
            ),
            shadowLumaRange = mergeArray(
                base.shadowLumaRange, staticParams.shadowLumaRange,
                evaluatedParams.shadowLumaRange, defaults.shadowLumaRange
            ) ?: defaults.shadowLumaRange,
            highlightLumaRange = mergeArray(
                base.highlightLumaRange, staticParams.highlightLumaRange,
                evaluatedParams.highlightLumaRange, defaults.highlightLumaRange
            ) ?: defaults.highlightLumaRange,
            hslHue = mergeArray(base.hslHue, staticParams.hslHue, evaluatedParams.hslHue, defaults.hslHue),
            hslSat = mergeArray(base.hslSat, staticParams.hslSat, evaluatedParams.hslSat, defaults.hslSat),
            hslLum = mergeArray(base.hslLum, staticParams.hslLum, evaluatedParams.hslLum, defaults.hslLum),
            curves = mergeValue(base.curves, staticParams.curves, evaluatedParams.curves, defaults.curves),
            lutId = mergeValue(base.lutId, staticParams.lutId, evaluatedParams.lutId, defaults.lutId),
            lutIntensity = mergeValue(
                base.lutIntensity, staticParams.lutIntensity,
                evaluatedParams.lutIntensity, defaults.lutIntensity
            ),
            inputLogProfile = mergeValue(
                base.inputLogProfile, staticParams.inputLogProfile,
                evaluatedParams.inputLogProfile, defaults.inputLogProfile
            ),
            inputColorSpace = mergeValue(
                base.inputColorSpace, staticParams.inputColorSpace,
                evaluatedParams.inputColorSpace, defaults.inputColorSpace
            ),
            outputColorSpace = mergeValue(
                base.outputColorSpace, staticParams.outputColorSpace,
                evaluatedParams.outputColorSpace, defaults.outputColorSpace
            ),
            toneMappingMode = mergeValue(
                base.toneMappingMode, staticParams.toneMappingMode,
                evaluatedParams.toneMappingMode, defaults.toneMappingMode
            ),
            highlightRolloff = mergeValue(
                base.highlightRolloff, staticParams.highlightRolloff,
                evaluatedParams.highlightRolloff, defaults.highlightRolloff
            ),
            qualEnabled = mergeValue(
                base.qualEnabled, staticParams.qualEnabled, evaluatedParams.qualEnabled, defaults.qualEnabled
            ),
            qualHueCenter = mergeValue(
                base.qualHueCenter, staticParams.qualHueCenter, evaluatedParams.qualHueCenter, defaults.qualHueCenter
            ),
            qualHueWidth = mergeValue(
                base.qualHueWidth, staticParams.qualHueWidth, evaluatedParams.qualHueWidth, defaults.qualHueWidth
            ),
            qualHueFeather = mergeValue(
                base.qualHueFeather, staticParams.qualHueFeather,
                evaluatedParams.qualHueFeather, defaults.qualHueFeather
            ),
            qualSatMin = mergeValue(base.qualSatMin, staticParams.qualSatMin, evaluatedParams.qualSatMin, defaults.qualSatMin),
            qualSatMax = mergeValue(base.qualSatMax, staticParams.qualSatMax, evaluatedParams.qualSatMax, defaults.qualSatMax),
            qualSatFeather = mergeValue(
                base.qualSatFeather, staticParams.qualSatFeather,
                evaluatedParams.qualSatFeather, defaults.qualSatFeather
            ),
            qualLumMin = mergeValue(base.qualLumMin, staticParams.qualLumMin, evaluatedParams.qualLumMin, defaults.qualLumMin),
            qualLumMax = mergeValue(base.qualLumMax, staticParams.qualLumMax, evaluatedParams.qualLumMax, defaults.qualLumMax),
            qualLumFeather = mergeValue(
                base.qualLumFeather, staticParams.qualLumFeather,
                evaluatedParams.qualLumFeather, defaults.qualLumFeather
            ),
            qualInvert = mergeValue(base.qualInvert, staticParams.qualInvert, evaluatedParams.qualInvert, defaults.qualInvert),
            qualShowMatte = mergeValue(
                base.qualShowMatte, staticParams.qualShowMatte,
                evaluatedParams.qualShowMatte, defaults.qualShowMatte
            ),
            qualHueShift = mergeValue(
                base.qualHueShift, staticParams.qualHueShift, evaluatedParams.qualHueShift, defaults.qualHueShift
            ),
            qualSat = mergeValue(base.qualSat, staticParams.qualSat, evaluatedParams.qualSat, defaults.qualSat),
            qualLum = mergeValue(base.qualLum, staticParams.qualLum, evaluatedParams.qualLum, defaults.qualLum),
            qualContrast = mergeValue(
                base.qualContrast, staticParams.qualContrast, evaluatedParams.qualContrast, defaults.qualContrast
            ),
            qualTemp = mergeValue(base.qualTemp, staticParams.qualTemp, evaluatedParams.qualTemp, defaults.qualTemp),
            qualTint = mergeValue(base.qualTint, staticParams.qualTint, evaluatedParams.qualTint, defaults.qualTint)
        )
    }

    private fun isNeutral(grade: ColorGradeStack): Boolean =
        grade.primaryCorrections == ColorGradeParams() &&
            grade.curves == CurveParams() &&
            grade.hslAdjustments == HslQualifierParams() &&
            grade.selectiveColor == SelectiveColorParams() &&
            grade.lutStack == LutGradeParams() &&
            grade.inputTransform == LogTransformParams() &&
            grade.colorMatch == ColorMatchParams() &&
            grade.skinToneProtection == SkinToneProtectionParams() &&
            grade.hdrToneMapping == HdrToneMappingParams() &&
            grade.keyframes.isEmpty()

    private fun toAdjustmentParams(grade: ColorGradeStack): VideoAdjustmentParams {
        val defaults = VideoAdjustmentParams.default()
        val primary = PrimaryWheelAdjustmentMapper.apply(defaults, grade.primaryCorrections)
        val selective = grade.selectiveColor
        val qualifier: HslQualifierParams = grade.hslAdjustments
        val shadows = grade.primaryCorrections.shadows
        val midtones = grade.primaryCorrections.midtones
        val highlights = grade.primaryCorrections.highlights
        val logProfile = mapLogProfile(grade.inputTransform)
        val inputColorSpace = mapColorSpace(grade.inputTransform.inputColorSpace)
        val outputColorSpace = mapColorSpace(grade.inputTransform.outputColorSpace)
        val gradeToneMapping = mapToneMapping(grade.inputTransform.toneMappingMode)
        val hdrToneMapping = if (grade.hdrToneMapping.enabled) {
            mapToneMapping(grade.hdrToneMapping.mappingAlgorithm)
        } else {
            ToneMappingMode.NONE
        }
        val lutId = grade.lutStack.lutId.takeIf { grade.lutStack.enabled }
        val skinTone = grade.skinToneProtection

        return primary.copy(
            shadowColorBalance = rangeVector(shadows),
            midtoneColorBalance = rangeVector(midtones),
            highlightColorBalance = rangeVector(highlights),
            shadowLumaRange = if (shadows.lumaMin != 0f || shadows.lumaMax != 1f) {
                floatArrayOf(shadows.lumaMin, shadows.lumaMax)
            } else {
                defaults.shadowLumaRange
            },
            highlightLumaRange = if (highlights.lumaMin != 0f || highlights.lumaMax != 1f) {
                floatArrayOf(highlights.lumaMin, highlights.lumaMax)
            } else {
                defaults.highlightLumaRange
            },
            hslHue = floatArrayOf(
                selective.red.hueShift, selective.orange.hueShift, selective.yellow.hueShift,
                selective.green.hueShift, selective.cyan.hueShift, selective.blue.hueShift,
                selective.purple.hueShift, selective.magenta.hueShift
            ),
            hslSat = floatArrayOf(
                selective.red.saturation, selective.orange.saturation, selective.yellow.saturation,
                selective.green.saturation, selective.cyan.saturation, selective.blue.saturation,
                selective.purple.saturation, selective.magenta.saturation
            ),
            hslLum = floatArrayOf(
                selective.red.luminance, selective.orange.luminance, selective.yellow.luminance,
                selective.green.luminance, selective.cyan.luminance, selective.blue.luminance,
                selective.purple.luminance, selective.magenta.luminance
            ),
            qualEnabled = qualifier.enabled,
            qualHueCenter = qualifier.hueCenter,
            qualHueWidth = qualifier.hueWidth,
            qualHueFeather = qualifier.hueFeather,
            qualSatMin = qualifier.saturationMin,
            qualSatMax = qualifier.saturationMax,
            qualSatFeather = qualifier.saturationFeather,
            qualLumMin = qualifier.luminanceMin,
            qualLumMax = qualifier.luminanceMax,
            qualLumFeather = qualifier.luminanceFeather,
            qualInvert = qualifier.invertMask,
            qualShowMatte = qualifier.showMatte,
            qualHueShift = qualifier.hueShift,
            qualSat = qualifier.saturation,
            qualLum = qualifier.luminance,
            qualContrast = qualifier.contrast,
            qualTemp = qualifier.temperature,
            qualTint = qualifier.tint,
            curves = grade.curves,
            lutId = lutId,
            lutIntensity = grade.lutStack.intensity,
            skinToneProtection = if (skinTone.enabled) skinTone.strength else 0f,
            skinToneHueWidth = skinTone.hueRange,
            inputLogProfile = logProfile,
            inputColorSpace = inputColorSpace,
            outputColorSpace = outputColorSpace,
            toneMappingMode = if (hdrToneMapping != ToneMappingMode.NONE) hdrToneMapping else gradeToneMapping,
            highlightRolloff = if (grade.hdrToneMapping.enabled) grade.hdrToneMapping.knee else grade.inputTransform.highlightRolloff
        )
    }

    private fun rangeVector(params: ColorRangeParams): Vector3 = Vector3(
        (params.r + params.luma).coerceIn(-1f, 1f),
        (params.g + params.luma).coerceIn(-1f, 1f),
        (params.b + params.luma).coerceIn(-1f, 1f)
    )

    private fun mapLogProfile(transform: LogTransformParams): LogProfile {
        val value = if (transform.logProfile != "None") transform.logProfile else transform.inputGamma
        return when (value.lowercase().replace("-", "").replace(" ", "")) {
            "slog2" -> LogProfile.SLOG2
            "slog3" -> LogProfile.SLOG3
            "clog" -> LogProfile.CLOG
            "clog2" -> LogProfile.CLOG2
            "clog3" -> LogProfile.CLOG3
            "vlog" -> LogProfile.VLOG
            "logc" -> LogProfile.LOGC
            "nlog" -> LogProfile.NLOG
            "dlog" -> LogProfile.DLOG
            "dlogm", "djidlogm" -> LogProfile.DLOG_M
            "bmdfilm", "blackmagicfilm" -> LogProfile.BMD_FILM
            "rec.2020", "rec2020" -> LogProfile.REC_2020
            else -> LogProfile.NONE
        }
    }

    private fun mapColorSpace(value: String): ColorSpaceProfile = when (
        value.lowercase().replace(" ", "").replace(".", "")
    ) {
        "rec2020", "sgamut3cine" -> ColorSpaceProfile.REC_2020
        "rec2020linear" -> ColorSpaceProfile.REC_2020_LINEAR
        "dci-p3", "dcip3" -> ColorSpaceProfile.DCI_P3
        "acesap0" -> ColorSpaceProfile.ACES_AP0
        "acesap1" -> ColorSpaceProfile.ACES_AP1
        "hlg" -> ColorSpaceProfile.HLG
        "pq", "st2084" -> ColorSpaceProfile.PQ
        "srgb" -> ColorSpaceProfile.SRGB
        else -> ColorSpaceProfile.REC_709
    }

    private fun mapToneMapping(value: String): ToneMappingMode = when (value.lowercase().replace(" ", "")) {
        "simple", "reinhard" -> ToneMappingMode.SIMPLE
        "filmic", "aces" -> ToneMappingMode.FILMIC
        "highlightrolloff", "rolloff" -> ToneMappingMode.HIGHLIGHT_ROLLOFF
        "preservesaturation" -> ToneMappingMode.PRESERVE_SATURATION
        else -> ToneMappingMode.NONE
    }

    private fun blendBands(base: FloatArray, overlay: FloatArray, amount: Float): FloatArray =
        FloatArray(8) { index ->
            val baseValue = base.getOrElse(index) { 0f }
            val overlayValue = overlay.getOrElse(index) { 0f }
            (baseValue + overlayValue * amount).coerceIn(-1f, 1f)
        }

    private fun blendCurves(base: CurveParams, overlay: CurveParams, amount: Float): CurveParams {
        if (overlay == CurveParams() || amount <= 0f) return base
        return CurveParams(
            masterPoints = composeCurve(base.masterPoints, overlay.masterPoints, CurveParams().masterPoints, amount),
            redPoints = composeCurve(base.redPoints, overlay.redPoints, CurveParams().redPoints, amount),
            greenPoints = composeCurve(base.greenPoints, overlay.greenPoints, CurveParams().greenPoints, amount),
            bluePoints = composeCurve(base.bluePoints, overlay.bluePoints, CurveParams().bluePoints, amount),
            lumaPoints = composeCurve(base.lumaPoints, overlay.lumaPoints, CurveParams().lumaPoints, amount),
            hueVsHuePoints = composeCurve(base.hueVsHuePoints, overlay.hueVsHuePoints, CurveParams().hueVsHuePoints, amount),
            hueVsSatPoints = composeCurve(base.hueVsSatPoints, overlay.hueVsSatPoints, CurveParams().hueVsSatPoints, amount),
            hueVsLumaPoints = composeCurve(base.hueVsLumaPoints, overlay.hueVsLumaPoints, CurveParams().hueVsLumaPoints, amount),
            satVsSatPoints = composeCurve(base.satVsSatPoints, overlay.satVsSatPoints, CurveParams().satVsSatPoints, amount),
            lumaVsSatPoints = composeCurve(base.lumaVsSatPoints, overlay.lumaVsSatPoints, CurveParams().lumaVsSatPoints, amount)
        )
    }

    private fun composeCurve(
        baseInput: List<CurvePoint>,
        overlayInput: List<CurvePoint>,
        neutral: List<CurvePoint>,
        amount: Float
    ): List<CurvePoint> {
        if (overlayInput == neutral) return baseInput

        fun normalized(points: List<CurvePoint>): List<CurvePoint> {
            val ordered = points.asSequence()
                .filter { it.x.isFinite() && it.y.isFinite() }
                .map { CurvePoint(it.x.coerceIn(0f, 1f), it.y.coerceIn(0f, 1f)) }
                .sortedBy { it.x }
                .toList()
            if (ordered.isEmpty()) return neutral
            val unique = ArrayList<CurvePoint>(ordered.size)
            ordered.forEach { point ->
                if (unique.lastOrNull()?.x == point.x) unique[unique.lastIndex] = point
                else unique += point
            }
            return unique
        }

        fun evaluate(points: List<CurvePoint>, input: Float): Float {
            val x = input.coerceIn(0f, 1f)
            if (points.size == 1) return points.single().y
            var left = points.first()
            if (x <= left.x) return left.y
            for (index in 1 until points.size) {
                val right = points[index]
                if (x <= right.x) {
                    val t = (x - left.x) / (right.x - left.x).coerceAtLeast(0.0001f)
                    return left.y + (right.y - left.y) * t
                }
                left = right
            }
            return points.last().y
        }

        val base = normalized(baseInput)
        val overlay = normalized(overlayInput)
        val knots = (listOf(0f, 1f) + base.map { it.x } + overlay.map { it.x })
            .distinct()
            .sorted()
        val boundedKnots = if (knots.size <= 256) knots else List(256) { index ->
            knots[index * knots.lastIndex / 255]
        }
        return boundedKnots.map { x ->
            val baseValue = evaluate(base, x)
            val overlayValue = evaluate(overlay, baseValue)
            CurvePoint(x, (baseValue + (overlayValue - baseValue) * amount).coerceIn(0f, 1f))
        }
    }
}
