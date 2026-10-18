package com.hinnka.mycamera.raw

import android.content.Context
import android.opengl.GLES30
import com.hinnka.mycamera.raw.fotufilm.FotufilmBuildResult
import com.hinnka.mycamera.raw.fotufilm.FotufilmDocument
import com.hinnka.mycamera.raw.fotufilm.FotufilmFrame
import com.hinnka.mycamera.raw.fotufilm.FotufilmRenderer
import com.hinnka.mycamera.raw.fotufilm.FotufilmRuntime
import com.hinnka.mycamera.utils.PLog
import java.nio.ByteBuffer
import java.nio.ByteOrder
import kotlin.math.ceil
import kotlin.math.max
import kotlin.math.min
import kotlin.math.roundToInt

/**
 * Whole-frame metering image for a frame rendered in tiles: linear Rec.2020 scene light of the
 * entire frame at a reduced, aspect-preserving size, laid out as RGBA floats (direct buffer).
 * [identity] names its content so frames built from it are cached.
 */
internal class FotufilmMeteringImage(
    val buffer: ByteBuffer,
    val width: Int,
    val height: Int,
    val identity: String,
)

/** What the tone stage needs to develop one photograph through Fotufilm. */
internal data class FotufilmRenderRequest(
    val context: Context,
    val document: FotufilmDocument,
    /** Required when the tone stage sees a tile of a frame whose development meters the scene. */
    val metering: FotufilmMeteringImage? = null,
)

/**
 * Fotufilm in the RAW tone stage.
 *
 * 1. The shared engine-input shader prepares linear Rec.2020 scene light (PGTM, exposure, the
 *    profile-to-BT.2020 transform) exactly as every other scene-referred engine receives it.
 * 2. [FotufilmRenderer] develops and prints it in sub-tiles whose apron covers the frame's
 *    `spatialSupport`, at absolute frame coordinates, so the result equals a whole-frame render.
 * 3. The print's linear Display P3 is carried to the tone pass output space.
 *
 * The virtual frame is the full image at the tone input's own sampling density: a downsampled
 * input (globalWidth > width) is a smaller frame, so grain and every millimetre-sized structure
 * keep their size on the film.
 */
internal class FotufilmToneStage(private val quad: RawFullscreenQuad) {
    /** Prepares the engine input; also binds its shared resources for callers outside [render]. */
    internal val inputAlgorithm = FotufilmInputToneAlgorithm(quad)
    private val renderer = FotufilmRenderer(quad)
    private var finishProgram = 0
    private var meterProgram = 0
    private val prepared = Target("prepared")
    private val subInput = Target("subInput")
    private val subOutput = Target("subOutput")
    private val meterTarget = Target("meter")
    private val readFramebuffers = IntArray(2)

