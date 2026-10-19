package com.hinnka.mycamera.livephoto

import android.media.MediaCodec
import android.media.MediaCodecInfo
import android.media.MediaExtractor
import android.media.MediaFormat
import android.media.MediaMuxer
import android.os.SystemClock
import android.view.Surface
import com.hinnka.mycamera.utils.PLog
import java.io.File
import java.nio.ByteBuffer
import kotlin.math.abs
import kotlin.math.max
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.currentCoroutineContext
import kotlinx.coroutines.ensureActive
import kotlinx.coroutines.withContext

/** Processes only the temporary MP4; the caller owns both input and any successful output. */
internal object MotionPhotoStabilizer {
    private const val TAG = "MotionPhotoStabilizer"
    private const val TIME_LIMIT_MS = 60_000L
    private const val MAX_FRAMES = 180
    private const val ANALYSIS_WIDTH = 256

    enum class Status { STABILIZED_GLOBAL, REDUCED_MODEL, ALREADY_STABLE, ORIGINAL_FALLBACK }

    data class Result(
        val outputFile: File,
        val primaryTimestampUs: Long,
        val wasStabilized: Boolean,
        val reason: String? = null,
        val status: Status = if (wasStabilized) Status.STABILIZED_GLOBAL else Status.ORIGINAL_FALLBACK,
    )

    private data class Analysis(
        val corrections: List<MotionPhotoMotion.Shift>,
        val firstPtsUs: Long,
        val lastPtsUs: Long,
        val width: Int,
        val height: Int,
        val analysisHeight: Int,
        val rotation: Int,
        val hadAudio: Boolean,
        val framePtsUs: List<Long>,
        val cropScale: Float,
        val unreliablePairs: Int,
    )

    suspend fun stabilize(input: File, primaryTimestampUs: Long): Result = withContext(Dispatchers.IO) {
        val start = SystemClock.elapsedRealtime()
        var output: File? = null
        var videoOnly: File? = null
        try {
            require(input.isFile && input.length() > 0) { "empty input" }
            val outputFile = File.createTempFile("stabilized_motion_", ".mp4", input.parentFile)
            output = outputFile
            val videoOnlyFile = File.createTempFile("motion_video_", ".mp4", input.parentFile)
            videoOnly = videoOnlyFile
            val analysis = analyze(input, primaryTimestampUs, start)
            encode(input, videoOnlyFile, analysis, start)
            remux(input, videoOnlyFile, outputFile, analysis, start)
            validate(input, outputFile, analysis, start)
            val mappedTimestamp = MotionPhotoMotion.mapPrimaryTimestamp(primaryTimestampUs, analysis.firstPtsUs)
            require(mappedTimestamp <= analysis.lastPtsUs - analysis.firstPtsUs + 100_000) {
                "primary timestamp outside output"
            }
            val maxTranslation = analysis.corrections.maxOf { max(abs(it.x), abs(it.y)) }
            val maxRotation = analysis.corrections.maxOf { abs(it.rotationRadians) }
            val maxScaleChange = analysis.corrections.maxOf { abs(it.scale - 1f) }
            val status = when {
                maxTranslation < 0.25f && maxRotation < 0.002f && maxScaleChange < 0.002f -> Status.ALREADY_STABLE
                analysis.unreliablePairs > 0 -> Status.REDUCED_MODEL
                else -> Status.STABILIZED_GLOBAL
            }
            PLog.i(TAG, "status=$status frames=${analysis.corrections.size} crop=${analysis.cropScale} " +
                "maxShiftAnalysisPx=$maxTranslation maxRotationRad=$maxRotation " +
                "unreliablePairs=${analysis.unreliablePairs} elapsedMs=${SystemClock.elapsedRealtime() - start}")
            Result(outputFile, mappedTimestamp, status != Status.ALREADY_STABLE,
                if (status == Status.REDUCED_MODEL) "unreliable frame pairs" else null, status)
        } catch (e: CancellationException) {
            output?.delete()
            throw e
        } catch (e: OutOfMemoryError) {
            output?.delete()
            PLog.w(TAG, "Fallback to original clip: out of memory")
            Result(input, primaryTimestampUs, false, "out of memory")
        } catch (e: Exception) {
            output?.delete()
            PLog.w(TAG, "Fallback to original clip: ${e.javaClass.simpleName}: ${e.message}")
            Result(input, primaryTimestampUs, false, e.javaClass.simpleName)
        } finally {
            videoOnly?.delete()
        }
    }

