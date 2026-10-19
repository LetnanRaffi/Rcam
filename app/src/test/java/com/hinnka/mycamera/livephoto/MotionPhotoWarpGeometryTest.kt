package com.hinnka.mycamera.livephoto

import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class MotionPhotoWarpGeometryTest {
    @Test fun fixedCropContainsEveryWarpedCorner() {
        val corrections = listOf(
            MotionPhotoMotion.Shift(0f, 0f),
            MotionPhotoMotion.Shift(9f, -4f, 0.07f),
            MotionPhotoMotion.Shift(-6f, 5f, -0.05f),
        )
        val crop = MotionPhotoWarpGeometry.cropScale(corrections, 1280, 720, 144)
        assertTrue(crop in 0.72f..1f)
        assertTrue(corrections.all { MotionPhotoWarpGeometry.fits(it, 1280, 720, 144, crop - 0.0001f) })
        assertFalse(MotionPhotoWarpGeometry.fits(corrections[1], 1280, 720, 144, 1f))
    }
}