    fun render(input: RawEngineTonePass.Input): RawEngineTonePass.Output? {
        val request = requireNotNull(input.fotufilm) { "Fotufilm render request missing" }
        if (!prepared.ensure(input.width, input.height)) return null
        inputAlgorithm.render(
            input.copy(targetFramebufferId = prepared.framebuffer, targetTextureId = prepared.texture),
        ) ?: return null

        val scaleX = input.width.toDouble() / input.globalWidth
        val scaleY = input.height.toDouble() / input.globalHeight
        val originX = (input.globalOriginX * scaleX).roundToInt()
        val originY = (input.globalOriginY * scaleY).roundToInt()
        val frameWidth = max((input.fullImageWidth * scaleX).roundToInt(), originX + input.width)
        val frameHeight = max((input.fullImageHeight * scaleY).roundToInt(), originY + input.height)
        val wholeFrame = originX == 0 && originY == 0 &&
            input.width == frameWidth && input.height == frameHeight

        val frame = buildFrame(request, frameWidth, frameHeight, wholeFrame, input.width, input.height)
            ?: return null
        val outputTransform = DngSdkColorSpec.multiplyMatrix3x3(
            input.outputTransform,
            RawToneMappingGl.computeWorkingToOutputTransform(ColorSpace.DisplayP3, ColorSpace.BT2020),
        )
        val support = frame.spatialSupport
        val coreEdge = max(MAX_SUB_TILE_EDGE - 2 * support, MIN_SUB_TILE_CORE)
        if (input.width <= MAX_SUB_TILE_EDGE && input.height <= MAX_SUB_TILE_EDGE) {
            if (!subOutput.ensure(input.width, input.height)) return null
            renderer.render(
                frame, prepared.texture, input.width, input.height, originX, originY, null,
                subOutput.framebuffer, subOutput.texture,
            )
            finish(subOutput.texture, 0, 0, 0, 0, input.width, input.height, input, outputTransform)
        } else {
            var top = 0
            while (top < input.height) {
                val coreBottom = min(input.height, top + coreEdge)
                var left = 0
                while (left < input.width) {
                    val coreRight = min(input.width, left + coreEdge)
                    val workLeft = max(0, left - support)
                    val workTop = max(0, top - support)
                    val workRight = min(input.width, coreRight + support)
                    val workBottom = min(input.height, coreBottom + support)
                    val workWidth = workRight - workLeft
                    val workHeight = workBottom - workTop
                    if (!subInput.ensure(workWidth, workHeight) || !subOutput.ensure(workWidth, workHeight)) {
                        return null
                    }
                    copyRegion(prepared.framebuffer, workLeft, workTop, workWidth, workHeight, subInput.framebuffer)
                    renderer.render(
                        frame, subInput.texture, workWidth, workHeight,
                        originX + workLeft, originY + workTop, null,
                        subOutput.framebuffer, subOutput.texture,
                    )
                    finish(
                        subOutput.texture, left - workLeft, top - workTop, left, top,
                        coreRight - left, coreBottom - top, input, outputTransform,
                    )
                    left = coreRight
                }
                top = coreBottom
            }
        }
        RawGlesProgram.logErrors("FotufilmToneStage.render")
        return RawEngineTonePass.Output(input.targetTextureId, input.width, input.height)
    }

    /**
     * Writes the tile's whole-frame metering contribution: the sums of prepared scene light and
     * the pixel count over each cell of a [cellSize]-pixel lattice of the frame that the tile's core
     * ([coreLeft], [coreTop], [coreWidth] × [coreHeight] in input pixels) covers. The caller adds the
     * returned cells (row-major RGBA = r, g, b sums and count, starting at cell ([firstCellX],
     * [firstCellY])) into its whole-frame accumulator.
     */
    fun meterTile(
        input: RawEngineTonePass.Input,
        coreLeft: Int,
        coreTop: Int,
        coreWidth: Int,
        coreHeight: Int,
        cellSize: Int,
    ): FotufilmMeterCells? {
        require(input.width == input.globalWidth && input.height == input.globalHeight) {
            "Fotufilm tile metering reads full-resolution tiles"
        }
        if (!prepared.ensure(input.width, input.height)) return null
        inputAlgorithm.render(
            input.copy(targetFramebufferId = prepared.framebuffer, targetTextureId = prepared.texture),
        ) ?: return null
        val frameLeft = input.globalOriginX + coreLeft
        val frameTop = input.globalOriginY + coreTop
        val firstCellX = frameLeft / cellSize
        val firstCellY = frameTop / cellSize
        val lastCellX = (frameLeft + coreWidth - 1) / cellSize
        val lastCellY = (frameTop + coreHeight - 1) / cellSize
        val cellsX = lastCellX - firstCellX + 1
        val cellsY = lastCellY - firstCellY + 1
        val values = meter(
            source = prepared.texture,
            outputWidth = cellsX,
            outputHeight = cellsY,
            cellSize = cellSize,
            // Cell (i, j) spans frame pixels [cell * size, (cell + 1) * size) clipped to the core.
            cellOriginX = firstCellX * cellSize - input.globalOriginX,
            cellOriginY = firstCellY * cellSize - input.globalOriginY,
            clipLeft = coreLeft,
            clipTop = coreTop,
            clipRight = coreLeft + coreWidth,
            clipBottom = coreTop + coreHeight,
            normalize = false,
        ) ?: return null
        return FotufilmMeterCells(firstCellX, firstCellY, cellsX, cellsY, values)
    }

