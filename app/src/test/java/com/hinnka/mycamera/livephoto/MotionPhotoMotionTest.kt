package com.hinnka.mycamera.livephoto

import com.hinnka.mycamera.data.UserPreferences
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import kotlin.random.Random

class MotionPhotoMotionTest {
    @Test fun stabilizationDefaultsOff() {
        assertFalse(UserPreferences().stabilizeMotionPhoto)
    }

    @Test fun estimatesSyntheticBackgroundTranslation() {
        val width = 256
        val height = 144
        val random = Random(42)
        val first = ByteArray(width * height) { random.nextInt(256).toByte() }
        val second = ByteArray(width * height)
        for (y in 3 until height - 3) for (x in 4 until width - 4)
            second[(y + 3) * width + x - 4] = first[y * width + x]
        val shift = MotionPhotoMotion.estimate(first, second, width, height)
        assertNotNull(shift)
        assertEquals(-4f, shift!!.x, 0.5f)
        assertEquals(3f, shift.y, 0.5f)
    }

    @Test fun flatSceneFallsBack() {
        assertNull(MotionPhotoMotion.estimate(ByteArray(256 * 144), ByteArray(256 * 144), 256, 144))
    }

    @Test fun smoothingAndTimestampMappingAreBounded() {
        val corrections = MotionPhotoMotion.corrections(
            listOf(
                MotionPhotoMotion.Shift(3f, 0f), MotionPhotoMotion.Shift(-3f, 0f),
                MotionPhotoMotion.Shift(3f, 0f), MotionPhotoMotion.Shift(-3f, 0f),
            ), 256, 144
        )
        assertNotNull(corrections)
        assertTrue(corrections!!.any { it.x != 0f })
        assertEquals(1_500_000L, MotionPhotoMotion.mapPrimaryTimestamp(1_510_000L, 10_000L))
        assertNull(MotionPhotoMotion.corrections(List(8) { MotionPhotoMotion.Shift(30f, 0f) }, 256, 144))
    }
}
