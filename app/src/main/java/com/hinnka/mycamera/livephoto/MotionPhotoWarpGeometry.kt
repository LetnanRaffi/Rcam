package com.hinnka.mycamera.livephoto

import kotlin.math.abs
import kotlin.math.min

/** A single, fixed crop for the clip avoids both exposed borders and zoom pumping. */
internal object MotionPhotoWarpGeometry {
    fun cropScale(corrections: List<MotionPhotoMotion.Shift>, width: Int, height: Int, analysisHeight: Int): Float {
        require(width > 0 && height > 0 && analysisHeight > 0 && corrections.isNotEmpty())
        var crop = 1f
        for (correction in corrections) {
            require(correction.scale > 0f)
            var low = 0f
            var high = crop
            repeat(18) {
                val middle = (low + high) * 0.5f
                if (fits(correction, width, height, analysisHeight, middle)) low = middle else high = middle
            }
            crop = min(crop, low)
        }
        return crop
    }

    fun fits(correction: MotionPhotoMotion.Shift, width: Int, height: Int, analysisHeight: Int, crop: Float): Boolean {
        val inverse = correction.inverseLinear() ?: return false
        // Keep a two-pixel safety margin for bilinear filtering and driver rounding.
        val halfWidth = width * 0.5f - 2f
        val halfHeight = height * 0.5f - 2f
        for (cornerY in listOf(-1f, 1f)) for (cornerX in listOf(-1f, 1f)) {
            val x = cornerX * width * 0.5f * crop - correction.x * width / 256f
            val y = cornerY * height * 0.5f * crop - correction.y * height / analysisHeight
            val sourceX = inverse[0] * x + inverse[1] * y
            val sourceY = inverse[2] * x + inverse[3] * y
            if (abs(sourceX) > halfWidth || abs(sourceY) > halfHeight) return false
        }
        return true
    }
}