    private fun buildFrame(
        request: FotufilmRenderRequest,
        frameWidth: Int,
        frameHeight: Int,
        wholeFrame: Boolean,
        inputWidth: Int,
        inputHeight: Int,
    ): FotufilmFrame? {
        val metering = request.metering
        if (metering != null) {
            return FotufilmRuntime.buildFrame(
                request.context, request.document, frameWidth, frameHeight,
                meteringBuffer = metering.buffer, meteringWidth = metering.width,
                meteringHeight = metering.height, measureFlareOnDevice = false,
                sourceIdentity = metering.identity,
            ).frame
        }
        val plain = FotufilmRuntime.buildFrame(
            request.context, request.document, frameWidth, frameHeight, measureFlareOnDevice = false,
        )
        if (!plain.needsMetering()) return plain.frame
        check(wholeFrame) {
            "Fotufilm frame ${frameWidth}x$frameHeight meters the scene; a tile needs a metering image"
        }
        val scale = max(1, ceil(max(inputWidth, inputHeight).toDouble() / METERING_LONG_EDGE).toInt())
        val width = (inputWidth + scale - 1) / scale
        val height = (inputHeight + scale - 1) / scale
        val values = meter(
            source = prepared.texture,
            outputWidth = width,
            outputHeight = height,
            cellSize = scale,
            cellOriginX = 0,
            cellOriginY = 0,
            clipLeft = 0,
            clipTop = 0,
            clipRight = inputWidth,
            clipBottom = inputHeight,
            normalize = true,
        ) ?: return null
        val buffer = ByteBuffer.allocateDirect(values.size * Float.SIZE_BYTES).order(ByteOrder.nativeOrder())
        buffer.asFloatBuffer().put(values)
        PLog.d(TAG, "Metered ${frameWidth}x$frameHeight on a ${width}x$height image")
        return FotufilmRuntime.buildFrame(
            request.context, request.document, frameWidth, frameHeight,
            meteringBuffer = buffer, meteringWidth = width, meteringHeight = height,
            measureFlareOnDevice = false,
        ).frame
    }

    /** Box-averages (or sums, with the pixel count in alpha) [source] over a cell lattice. */
    private fun meter(
        source: Int,
        outputWidth: Int,
        outputHeight: Int,
        cellSize: Int,
        cellOriginX: Int,
        cellOriginY: Int,
        clipLeft: Int,
        clipTop: Int,
        clipRight: Int,
        clipBottom: Int,
        normalize: Boolean,
    ): FloatArray? {
        if (!meterTarget.ensure(outputWidth, outputHeight, GLES30.GL_RGBA32F)) return null
        val program = ensureMeterProgram()
        if (program == 0) return null
        GLES30.glUseProgram(program)
        GLES30.glBindFramebuffer(GLES30.GL_FRAMEBUFFER, meterTarget.framebuffer)
        GLES30.glViewport(0, 0, outputWidth, outputHeight)
        GLES30.glDisable(GLES30.GL_BLEND)
        GLES30.glActiveTexture(GLES30.GL_TEXTURE0)
        GLES30.glBindTexture(GLES30.GL_TEXTURE_2D, source)
        GLES30.glUniform1i(GLES30.glGetUniformLocation(program, "uSource"), 0)
        GLES30.glUniform1i(GLES30.glGetUniformLocation(program, "uCellSize"), cellSize)
        GLES30.glUniform2i(GLES30.glGetUniformLocation(program, "uCellOrigin"), cellOriginX, cellOriginY)
        GLES30.glUniform4i(GLES30.glGetUniformLocation(program, "uClip"), clipLeft, clipTop, clipRight, clipBottom)
        GLES30.glUniform1i(GLES30.glGetUniformLocation(program, "uNormalize"), if (normalize) 1 else 0)
        quad.bindIdentityTextureMatrix(program)
        quad.draw(program)
        val bytes = ByteBuffer.allocateDirect(outputWidth * outputHeight * 4 * Float.SIZE_BYTES)
            .order(ByteOrder.nativeOrder())
        GLES30.glPixelStorei(GLES30.GL_PACK_ALIGNMENT, 4)
        GLES30.glReadPixels(0, 0, outputWidth, outputHeight, GLES30.GL_RGBA, GLES30.GL_FLOAT, bytes)
        val error = GLES30.glGetError()
        if (error != GLES30.GL_NO_ERROR) {
            PLog.e(TAG, "Fotufilm metering readback failed: error=0x${error.toString(16)}")
            return null
        }
        return FloatArray(outputWidth * outputHeight * 4).also { bytes.asFloatBuffer().get(it) }
    }