    private suspend fun checkDeadline(start: Long) {
        currentCoroutineContext().ensureActive()
        check(SystemClock.elapsedRealtime() - start < TIME_LIMIT_MS) { "stabilization timeout" }
    }

    private suspend fun analyze(input: File, primaryTimestampUs: Long, start: Long): Analysis {
        val extractor = MediaExtractor()
        var decoder: MediaCodec? = null
        try {
            extractor.setDataSource(input.absolutePath)
            val videoTrack = findTrack(extractor, "video/")
            val format = extractor.getTrackFormat(videoTrack)
            val width = format.getInteger(MediaFormat.KEY_WIDTH)
            val height = format.getInteger(MediaFormat.KEY_HEIGHT)
            require(width in 16..1920 && height in 16..1920 &&
                width.toLong() * height <= 1920L * 1080L &&
                width.toDouble() / height in 0.4..2.5) { "unsupported resolution" }
            val rotation = if (format.containsKey(MediaFormat.KEY_ROTATION))
                format.getInteger(MediaFormat.KEY_ROTATION) else 0
            val audio = runCatching { findTrack(extractor, "audio/") }.isSuccess
            val analysisHeight = ((height * ANALYSIS_WIDTH.toDouble() / width).toInt()).coerceAtLeast(16)
            extractor.selectTrack(videoTrack)
            decoder = MediaCodec.createDecoderByType(format.getString(MediaFormat.KEY_MIME)!!)
            format.setInteger(MediaFormat.KEY_COLOR_FORMAT, MediaCodecInfo.CodecCapabilities.COLOR_FormatYUV420Flexible)
            decoder.configure(format, null, null, 0)
            decoder.start()
            val info = MediaCodec.BufferInfo()
            var sentEos = false
            var receivedEos = false
            var previous: ByteArray? = null
            val motions = ArrayList<MotionPhotoMotion.Shift>()
            val framePts = ArrayList<Long>()
            val cuts = HashSet<Int>()
            var unreliablePairs = 0
            var firstPts = -1L
            var lastPts = -1L
            var count = 0
            while (!receivedEos) {
                checkDeadline(start)
                if (!sentEos) {
                    val index = decoder.dequeueInputBuffer(10_000)
                    if (index >= 0) {
                        val size = extractor.readSampleData(decoder.getInputBuffer(index)!!, 0)
                        if (size < 0) {
                            decoder.queueInputBuffer(index, 0, 0, 0, MediaCodec.BUFFER_FLAG_END_OF_STREAM)
                            sentEos = true
                        } else {
                            decoder.queueInputBuffer(index, 0, size, extractor.sampleTime, 0)
                            extractor.advance()
                        }
                    }
                }
                val index = decoder.dequeueOutputBuffer(info, 10_000)
                if (index >= 0) {
                    if (info.size > 0) {
                        val image = decoder.getOutputImage(index) ?: error("decoder has no YUV image")
                        val gray = try { downsampleLuma(image, ANALYSIS_WIDTH, analysisHeight) }
                            finally { image.close() }
                        if (firstPts < 0) firstPts = info.presentationTimeUs
                        lastPts = info.presentationTimeUs
                        previous?.let { old ->
                            val motion = MotionPhotoMotion.estimate(old, gray, ANALYSIS_WIDTH, analysisHeight)
                            if (motion == null) {
                                unreliablePairs++
                                // A blurred or dark frame is not necessarily a scene cut. Keep
                                // the path continuous unless the whole image changed abruptly.
                                var difference = 0L
                                for (pixel in gray.indices step 4) {
                                    difference += abs((gray[pixel].toInt() and 0xff) -
                                        (old[pixel].toInt() and 0xff))
                                }
                                if (difference * 4 / gray.size > 55) cuts.add(count)
                            }
                            motions.add(motion ?: MotionPhotoMotion.Shift(0f, 0f))
                        }
                        previous = gray
                        framePts.add(info.presentationTimeUs)
                        count++
                        check(count <= MAX_FRAMES) { "clip too long" }
                    }
                    receivedEos = (info.flags and MediaCodec.BUFFER_FLAG_END_OF_STREAM) != 0
                    decoder.releaseOutputBuffer(index, false)
                }
            }
            require(motions.size - unreliablePairs >= max(3, motions.size / 2)) {
                "insufficient background features"
            }
            require(framePts.zipWithNext().all { (a, b) -> b > a }) { "non-monotonic source PTS" }
            val primaryFrame = framePts.indices.minBy { abs(framePts[it] - primaryTimestampUs) }
            val corrections = MotionPhotoMotion.corrections(
                motions, ANALYSIS_WIDTH, analysisHeight, primaryFrame, cuts)
                ?: error("motion exceeds crop or clip too short")
            require(firstPts >= 0 && lastPts > firstPts && corrections.size == count)
            val cropScale = MotionPhotoWarpGeometry.cropScale(corrections, width, height, analysisHeight)
            require(cropScale >= 0.72f) { "correction exceeds crop budget" }
            return Analysis(corrections, firstPts, lastPts, width, height, analysisHeight, rotation,
                audio, framePts, cropScale, unreliablePairs)
        } finally {
            runCatching { decoder?.stop() }
            decoder?.release()
            extractor.release()
        }
    }

