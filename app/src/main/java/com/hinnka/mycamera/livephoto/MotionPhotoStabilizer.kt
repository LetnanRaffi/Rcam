package com.hinnka.mycamera.livephoto

import android.graphics.BitmapFactory
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
    private const val MINIMUM_CROP = 0.78f
    private const val MINIMUM_STRENGTH = 0.28f

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
        val stabilizationStrength: Float,
        val unreliablePairs: Int,
        val affinePairs: Int,
        val photoAligned: Boolean,
    )

    suspend fun stabilize(
        input: File,
        primaryTimestampUs: Long,
        referencePhoto: File? = null,
        videoStartTimestampUs: Long = 0L,
        orientationTrack: MotionPhotoOrientationTrack? = null,
    ): Result = withContext(Dispatchers.IO) {
        val start = SystemClock.elapsedRealtime()
        var output: File? = null
        var videoOnly: File? = null
        try {
            require(input.isFile && input.length() > 0) { "empty input" }
            val outputFile = File.createTempFile("stabilized_motion_", ".mp4", input.parentFile)
            output = outputFile
            val videoOnlyFile = File.createTempFile("motion_video_", ".mp4", input.parentFile)
            videoOnly = videoOnlyFile
            val analysis = analyze(
                input, primaryTimestampUs, referencePhoto, videoStartTimestampUs,
                orientationTrack, start,
            )
            val effectivelyStable = analysis.corrections.all {
                max(abs(it.x), abs(it.y)) < .25f && abs(it.rotationRadians) < .002f &&
                    abs(it.scale - 1f) < .002f && max(abs(it.stretch), abs(it.shear)) < .0015f
            }
            if (effectivelyStable) {
                outputFile.delete()
                output = null
                return@withContext Result(
                    input, primaryTimestampUs, false, "trajectory already stable", Status.ALREADY_STABLE,
                )
            }
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
            val maxAffine = analysis.corrections.maxOf { max(abs(it.stretch), abs(it.shear)) }
            val status = when {
                maxTranslation < 0.25f && maxRotation < 0.002f &&
                    maxScaleChange < 0.002f && maxAffine < 0.0015f -> Status.ALREADY_STABLE
                analysis.unreliablePairs > 0 || analysis.stabilizationStrength < .98f -> Status.REDUCED_MODEL
                else -> Status.STABILIZED_GLOBAL
            }
            PLog.i(TAG, "status=$status frames=${analysis.corrections.size} crop=${analysis.cropScale} " +
                "maxShiftAnalysisPx=$maxTranslation maxRotationRad=$maxRotation " +
                "maxAffine=$maxAffine strength=${analysis.stabilizationStrength} " +
                "affinePairs=${analysis.affinePairs} photoAligned=${analysis.photoAligned} " +
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

    private suspend fun analyze(
        input: File,
        primaryTimestampUs: Long,
        referencePhoto: File?,
        videoStartTimestampUs: Long,
        orientationTrack: MotionPhotoOrientationTrack?,
        start: Long,
    ): Analysis {
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
            val motions = ArrayList<MotionPhotoMotion.Shift?>()
            val framePts = ArrayList<Long>()
            val cuts = HashSet<Int>()
            var unreliablePairs = 0
            var primaryGray: ByteArray? = null
            var primaryDistanceUs = Long.MAX_VALUE
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
                        val shutterDistance = abs(info.presentationTimeUs - primaryTimestampUs)
                        if (shutterDistance < primaryDistanceUs) {
                            primaryDistanceUs = shutterDistance
                            primaryGray = gray
                        }
                        previous?.let { old ->
                            val previousPtsUs = framePts.last()
                            val orientationPrior = orientationTrack?.relativeRoll(
                                videoStartTimestampUs + previousPtsUs,
                                videoStartTimestampUs + info.presentationTimeUs,
                            )
                            val motion = MotionPhotoMotion.estimate(
                                old, gray, ANALYSIS_WIDTH, analysisHeight,
                                orientationPrior = orientationPrior,
                            )
                            if (motion == null) {
                                unreliablePairs++
                                if (MotionPhotoMotion.isSceneCut(old, gray)) cuts.add(count)
                            }
                            motions.add(motion)
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
            require(motions.size - unreliablePairs >= max(3, motions.size * 2 / 3)) {
                "insufficient background features"
            }
            require(framePts.zipWithNext().all { (a, b) -> b > a }) { "non-monotonic source PTS" }
            val repairedMotions = MotionPhotoMotion.repair(motions, cuts)
                ?: error("unreliable motion run")
            val primaryFrame = framePts.indices.minBy { abs(framePts[it] - primaryTimestampUs) }
            val stillGray = referencePhoto?.takeIf { it.isFile && it.length() > 0L }?.let {
                loadStillLuma(it, ANALYSIS_WIDTH, analysisHeight)
            }
            val photoAlignment = if (stillGray != null && primaryGray != null) {
                MotionPhotoMotion.estimate(
                    primaryGray, stillGray, ANALYSIS_WIDTH, analysisHeight, alignment = true,
                )?.takeIf { alignment ->
                    alignment.inlierRatio >= .38f && alignment.residual <= 1.8f &&
                        MotionPhotoWarpGeometry.cropScale(
                            listOf(alignment), width, height, analysisHeight,
                        ) >= .80f
                }
            } else null
            var corrections = MotionPhotoMotion.corrections(
                repairedMotions, ANALYSIS_WIDTH, analysisHeight, primaryFrame, cuts,
                framePtsUs = framePts, photoAlignment = photoAlignment)
                ?: error("motion exceeds crop or clip too short")
            require(firstPts >= 0 && lastPts > firstPts && corrections.size == count)
            var cropScale = MotionPhotoWarpGeometry.cropScale(corrections, width, height, analysisHeight)
            var strength = 1f
            if (cropScale < MINIMUM_CROP) {
                var low = 0f
                var high = 1f
                repeat(14) {
                    val candidate = (low + high) * .5f
                    val candidateCrop = MotionPhotoWarpGeometry.cropScale(
                        MotionPhotoMotion.withStrength(corrections, candidate), width, height, analysisHeight,
                    )
                    if (candidateCrop >= MINIMUM_CROP) low = candidate else high = candidate
                }
                strength = low
                require(strength >= MINIMUM_STRENGTH) { "correction exceeds crop budget" }
                corrections = MotionPhotoMotion.withStrength(corrections, strength)
                cropScale = MotionPhotoWarpGeometry.cropScale(corrections, width, height, analysisHeight)
            }
            return Analysis(corrections, firstPts, lastPts, width, height, analysisHeight, rotation,
                audio, framePts, cropScale, strength, unreliablePairs,
                repairedMotions.count { it.model == MotionPhotoMotion.ModelKind.AFFINE },
                photoAlignment != null)
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

    /** Decodes only a bounded preview and center-crops it to the Motion Photo video geometry. */
    private fun loadStillLuma(file: File, width: Int, height: Int): ByteArray? = runCatching {
        val bounds = BitmapFactory.Options().apply { inJustDecodeBounds = true }
        BitmapFactory.decodeFile(file.absolutePath, bounds)
        require(bounds.outWidth > 0 && bounds.outHeight > 0)
        var sampleSize = 1
        while (max(bounds.outWidth, bounds.outHeight) / sampleSize > 1400) sampleSize *= 2
        val bitmap = BitmapFactory.decodeFile(file.absolutePath, BitmapFactory.Options().apply {
            inSampleSize = sampleSize
            inPreferredConfig = android.graphics.Bitmap.Config.ARGB_8888
        }) ?: return@runCatching null
        try {
            val pixels = IntArray(bitmap.width * bitmap.height)
            bitmap.getPixels(pixels, 0, bitmap.width, 0, 0, bitmap.width, bitmap.height)
            val targetAspect = width.toFloat() / height
            val sourceAspect = bitmap.width.toFloat() / bitmap.height
            val cropWidth: Float
            val cropHeight: Float
            if (sourceAspect > targetAspect) {
                cropHeight = bitmap.height.toFloat()
                cropWidth = cropHeight * targetAspect
            } else {
                cropWidth = bitmap.width.toFloat()
                cropHeight = cropWidth / targetAspect
            }
            val left = (bitmap.width - cropWidth) * .5f
            val top = (bitmap.height - cropHeight) * .5f
            ByteArray(width * height) { index ->
                val x = index % width
                val y = index / width
                val sourceX = (left + (x + .5f) * cropWidth / width).toInt().coerceIn(0, bitmap.width - 1)
                val sourceY = (top + (y + .5f) * cropHeight / height).toInt().coerceIn(0, bitmap.height - 1)
                val color = pixels[sourceY * bitmap.width + sourceX]
                val red = color shr 16 and 255
                val green = color shr 8 and 255
                val blue = color and 255
                ((red * 54 + green * 183 + blue * 19) shr 8).toByte()
            }
        } finally {
            bitmap.recycle()
        }
    }.getOrNull()

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
                val sourceBitrate = if (sourceFormat.containsKey(MediaFormat.KEY_BIT_RATE)) {
                    sourceFormat.getInteger(MediaFormat.KEY_BIT_RATE)
                } else 8_000_000
                setInteger(MediaFormat.KEY_BIT_RATE, max(sourceBitrate,
                    (analysis.width.toLong() * analysis.height * 5).toInt()).coerceIn(8_000_000, 20_000_000))
                val sourceFrameRate = if (sourceFormat.containsKey(MediaFormat.KEY_FRAME_RATE)) {
                    sourceFormat.getInteger(MediaFormat.KEY_FRAME_RATE)
                } else 30
                setInteger(MediaFormat.KEY_FRAME_RATE, sourceFrameRate.coerceIn(24, 60))
                setInteger(MediaFormat.KEY_I_FRAME_INTERVAL, 1)
                for (key in listOf(MediaFormat.KEY_COLOR_STANDARD, MediaFormat.KEY_COLOR_TRANSFER,
                    MediaFormat.KEY_COLOR_RANGE)) {
                    if (sourceFormat.containsKey(key)) setInteger(key, sourceFormat.getInteger(key))
                }
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
