package com.hinnka.mycamera.livephoto

import java.nio.file.Files
import org.junit.Assert.assertArrayEquals
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class MotionPhotoPackagingTest {
    @Test fun writesPrimaryTimestampAndUnchangedClip() {
        val folder = Files.createTempDirectory("motion-packaging").toFile()
        try {
            val jpeg = folder.resolve("still.jpg")
            val video = folder.resolve("clip.mp4")
            val output = folder.resolve("motion.jpg")
            val extracted = folder.resolve("extracted.mp4")
            jpeg.writeBytes(byteArrayOf(0xff.toByte(), 0xd8.toByte(), 0xff.toByte(), 0xd9.toByte()))
            val videoBytes = ByteArray(256) { it.toByte() }
            video.writeBytes(videoBytes)
            assertTrue(GoogleLivePhotoCreator().create(
                jpeg.absolutePath, video.absolutePath, output.absolutePath, 1_234_567L
            ))
            assertEquals(1_234_567L, MotionPhotoWriter.getPresentationTimestampUs(output.absolutePath))
            assertEquals(videoBytes.size.toLong(), MotionPhotoWriter.getVideoLength(output.absolutePath))
            assertTrue(MotionPhotoWriter.extractVideo(output.absolutePath, extracted.absolutePath))
            assertArrayEquals(videoBytes, extracted.readBytes())
            val packed = output.readBytes()
            assertArrayEquals(videoBytes, packed.copyOfRange(packed.size - videoBytes.size, packed.size))
        } finally {
            folder.listFiles()?.forEach { it.delete() }
            folder.delete()
        }
    }
}