    private fun finish(
        source: Int,
        sourceX: Int,
        sourceY: Int,
        targetX: Int,
        targetY: Int,
        width: Int,
        height: Int,
        input: RawEngineTonePass.Input,
        outputTransform: FloatArray,
    ) {
        val program = ensureFinishProgram()
        check(program != 0) { "Fotufilm finish program unavailable" }
        GLES30.glUseProgram(program)
        GLES30.glBindFramebuffer(GLES30.GL_FRAMEBUFFER, input.targetFramebufferId)
        GLES30.glViewport(targetX, targetY, width, height)
        GLES30.glDisable(GLES30.GL_BLEND)
        GLES30.glActiveTexture(GLES30.GL_TEXTURE0)
        GLES30.glBindTexture(GLES30.GL_TEXTURE_2D, source)
        GLES30.glUniform1i(GLES30.glGetUniformLocation(program, "uSource"), 0)
        GLES30.glUniform2i(GLES30.glGetUniformLocation(program, "uSourceOffset"), sourceX - targetX, sourceY - targetY)
        GLES30.glUniformMatrix3fv(
            GLES30.glGetUniformLocation(program, "uOutputTransform"), 1, false,
            RawToneMappingGl.transposeMatrix3x3(outputTransform), 0,
        )
        quad.bindIdentityTextureMatrix(program)
        quad.draw(program)
    }

    private fun copyRegion(sourceFramebuffer: Int, x: Int, y: Int, width: Int, height: Int, targetFramebuffer: Int) {
        GLES30.glBindFramebuffer(GLES30.GL_READ_FRAMEBUFFER, sourceFramebuffer)
        GLES30.glBindFramebuffer(GLES30.GL_DRAW_FRAMEBUFFER, targetFramebuffer)
        GLES30.glBlitFramebuffer(
            x, y, x + width, y + height, 0, 0, width, height,
            GLES30.GL_COLOR_BUFFER_BIT, GLES30.GL_NEAREST,
        )
        GLES30.glBindFramebuffer(GLES30.GL_READ_FRAMEBUFFER, 0)
        GLES30.glBindFramebuffer(GLES30.GL_DRAW_FRAMEBUFFER, 0)
    }

    private fun ensureFinishProgram(): Int {
        if (finishProgram == 0) finishProgram = quad.createProgram(FINISH_SHADER, "fotufilmFinish")
        return finishProgram
    }

    private fun ensureMeterProgram(): Int {
        if (meterProgram == 0) meterProgram = quad.createProgram(METER_SHADER, "fotufilmMeter")
        return meterProgram
    }

    fun prewarm(): Boolean = inputAlgorithm.prewarm() && ensureFinishProgram() != 0 && ensureMeterProgram() != 0

    fun release() {
        inputAlgorithm.release()
        renderer.release()
        if (finishProgram != 0) GLES30.glDeleteProgram(finishProgram)
        if (meterProgram != 0) GLES30.glDeleteProgram(meterProgram)
        finishProgram = 0
        meterProgram = 0
        listOf(prepared, subInput, subOutput, meterTarget).forEach(Target::release)
    }

    private fun FotufilmBuildResult.needsMetering(): Boolean = wantsSceneMetering || wantsFlareMean