    private fun downsampleLuma(image: android.media.Image, width: Int, height: Int): ByteArray {
        val plane = image.planes[0]
        val buffer = plane.buffer
        val crop = image.cropRect
        val result = ByteArray(width * height)
        for (y in 0 until height) {
            val sy = crop.top + y * crop.height() / height
            for (x in 0 until width) {
                val sx = crop.left + x * crop.width() / width
                result[y * width + x] = buffer.get(sy * plane.rowStride + sx * plane.pixelStride)
            }
        }
        return result
    }

    private suspend fun encode(input: File, output: File, analysis: Analysis, start: Long) {
        val extractor = MediaExtractor()
        var decoder: MediaCodec? = null
        var encoder: MediaCodec? = null
        var muxer: MediaMuxer? = null
        var renderer: MotionPhotoWarpRenderer? = null
        var inputSurface: Surface? = null
        try {
            extractor.setDataSource(input.absolutePath)
            val track = findTrack(extractor, "video/")
            val sourceFormat = extractor.getTrackFormat(track)
            extractor.selectTrack(track)
            val target = MediaFormat.createVideoFormat(MediaFormat.MIMETYPE_VIDEO_AVC, analysis.width, analysis.height).apply {
                setInteger(MediaFormat.KEY_COLOR_FORMAT, MediaCodecInfo.CodecCapabilities.COLOR_FormatSurface)
                setInteger(MediaFormat.KEY_BIT_RATE, ((analysis.width.toLong() * analysis.height * 5).coerceIn(8_000_000, 16_000_000)).toInt())
                setInteger(MediaFormat.KEY_FRAME_RATE, 30)
                setInteger(MediaFormat.KEY_I_FRAME_INTERVAL, 1)
            }
            encoder = MediaCodec.createEncoderByType(MediaFormat.MIMETYPE_VIDEO_AVC)
            encoder.configure(target, null, null, MediaCodec.CONFIGURE_FLAG_ENCODE)
            inputSurface = encoder.createInputSurface()
            encoder.start()
            renderer = MotionPhotoWarpRenderer(requireNotNull(inputSurface), analysis.width,
                analysis.height, analysis.analysisHeight, analysis.cropScale)
            decoder = MediaCodec.createDecoderByType(sourceFormat.getString(MediaFormat.KEY_MIME)!!)
            decoder.configure(sourceFormat, renderer.decoderSurface, null, 0)
            decoder.start()
            val activeDecoder = requireNotNull(decoder)
            muxer = MediaMuxer(output.absolutePath, MediaMuxer.OutputFormat.MUXER_OUTPUT_MPEG_4)
            if (analysis.rotation in listOf(90, 180, 270)) muxer.setOrientationHint(analysis.rotation)
            val activeEncoder = requireNotNull(encoder)
            val activeMuxer = requireNotNull(muxer)
            val activeRenderer = requireNotNull(renderer)
            val decodeInfo = MediaCodec.BufferInfo()
            val encodeInfo = MediaCodec.BufferInfo()
            var sentEos = false
            var decodedEos = false
            var encodedEos = false
            var muxTrack = -1
            var frame = 0
            fun drain(wait: Boolean) {
                while (true) {
                    val index = activeEncoder.dequeueOutputBuffer(encodeInfo, if (wait) 10_000 else 0)
                    if (index == MediaCodec.INFO_TRY_AGAIN_LATER) return
                    if (index == MediaCodec.INFO_OUTPUT_FORMAT_CHANGED) {
                        check(muxTrack < 0)
                        muxTrack = activeMuxer.addTrack(activeEncoder.outputFormat)
                        activeMuxer.start()
                    } else if (index >= 0) {
                        if (encodeInfo.size > 0 &&
                            (encodeInfo.flags and MediaCodec.BUFFER_FLAG_CODEC_CONFIG) == 0) {
                            check(muxTrack >= 0)
                            val buffer = activeEncoder.getOutputBuffer(index)!!
                            buffer.position(encodeInfo.offset)
                            buffer.limit(encodeInfo.offset + encodeInfo.size)
                            activeMuxer.writeSampleData(muxTrack, buffer, encodeInfo)
                        }
                        encodedEos = (encodeInfo.flags and MediaCodec.BUFFER_FLAG_END_OF_STREAM) != 0
                        activeEncoder.releaseOutputBuffer(index, false)
                    }
                }
            }
            while (!decodedEos) {
                checkDeadline(start)
                if (!sentEos) {
                    val index = activeDecoder.dequeueInputBuffer(10_000)
                    if (index >= 0) {
                        val size = extractor.readSampleData(activeDecoder.getInputBuffer(index)!!, 0)
                        if (size < 0) {
                            activeDecoder.queueInputBuffer(index, 0, 0, 0, MediaCodec.BUFFER_FLAG_END_OF_STREAM)
                            sentEos = true
                        } else {
                            activeDecoder.queueInputBuffer(index, 0, size, extractor.sampleTime, 0)
                            extractor.advance()
                        }
                    }
                }
                val index = activeDecoder.dequeueOutputBuffer(decodeInfo, 10_000)
                if (index >= 0) {
                    if (decodeInfo.size > 0) {
                        drain(false)
                        check(frame < analysis.corrections.size) { "frame count changed between passes" }
                        check(decodeInfo.presentationTimeUs == analysis.framePtsUs[frame]) {
                            "frame PTS changed between passes"
                        }
                        activeDecoder.releaseOutputBuffer(index, true)
                        activeRenderer.draw(analysis.corrections[frame], decodeInfo.presentationTimeUs - analysis.firstPtsUs)
                        frame++
                        drain(false)
                    } else activeDecoder.releaseOutputBuffer(index, false)
                    decodedEos = (decodeInfo.flags and MediaCodec.BUFFER_FLAG_END_OF_STREAM) != 0
                }
            }
            check(frame == analysis.corrections.size)
            activeEncoder.signalEndOfInputStream()
            while (!encodedEos) {
                checkDeadline(start)
                drain(true)
            }
            activeMuxer.stop()
        } finally {
            runCatching { decoder?.stop() }
            decoder?.release()
            renderer?.close()
            inputSurface?.release()
            runCatching { encoder?.stop() }
            encoder?.release()
            runCatching { muxer?.release() }
            extractor.release()
        }
    }

