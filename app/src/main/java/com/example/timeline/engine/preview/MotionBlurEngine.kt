package com.example.timeline.engine.preview

import com.example.timeline.core.transform.ClipTransform
import com.example.timeline.core.transform.MotionBlurParams

object MotionBlurEngine {
    
    init {
        try {
            System.loadLibrary("transform_engine")
        } catch (e: UnsatisfiedLinkError) {
            // Error loading native library
        }
    }

    external fun nativeComputeMotionVector(
        prevX: Float, prevY: Float,
        currX: Float, currY: Float,
        nextX: Float, nextY: Float
    ): FloatArray

    external fun nativeComputeMotionBlurSamples(
        sampleCount: Int,
        shutterAngle: Float,
        strength: Float
    ): FloatArray

    external fun nativeApplyTransformMotionBlurRgba8888(
        outPixels: ByteArray,
        inPixels: ByteArray,
        width: Int,
        height: Int,
        currX: Float, currY: Float, currRot: Float, currScaleX: Float, currScaleY: Float,
        prevX: Float, prevY: Float, prevRot: Float, prevScaleX: Float, prevScaleY: Float,
        nextX: Float, nextY: Float, nextRot: Float, nextScaleX: Float, nextScaleY: Float,
        anchorX: Float, anchorY: Float,
        shutterAngle: Float,
        sampleCount: Int,
        strength: Float
    )
}
