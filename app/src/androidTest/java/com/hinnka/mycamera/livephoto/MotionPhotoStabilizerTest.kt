package com.hinnka.mycamera.livephoto

import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import java.io.File
import kotlinx.coroutines.runBlocking
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertSame
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith

@RunWith(AndroidJUnit4::class)
class MotionPhotoStabilizerTest {
    @Test fun invalidMp4FallsBackAndCleansTemporaryFiles() = runBlocking {
        val folder = File(InstrumentationRegistry.getInstrumentation().targetContext.cacheDir, "motion_test")
        folder.mkdirs()
        val input = File(folder, "invalid.mp4")
        input.writeText("not an MP4")
        try {
            val result = MotionPhotoStabilizer.stabilize(input, 123_456L)
            assertSame(input, result.outputFile)
            assertFalse(result.wasStabilized)
            assertEquals(123_456L, result.primaryTimestampUs)
            assertTrue(input.exists())
            assertEquals(listOf(input.name), folder.list()?.sorted())
        } finally {
            folder.listFiles()?.forEach { it.delete() }
            folder.delete()
        }
    }
}