    private suspend fun remux(input: File, videoOnly: File, output: File, analysis: Analysis, start: Long) {
        val videoExtractor = MediaExtractor()
        val audioExtractor = MediaExtractor()
        val muxer = MediaMuxer(output.absolutePath, MediaMuxer.OutputFormat.MUXER_OUTPUT_MPEG_4)
        try {
            videoExtractor.setDataSource(videoOnly.absolutePath)
            audioExtractor.setDataSource(input.absolutePath)
            val videoTrack = findTrack(videoExtractor, "video/")
            val audioTrack = runCatching { findTrack(audioExtractor, "audio/") }.getOrNull()
            val videoOutput = muxer.addTrack(videoExtractor.getTrackFormat(videoTrack))
            val audioOutput = audioTrack?.let { muxer.addTrack(audioExtractor.getTrackFormat(it)) }
            if (analysis.rotation in listOf(90, 180, 270)) muxer.setOrientationHint(analysis.rotation)
            muxer.start()
            val videoSamples = copyTrack(videoExtractor, videoTrack, muxer, videoOutput, 0L, start)
            require(videoSamples >= analysis.corrections.size - 1) { "encoded frames missing" }
            if (audioTrack != null && audioOutput != null) {
                require(copyTrack(audioExtractor, audioTrack, muxer, audioOutput,
                    analysis.firstPtsUs, start) > 0) { "audio samples missing" }
            }
            muxer.stop()
        } finally {
            runCatching { muxer.release() }
            videoExtractor.release()
            audioExtractor.release()
        }
    }

