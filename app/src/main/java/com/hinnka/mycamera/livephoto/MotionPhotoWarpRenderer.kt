package com.hinnka.mycamera.livephoto

import android.graphics.SurfaceTexture
import android.opengl.EGL14
import android.opengl.EGLConfig
import android.opengl.EGLExt
import android.opengl.GLES11Ext
import android.opengl.GLES20
import android.view.Surface
import java.nio.ByteBuffer
import java.nio.ByteOrder

/** Own EGL context; decoded external texture is drawn directly into the H.264 input surface. */
internal class MotionPhotoWarpRenderer(
    encoderSurface: Surface,
    private val width: Int,
    private val height: Int,
    private val analysisHeight: Int,
    private val cropScale: Float,
) : AutoCloseable {
    private val display = EGL14.eglGetDisplay(EGL14.EGL_DEFAULT_DISPLAY)
    private var context = EGL14.EGL_NO_CONTEXT
    private var eglSurface = EGL14.EGL_NO_SURFACE
    private var texture = 0
    private var program = 0
    private val vertices = ByteBuffer.allocateDirect(8 * 4)
        .order(ByteOrder.nativeOrder()).asFloatBuffer().apply {
            put(floatArrayOf(-1f, -1f, 1f, -1f, -1f, 1f, 1f, 1f))
            position(0)
        }
    private val lock = Object()
    private var frameAvailable = false
    private lateinit var surfaceTexture: SurfaceTexture
    lateinit var decoderSurface: Surface
        private set

    init {
        try {
            check(EGL14.eglInitialize(display, IntArray(2), 0, IntArray(2), 0))
            val configs = arrayOfNulls<EGLConfig>(1)
            val count = IntArray(1)
            check(EGL14.eglChooseConfig(display, intArrayOf(
                EGL14.EGL_RED_SIZE, 8, EGL14.EGL_GREEN_SIZE, 8,
                EGL14.EGL_BLUE_SIZE, 8, EGL14.EGL_ALPHA_SIZE, 8,
                EGL14.EGL_RENDERABLE_TYPE, EGL14.EGL_OPENGL_ES2_BIT,
                0x3142, 1, EGL14.EGL_NONE,
            ), 0, configs, 0, 1, count, 0) && count[0] > 0)
            val config = configs[0]!!
            context = EGL14.eglCreateContext(display, config, EGL14.EGL_NO_CONTEXT,
                intArrayOf(EGL14.EGL_CONTEXT_CLIENT_VERSION, 2, EGL14.EGL_NONE), 0)
            check(context != EGL14.EGL_NO_CONTEXT)
            eglSurface = EGL14.eglCreateWindowSurface(display, config, encoderSurface,
                intArrayOf(EGL14.EGL_NONE), 0)
            check(eglSurface != EGL14.EGL_NO_SURFACE)
            check(EGL14.eglMakeCurrent(display, eglSurface, eglSurface, context))
            val textures = IntArray(1)
            GLES20.glGenTextures(1, textures, 0)
            texture = textures[0]
            GLES20.glBindTexture(GLES11Ext.GL_TEXTURE_EXTERNAL_OES, texture)
            GLES20.glTexParameteri(GLES11Ext.GL_TEXTURE_EXTERNAL_OES, GLES20.GL_TEXTURE_MIN_FILTER, GLES20.GL_LINEAR)
            GLES20.glTexParameteri(GLES11Ext.GL_TEXTURE_EXTERNAL_OES, GLES20.GL_TEXTURE_MAG_FILTER, GLES20.GL_LINEAR)
            GLES20.glTexParameteri(GLES11Ext.GL_TEXTURE_EXTERNAL_OES, GLES20.GL_TEXTURE_WRAP_S, GLES20.GL_CLAMP_TO_EDGE)
            GLES20.glTexParameteri(GLES11Ext.GL_TEXTURE_EXTERNAL_OES, GLES20.GL_TEXTURE_WRAP_T, GLES20.GL_CLAMP_TO_EDGE)
            surfaceTexture = SurfaceTexture(texture)
            surfaceTexture.setOnFrameAvailableListener { synchronized(lock) { frameAvailable = true; lock.notifyAll() } }
            decoderSurface = Surface(surfaceTexture)
            val vertex = shader(GLES20.GL_VERTEX_SHADER, """
                attribute vec2 aPosition;
                varying vec2 vUv;
                void main() { gl_Position = vec4(aPosition, 0.0, 1.0); vUv = aPosition * 0.5 + 0.5; }
            """.trimIndent())
            val fragment = shader(GLES20.GL_FRAGMENT_SHADER, """
                #extension GL_OES_EGL_image_external : require
                precision highp float;
                uniform samplerExternalOES uTexture;
                uniform mat4 uTextureMatrix;
                uniform vec2 uOffset;
                uniform vec2 uFrameSize;
                uniform float uAngle;
                uniform float uScale;
                uniform float uCrop;
                varying vec2 vUv;
                void main() {
                    vec2 outputPx = vec2((vUv.x - 0.5) * uFrameSize.x,
                        (0.5 - vUv.y) * uFrameSize.y) * uCrop - uOffset;
                    float c = cos(uAngle);
                    float s = sin(uAngle);
                    vec2 sourcePx = vec2(c * outputPx.x + s * outputPx.y,
                        -s * outputPx.x + c * outputPx.y) / uScale;
                    vec2 sourceUv = clamp(vec2(0.5 + sourcePx.x / uFrameSize.x,
                        0.5 - sourcePx.y / uFrameSize.y), vec2(0.001), vec2(0.999));
                    gl_FragColor = texture2D(uTexture, (uTextureMatrix * vec4(sourceUv, 0.0, 1.0)).xy);
                }
            """.trimIndent())
            program = GLES20.glCreateProgram()
            GLES20.glAttachShader(program, vertex)
            GLES20.glAttachShader(program, fragment)
            GLES20.glLinkProgram(program)
            val linked = IntArray(1)
            GLES20.glGetProgramiv(program, GLES20.GL_LINK_STATUS, linked, 0)
            check(linked[0] == GLES20.GL_TRUE) { GLES20.glGetProgramInfoLog(program) }
            GLES20.glDeleteShader(vertex)
            GLES20.glDeleteShader(fragment)
        } catch (e: Throwable) {
            close()
            throw e
        }
    }

    fun draw(shift: MotionPhotoMotion.Shift, timestampUs: Long) {
        synchronized(lock) {
            val deadline = android.os.SystemClock.elapsedRealtime() + 1_000L
            while (!frameAvailable) {
                val remaining = deadline - android.os.SystemClock.elapsedRealtime()
                check(remaining > 0) { "decoder surface frame timeout" }
                lock.wait(remaining)
            }
            frameAvailable = false
        }
        surfaceTexture.updateTexImage()
        val matrix = FloatArray(16)
        surfaceTexture.getTransformMatrix(matrix)
        GLES20.glViewport(0, 0, width, height)
        GLES20.glUseProgram(program)
        GLES20.glActiveTexture(GLES20.GL_TEXTURE0)
        GLES20.glBindTexture(GLES11Ext.GL_TEXTURE_EXTERNAL_OES, texture)
        GLES20.glUniform1i(GLES20.glGetUniformLocation(program, "uTexture"), 0)
        GLES20.glUniformMatrix4fv(GLES20.glGetUniformLocation(program, "uTextureMatrix"), 1, false, matrix, 0)
        GLES20.glUniform2f(GLES20.glGetUniformLocation(program, "uOffset"),
            shift.x * width / 256f, shift.y * height / analysisHeight)
        GLES20.glUniform2f(GLES20.glGetUniformLocation(program, "uFrameSize"), width.toFloat(), height.toFloat())
        GLES20.glUniform1f(GLES20.glGetUniformLocation(program, "uAngle"), shift.rotationRadians)
        GLES20.glUniform1f(GLES20.glGetUniformLocation(program, "uScale"), shift.scale)
        GLES20.glUniform1f(GLES20.glGetUniformLocation(program, "uCrop"), cropScale)
        vertices.position(0)
        val location = GLES20.glGetAttribLocation(program, "aPosition")
        GLES20.glEnableVertexAttribArray(location)
        GLES20.glVertexAttribPointer(location, 2, GLES20.GL_FLOAT, false, 0, vertices)
        GLES20.glDrawArrays(GLES20.GL_TRIANGLE_STRIP, 0, 4)
        GLES20.glDisableVertexAttribArray(location)
        check(GLES20.glGetError() == GLES20.GL_NO_ERROR) { "warp draw failed" }
        check(EGLExt.eglPresentationTimeANDROID(display, eglSurface, timestampUs * 1_000))
        check(EGL14.eglSwapBuffers(display, eglSurface)) { "encoder surface swap failed" }
    }

    private fun shader(type: Int, source: String): Int {
        val id = GLES20.glCreateShader(type)
        GLES20.glShaderSource(id, source)
        GLES20.glCompileShader(id)
        val compiled = IntArray(1)
        GLES20.glGetShaderiv(id, GLES20.GL_COMPILE_STATUS, compiled, 0)
        check(compiled[0] == GLES20.GL_TRUE) { GLES20.glGetShaderInfoLog(id) }
        return id
    }

    override fun close() {
        if (::decoderSurface.isInitialized) decoderSurface.release()
        if (::surfaceTexture.isInitialized) surfaceTexture.release()
        if (program != 0) GLES20.glDeleteProgram(program)
        if (texture != 0) GLES20.glDeleteTextures(1, intArrayOf(texture), 0)
        EGL14.eglMakeCurrent(display, EGL14.EGL_NO_SURFACE, EGL14.EGL_NO_SURFACE, EGL14.EGL_NO_CONTEXT)
        if (eglSurface != EGL14.EGL_NO_SURFACE) EGL14.eglDestroySurface(display, eglSurface)
        if (context != EGL14.EGL_NO_CONTEXT) EGL14.eglDestroyContext(display, context)
        EGL14.eglTerminate(display)
    }
}