    /** One render target, reallocated only when its size or format changes. */
    private class Target(private val label: String) {
        var texture = 0
            private set
        var framebuffer = 0
            private set
        private var width = 0
        private var height = 0
        private var format = 0

        fun ensure(width: Int, height: Int, format: Int = GLES30.GL_RGBA16F): Boolean {
            if (texture != 0 && this.width == width && this.height == height && this.format == format) return true
            release()
            texture = IntArray(1).also { GLES30.glGenTextures(1, it, 0) }[0]
            framebuffer = IntArray(1).also { GLES30.glGenFramebuffers(1, it, 0) }[0]
            GLES30.glActiveTexture(GLES30.GL_TEXTURE0)
            GLES30.glBindTexture(GLES30.GL_TEXTURE_2D, texture)
            GLES30.glTexStorage2D(GLES30.GL_TEXTURE_2D, 1, format, width, height)
            GLES30.glTexParameteri(GLES30.GL_TEXTURE_2D, GLES30.GL_TEXTURE_MIN_FILTER, GLES30.GL_NEAREST)
            GLES30.glTexParameteri(GLES30.GL_TEXTURE_2D, GLES30.GL_TEXTURE_MAG_FILTER, GLES30.GL_NEAREST)
            GLES30.glTexParameteri(GLES30.GL_TEXTURE_2D, GLES30.GL_TEXTURE_WRAP_S, GLES30.GL_CLAMP_TO_EDGE)
            GLES30.glTexParameteri(GLES30.GL_TEXTURE_2D, GLES30.GL_TEXTURE_WRAP_T, GLES30.GL_CLAMP_TO_EDGE)
            GLES30.glBindFramebuffer(GLES30.GL_FRAMEBUFFER, framebuffer)
            GLES30.glFramebufferTexture2D(
                GLES30.GL_FRAMEBUFFER, GLES30.GL_COLOR_ATTACHMENT0, GLES30.GL_TEXTURE_2D, texture, 0,
            )
            val status = GLES30.glCheckFramebufferStatus(GLES30.GL_FRAMEBUFFER)
            val error = GLES30.glGetError()
            if (status != GLES30.GL_FRAMEBUFFER_COMPLETE || error != GLES30.GL_NO_ERROR) {
                PLog.e(TAG, "Fotufilm $label target ${width}x$height allocation failed: status=$status error=$error")
                release()
                return false
            }
            this.width = width
            this.height = height
            this.format = format
            return true
        }

        fun release() {
            if (texture != 0) GLES30.glDeleteTextures(1, intArrayOf(texture), 0)
            if (framebuffer != 0) GLES30.glDeleteFramebuffers(1, intArrayOf(framebuffer), 0)
            texture = 0
            framebuffer = 0
            width = 0
            height = 0
            format = 0
        }
    }

    companion object {
        private const val TAG = "FotufilmToneStage"

        /** Largest sub-tile edge, apron included: bounds the renderer's RGBA32F intermediates. */
        private const val MAX_SUB_TILE_EDGE = 2048
        private const val MIN_SUB_TILE_CORE = 512

        /** Long edge of the whole-frame image the scene is metered on (upstream accepts a downsample). */
        const val METERING_LONG_EDGE = 1024

        private val FINISH_SHADER = """
            #version 300 es
            precision highp float;
            precision highp int;
            precision highp sampler2D;
            out vec4 fragColor;
            uniform sampler2D uSource;
            uniform ivec2 uSourceOffset;
            uniform mat3 uOutputTransform;
            void main() {
                vec3 print = texelFetch(uSource, ivec2(gl_FragCoord.xy) + uSourceOffset, 0).rgb;
                fragColor = vec4(uOutputTransform * print, 1.0);
            }
        """.trimIndent()

        private val METER_SHADER = """
            #version 300 es
            precision highp float;
            precision highp int;
            precision highp sampler2D;
            out vec4 fragColor;
            uniform sampler2D uSource;
            uniform int uCellSize;
            uniform ivec2 uCellOrigin;
            uniform ivec4 uClip;
            uniform int uNormalize;
            void main() {
                ivec2 cell = ivec2(gl_FragCoord.xy);
                ivec2 start = max(uCellOrigin + cell * uCellSize, uClip.xy);
                ivec2 end = min(uCellOrigin + (cell + 1) * uCellSize, uClip.zw);
                vec3 sum = vec3(0.0);
                float count = 0.0;
                for (int y = start.y; y < end.y; ++y) {
                    for (int x = start.x; x < end.x; ++x) {
                        sum += texelFetch(uSource, ivec2(x, y), 0).rgb;
                        count += 1.0;
                    }
                }
                fragColor = uNormalize != 0
                    ? vec4(count > 0.0 ? sum / count : vec3(0.0), 1.0)
                    : vec4(sum, count);
            }
        """.trimIndent()
    }
}