    private suspend fun copyTrack(
        extractor: MediaExtractor, source: Int, muxer: MediaMuxer, target: Int,
        basePtsUs: Long, start: Long,
    ): Int {
        extractor.selectTrack(source)
        val format = extractor.getTrackFormat(source)
        val size = if (format.containsKey(MediaFormat.KEY_MAX_INPUT_SIZE))
            format.getInteger(MediaFormat.KEY_MAX_INPUT_SIZE).coerceIn(64 * 1024, 4 * 1024 * 1024)
        else 4 * 1024 * 1024
        val buffer = ByteBuffer.allocateDirect(size)
        val info = MediaCodec.BufferInfo()
        var samples = 0
        while (true) {
            checkDeadline(start)
            buffer.clear()
            val bytes = extractor.readSampleData(buffer, 0)
            if (bytes < 0) break
            require(bytes <= buffer.capacity())
            val pts = extractor.sampleTime - basePtsUs
            if (pts >= 0) {
                info.set(0, bytes, pts, extractor.sampleFlags)
                buffer.position(0)
                buffer.limit(bytes)
                muxer.writeSampleData(target, buffer, info)
                samples++
            }
            extractor.advance()
        }
        return samples
    }

    private suspend fun validate(input: File, output: File, analysis: Analysis, start: Long) {
        require(output.length() > 1024)
        val source = MediaExtractor()
        val result = MediaExtractor()
        try {
            source.setDataSource(input.absolutePath)
            result.setDataSource(output.absolutePath)
            val sourceAudio = runCatching { findTrack(source, "audio/") }.getOrNull()
            val resultAudio = runCatching { findTrack(result, "audio/") }.getOrNull()
            require((sourceAudio != null) == (resultAudio != null) &&
                (resultAudio != null) == analysis.hadAudio) { "audio track lost" }
            val videoTrack = findTrack(result, "video/")
            val video = result.getTrackFormat(videoTrack)
            require(video.getInteger(MediaFormat.KEY_WIDTH) == analysis.width)
            require(video.getInteger(MediaFormat.KEY_HEIGHT) == analysis.height)
            require(lastSampleUs(result, videoTrack, start) >=
                analysis.lastPtsUs - analysis.firstPtsUs - 100_000) { "short output" }
            val rotation = if (video.containsKey(MediaFormat.KEY_ROTATION))
                video.getInteger(MediaFormat.KEY_ROTATION) else 0
            require(rotation == analysis.rotation) { "rotation changed" }
            if (sourceAudio != null && resultAudio != null) {
                val sourceEnd = lastSampleUs(source, sourceAudio, start) - analysis.firstPtsUs
                val outputEnd = lastSampleUs(result, resultAudio, start)
                require(outputEnd >= sourceEnd - 100_000 && outputEnd <= sourceEnd + 100_000) {
                    "audio timeline changed"
                }
            }
        } finally {
            source.release()
            result.release()
        }
    }

    private suspend fun lastSampleUs(extractor: MediaExtractor, track: Int, start: Long): Long {
        extractor.selectTrack(track)
        extractor.seekTo(0L, MediaExtractor.SEEK_TO_CLOSEST_SYNC)
        var last = -1L
        while (extractor.sampleTrackIndex >= 0) {
            checkDeadline(start)
            if (extractor.sampleTrackIndex == track) last = extractor.sampleTime
            if (!extractor.advance()) break
        }
        extractor.unselectTrack(track)
        require(last >= 0) { "empty track" }
        return last
    }

    private fun findTrack(extractor: MediaExtractor, prefix: String): Int =
        (0 until extractor.trackCount).firstOrNull {
            extractor.getTrackFormat(it).getString(MediaFormat.KEY_MIME)?.startsWith(prefix) == true
        } ?: error("missing $prefix track")
}
