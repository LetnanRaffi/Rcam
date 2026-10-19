package com.hinnka.mycamera.livephoto

import com.hinnka.mycamera.data.UserPreferences
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import kotlin.math.abs
import kotlin.math.cos
import kotlin.math.sin
import kotlin.random.Random

class MotionPhotoMotionTest {
    @Test fun stabilizationDefaultsOff() {
        assertTrue(UserPreferences().stabilizeMotionPhoto)
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
        val pan = MotionPhotoMotion.corrections(List(8) { MotionPhotoMotion.Shift(3f, 0f) }, 256, 144)
        assertNotNull(pan)
        assertTrue(pan!!.drop(2).dropLast(2).all { abs(it.x) < 0.5f })
    }

    @Test fun robustSimilarityRejectsMovingForeground() {
        val width = 256
        val height = 144
        val random = Random(17)
        val raw = ByteArray(width * height) { random.nextInt(256).toByte() }
        val texture = ByteArray(raw.size)
        for (y in 2 until height - 2) for (x in 2 until width - 2) {
            var sum = 0
            for (dy in -2..2) for (dx in -2..2)
                sum += raw[(y + dy) * width + x + dx].toInt() and 255
            texture[y * width + x] = (sum / 25).toByte()
        }
        val background = render(texture, width, height, 2.0, -1.0, 0.035)
        // A moving subject occupying a small area must not redirect the global camera estimate.
        for (y in 55 until 90) for (x in 103 until 143) {
            background[y * width + x] = texture[y * width + x - 7]
        }
        val motion = MotionPhotoMotion.estimate(texture, background, width, height)
        assertNotNull(motion)
        assertTrue(abs(motion!!.rotationRadians - 0.035f) < 0.02f)
        assertTrue(abs(motion.x - 2f) < 2f)
        assertTrue(motion.inlierRatio > 0.4f)
    }

    @Test fun constrainedPathRemovesJitterButKeepsPanAndAnchor() {
        val jitter = listOf(3f, -3f, 3f, -3f, 3f, -3f, 3f, -3f, 3f, -3f)
        val motions = jitter.map { MotionPhotoMotion.Shift(1.5f + it, 0f) }
        val correction = MotionPhotoMotion.corrections(motions, 256, 144, primaryFrame = 5)
        assertNotNull(correction)
        assertEquals(0f, correction!![5].x, 1e-4f)
        assertTrue(correction.any { abs(it.x) > 1f })
        assertTrue(correction.all { abs(it.x) <= 25.6f })
        val cuts = MotionPhotoMotion.corrections(motions, 256, 144,
            primaryFrame = 5, cuts = setOf(7))
        assertNotNull(cuts)
        assertEquals(0f, cuts!![7].x, 1e-4f)
    }

    @Test fun syntheticGroundTruthHighFrequencyJitterDropsByHalf() {
        val panPerFrame = 1.5f
        val shake = FloatArray(31) { index -> if (index % 2 == 0) 2.5f else -2.5f }
        val observed = FloatArray(shake.size) { index -> index * panPerFrame + shake[index] }
        val steps = (1 until observed.size).map { MotionPhotoMotion.Shift(observed[it] - observed[it - 1], 0f) }
        val correction = MotionPhotoMotion.corrections(steps, 256, 144, primaryFrame = 15)
        assertNotNull(correction)
        val stabilized = FloatArray(observed.size) { observed[it] + correction!![it].x }
        val before = jitterResidual(observed, panPerFrame)
        val after = jitterResidual(stabilized, panPerFrame)
        println("MotionPhoto synthetic ground truth residual: before=$before after=$after")
        assertTrue("high-frequency jitter reduction", after < before * 0.5f)
        assertEquals(0f, correction!![15].x, 1e-4f)
    }

    private fun jitterResidual(path: FloatArray, intendedPan: Float): Float =
        (1 until path.size).map { abs((path[it] - path[it - 1]) - intendedPan) }.average().toFloat()

    private fun render(source: ByteArray, width: Int, height: Int,
                       tx: Double, ty: Double, radians: Double): ByteArray {
        val output = ByteArray(source.size)
        val c = cos(radians)
        val s = sin(radians)
        val cx = width / 2.0
        val cy = height / 2.0
        for (y in 0 until height) for (x in 0 until width) {
            val u = x - cx - tx
            val v = y - cy - ty
            val sx = (c * u + s * v + cx).toInt()
            val sy = (-s * u + c * v + cy).toInt()
            if (sx in 0 until width && sy in 0 until height)
                output[y * width + x] = source[sy * width + sx]
        }
        return output
    }
}