/** A tile's metering cells: row-major RGBA (r, g, b sums, pixel count). */
internal class FotufilmMeterCells(
    val firstCellX: Int,
    val firstCellY: Int,
    val width: Int,
    val height: Int,
    val values: FloatArray,
)

/** Accumulates tile metering cells into the whole-frame metering image. */
internal class FotufilmMeteringAccumulator(frameWidth: Int, frameHeight: Int) {
    val cellSize = max(1, ceil(max(frameWidth, frameHeight).toDouble() / FotufilmToneStage.METERING_LONG_EDGE).toInt())
    val width = (frameWidth + cellSize - 1) / cellSize
    val height = (frameHeight + cellSize - 1) / cellSize
    private val sums = DoubleArray(width * height * 4)

    fun add(cells: FotufilmMeterCells) {
        for (y in 0 until cells.height) {
            for (x in 0 until cells.width) {
                val target = ((cells.firstCellY + y) * width + cells.firstCellX + x) * 4
                val source = (y * cells.width + x) * 4
                for (c in 0 until 4) sums[target + c] += cells.values[source + c].toDouble()
            }
        }
    }

    /**
     * The metered image: the cells the tiles covered, which is the rendered region of the frame
     * (the output crop) rather than the whole sensor when the photograph is cropped.
     */
    fun image(identity: String): FotufilmMeteringImage {
        var left = width
        var top = height
        var right = -1
        var bottom = -1
        for (y in 0 until height) for (x in 0 until width) {
            if (sums[(y * width + x) * 4 + 3] > 0.0) {
                left = min(left, x)
                top = min(top, y)
                right = max(right, x)
                bottom = max(bottom, y)
            }
        }
        check(right >= left && bottom >= top) { "Fotufilm metering received no pixels" }
        val imageWidth = right - left + 1
        val imageHeight = bottom - top + 1
        val buffer = ByteBuffer.allocateDirect(imageWidth * imageHeight * 4 * Float.SIZE_BYTES)
            .order(ByteOrder.nativeOrder())
        val floats = buffer.asFloatBuffer()
        for (y in top..bottom) for (x in left..right) {
            val i = (y * width + x) * 4
            val count = sums[i + 3]
            check(count > 0.0) { "Fotufilm metering cell ($x, $y) inside the rendered region received no pixels" }
            floats.put((sums[i] / count).toFloat())
            floats.put((sums[i + 1] / count).toFloat())
            floats.put((sums[i + 2] / count).toFloat())
            floats.put(1f)
        }
        return FotufilmMeteringImage(buffer, imageWidth, imageHeight, identity)
    }
}

/** The shared engine-input shader with an identity engine: linear Rec.2020 scene light out. */
internal object FotufilmInputToneShader {
    val DEFINITION = RawEngineToneShaderDefinition(
        engineUniforms = "uniform mat3 uOutputTransform;",
        engineFunctions = """
            vec3 applyEngineTone(vec3 color) {
                return color;
            }
        """.trimIndent(),
        includeAdobeProfilePipeline = false,
    )
}

internal class FotufilmInputToneAlgorithm(quad: RawFullscreenQuad) :
    RawRenderingEngineToneAlgorithm(quad, FotufilmInputToneShader.DEFINITION)
