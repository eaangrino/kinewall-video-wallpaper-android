package com.eaangrino.kinewall

import android.graphics.SurfaceTexture
import android.opengl.EGL14
import android.opengl.EGLConfig
import android.opengl.EGLContext
import android.opengl.EGLDisplay
import android.opengl.EGLSurface
import android.opengl.GLES11Ext
import android.opengl.GLES20
import android.os.Handler
import android.os.HandlerThread
import android.view.Surface
import java.nio.ByteBuffer
import java.nio.ByteOrder
import java.nio.FloatBuffer
import java.util.concurrent.atomic.AtomicBoolean

internal class VideoFrameRenderer(
    private val onError: (stage: String, error: Throwable?) -> Unit
) {

    private val renderThread = HandlerThread("KinewallVideoRenderer").apply { start() }
    private val renderHandler = Handler(renderThread.looper)
    private val released = AtomicBoolean(false)

    private var eglDisplay: EGLDisplay = EGL14.EGL_NO_DISPLAY
    private var eglContext: EGLContext = EGL14.EGL_NO_CONTEXT
    private var eglSurface: EGLSurface = EGL14.EGL_NO_SURFACE
    private var shaderProgram = 0
    private var positionLocation = -1
    private var textureLocation = -1
    private var matrixLocation = -1
    private var cropScaleLocation = -1
    private var cropShiftLocation = -1
    private var samplerLocation = -1
    private var textureId = 0
    private var surfaceTexture: SurfaceTexture? = null
    private var inputSurface: Surface? = null

    private var outputWidth = 0
    private var outputHeight = 0
    private var videoWidth = 0
    private var videoHeight = 0
    private var scaleMode = SCALE_MODE_CROP
    private var cropPositionX = 0f
    private var cropPositionY = 0f

    private val textureTransform = FloatArray(16)
    private val vertexBuffer = floatBufferOf(
        -1f, -1f,
        1f, -1f,
        -1f, 1f,
        1f, 1f
    )
    private val textureBuffer = floatBufferOf(
        0f, 0f,
        1f, 0f,
        0f, 1f,
        1f, 1f
    )

    fun attach(outputSurface: Surface, onReady: (Surface) -> Unit) {
        post {
            try {
                initialize(outputSurface)
                val readySurface = inputSurface ?: error("Video input surface was not created")
                onReady(readySurface)
            } catch (error: Throwable) {
                onError("initialize", error)
                released.set(true)
                releaseGlResources()
                renderThread.quitSafely()
            }
        }
    }

    fun setOutputSize(width: Int, height: Int) {
        post {
            outputWidth = width.coerceAtLeast(0)
            outputHeight = height.coerceAtLeast(0)
        }
    }

    fun setVideoSize(width: Int, height: Int) {
        post {
            videoWidth = width.coerceAtLeast(0)
            videoHeight = height.coerceAtLeast(0)
            if (videoWidth > 0 && videoHeight > 0) {
                surfaceTexture?.setDefaultBufferSize(videoWidth, videoHeight)
            }
        }
    }

    fun setScaleMode(mode: String) {
        post {
            scaleMode = mode
        }
    }

    fun setCropPosition(x: Float, y: Float) {
        post {
            cropPositionX = x.coerceIn(-1f, 1f)
            cropPositionY = y.coerceIn(-1f, 1f)
        }
    }

    fun release() {
        if (!released.compareAndSet(false, true)) {
            return
        }

        renderHandler.post {
            releaseGlResources()
            renderThread.quitSafely()
        }
    }

    private fun post(block: () -> Unit) {
        if (released.get()) {
            return
        }
        renderHandler.post {
            if (!released.get()) {
                block()
            }
        }
    }

    private fun initialize(outputSurface: Surface) {
        eglDisplay = EGL14.eglGetDisplay(EGL14.EGL_DEFAULT_DISPLAY)
        if (eglDisplay == EGL14.EGL_NO_DISPLAY) {
            error("Unable to acquire EGL display")
        }

        val versions = IntArray(2)
        if (!EGL14.eglInitialize(eglDisplay, versions, 0, versions, 1)) {
            error("Unable to initialize EGL")
        }

        val configAttributes = intArrayOf(
            EGL14.EGL_RED_SIZE, 8,
            EGL14.EGL_GREEN_SIZE, 8,
            EGL14.EGL_BLUE_SIZE, 8,
            EGL14.EGL_ALPHA_SIZE, 8,
            EGL14.EGL_SURFACE_TYPE, EGL14.EGL_WINDOW_BIT,
            EGL14.EGL_RENDERABLE_TYPE, EGL14.EGL_OPENGL_ES2_BIT,
            EGL14.EGL_NONE
        )
        val configs = arrayOfNulls<EGLConfig>(1)
        val configCount = IntArray(1)
        if (
            !EGL14.eglChooseConfig(
                eglDisplay,
                configAttributes,
                0,
                configs,
                0,
                configs.size,
                configCount,
                0
            ) || configCount[0] == 0
        ) {
            error("Unable to choose EGL config")
        }
        val config = configs[0] ?: error("EGL config was null")

        eglContext = EGL14.eglCreateContext(
            eglDisplay,
            config,
            EGL14.EGL_NO_CONTEXT,
            intArrayOf(
                EGL14.EGL_CONTEXT_CLIENT_VERSION, 2,
                EGL14.EGL_NONE
            ),
            0
        )
        if (eglContext == EGL14.EGL_NO_CONTEXT) {
            error("Unable to create EGL context")
        }

        eglSurface = EGL14.eglCreateWindowSurface(
            eglDisplay,
            config,
            outputSurface,
            intArrayOf(EGL14.EGL_NONE),
            0
        )
        if (eglSurface == EGL14.EGL_NO_SURFACE) {
            error("Unable to create EGL window surface")
        }

        if (!EGL14.eglMakeCurrent(eglDisplay, eglSurface, eglSurface, eglContext)) {
            error("Unable to make EGL context current")
        }

        shaderProgram = createProgram(VERTEX_SHADER, FRAGMENT_SHADER)
        positionLocation = GLES20.glGetAttribLocation(shaderProgram, "aPosition")
        textureLocation = GLES20.glGetAttribLocation(shaderProgram, "aTexCoord")
        matrixLocation = GLES20.glGetUniformLocation(shaderProgram, "uTexMatrix")
        cropScaleLocation = GLES20.glGetUniformLocation(shaderProgram, "uCropScale")
        cropShiftLocation = GLES20.glGetUniformLocation(shaderProgram, "uCropShift")
        samplerLocation = GLES20.glGetUniformLocation(shaderProgram, "uTexture")
        if (
            positionLocation < 0 ||
            textureLocation < 0 ||
            matrixLocation < 0 ||
            cropScaleLocation < 0 ||
            cropShiftLocation < 0 ||
            samplerLocation < 0
        ) {
            error("Unable to resolve video shader locations")
        }

        val textures = IntArray(1)
        GLES20.glGenTextures(1, textures, 0)
        textureId = textures[0]
        if (textureId == 0) {
            error("Unable to allocate external video texture")
        }

        GLES20.glBindTexture(GLES11Ext.GL_TEXTURE_EXTERNAL_OES, textureId)
        GLES20.glTexParameteri(
            GLES11Ext.GL_TEXTURE_EXTERNAL_OES,
            GLES20.GL_TEXTURE_MIN_FILTER,
            GLES20.GL_LINEAR
        )
        GLES20.glTexParameteri(
            GLES11Ext.GL_TEXTURE_EXTERNAL_OES,
            GLES20.GL_TEXTURE_MAG_FILTER,
            GLES20.GL_LINEAR
        )
        GLES20.glTexParameteri(
            GLES11Ext.GL_TEXTURE_EXTERNAL_OES,
            GLES20.GL_TEXTURE_WRAP_S,
            GLES20.GL_CLAMP_TO_EDGE
        )
        GLES20.glTexParameteri(
            GLES11Ext.GL_TEXTURE_EXTERNAL_OES,
            GLES20.GL_TEXTURE_WRAP_T,
            GLES20.GL_CLAMP_TO_EDGE
        )
        checkGlError("configure_external_texture")

        val newSurfaceTexture = SurfaceTexture(textureId)
        surfaceTexture = newSurfaceTexture
        newSurfaceTexture.setOnFrameAvailableListener(
            {
                if (!released.get()) {
                    renderFrame()
                }
            },
            renderHandler
        )
        inputSurface = Surface(newSurfaceTexture)
    }

    private fun renderFrame() {
        val currentSurfaceTexture = surfaceTexture ?: return
        if (eglDisplay == EGL14.EGL_NO_DISPLAY || eglSurface == EGL14.EGL_NO_SURFACE) {
            return
        }

        try {
            currentSurfaceTexture.updateTexImage()
            currentSurfaceTexture.getTransformMatrix(textureTransform)

            if (outputWidth <= 0 || outputHeight <= 0) {
                return
            }

            GLES20.glViewport(0, 0, outputWidth, outputHeight)
            GLES20.glClearColor(0f, 0f, 0f, 1f)
            GLES20.glClear(GLES20.GL_COLOR_BUFFER_BIT)
            GLES20.glUseProgram(shaderProgram)

            vertexBuffer.position(0)
            GLES20.glEnableVertexAttribArray(positionLocation)
            GLES20.glVertexAttribPointer(
                positionLocation,
                2,
                GLES20.GL_FLOAT,
                false,
                0,
                vertexBuffer
            )

            textureBuffer.position(0)
            GLES20.glEnableVertexAttribArray(textureLocation)
            GLES20.glVertexAttribPointer(
                textureLocation,
                2,
                GLES20.GL_FLOAT,
                false,
                0,
                textureBuffer
            )

            GLES20.glUniformMatrix4fv(matrixLocation, 1, false, textureTransform, 0)

            val crop = calculateCrop()
            GLES20.glUniform2f(cropScaleLocation, crop.scaleX, crop.scaleY)
            GLES20.glUniform2f(cropShiftLocation, crop.shiftX, crop.shiftY)

            GLES20.glActiveTexture(GLES20.GL_TEXTURE0)
            GLES20.glBindTexture(GLES11Ext.GL_TEXTURE_EXTERNAL_OES, textureId)
            GLES20.glUniform1i(samplerLocation, 0)
            GLES20.glDrawArrays(GLES20.GL_TRIANGLE_STRIP, 0, 4)
            GLES20.glDisableVertexAttribArray(positionLocation)
            GLES20.glDisableVertexAttribArray(textureLocation)
            checkGlError("draw_frame")

            if (!EGL14.eglSwapBuffers(eglDisplay, eglSurface)) {
                error("eglSwapBuffers failed: 0x${EGL14.eglGetError().toString(16)}")
            }
        } catch (error: Throwable) {
            if (!released.get()) {
                onError("render_frame", error)
            }
        }
    }

    private fun calculateCrop(): CropTransform {
        if (
            scaleMode == SCALE_MODE_STRETCH ||
            videoWidth <= 0 ||
            videoHeight <= 0 ||
            outputWidth <= 0 ||
            outputHeight <= 0
        ) {
            return CropTransform.IDENTITY
        }

        val videoAspectRatio = videoWidth.toFloat() / videoHeight.toFloat()
        val outputAspectRatio = outputWidth.toFloat() / outputHeight.toFloat()

        val scaleX: Float
        val scaleY: Float

        if (videoAspectRatio > outputAspectRatio) {
            scaleX = (outputAspectRatio / videoAspectRatio).coerceIn(0f, 1f)
            scaleY = 1f
        } else {
            scaleX = 1f
            scaleY = (videoAspectRatio / outputAspectRatio).coerceIn(0f, 1f)
        }

        return CropTransform(
            scaleX = scaleX,
            scaleY = scaleY,
            shiftX = cropPositionX * (1f - scaleX) * 0.5f,
            shiftY = cropPositionY * (1f - scaleY) * 0.5f
        )
    }

    private fun createProgram(vertexSource: String, fragmentSource: String): Int {
        val vertexShader = compileShader(GLES20.GL_VERTEX_SHADER, vertexSource)
        val fragmentShader = compileShader(GLES20.GL_FRAGMENT_SHADER, fragmentSource)
        val program = GLES20.glCreateProgram()
        if (program == 0) {
            error("Unable to create shader program")
        }

        GLES20.glAttachShader(program, vertexShader)
        GLES20.glAttachShader(program, fragmentShader)
        GLES20.glLinkProgram(program)

        val linkStatus = IntArray(1)
        GLES20.glGetProgramiv(program, GLES20.GL_LINK_STATUS, linkStatus, 0)
        GLES20.glDeleteShader(vertexShader)
        GLES20.glDeleteShader(fragmentShader)

        if (linkStatus[0] != GLES20.GL_TRUE) {
            val log = GLES20.glGetProgramInfoLog(program)
            GLES20.glDeleteProgram(program)
            error("Unable to link shader program: $log")
        }

        return program
    }

    private fun compileShader(type: Int, source: String): Int {
        val shader = GLES20.glCreateShader(type)
        if (shader == 0) {
            error("Unable to create shader type=$type")
        }

        GLES20.glShaderSource(shader, source)
        GLES20.glCompileShader(shader)

        val compileStatus = IntArray(1)
        GLES20.glGetShaderiv(shader, GLES20.GL_COMPILE_STATUS, compileStatus, 0)
        if (compileStatus[0] != GLES20.GL_TRUE) {
            val log = GLES20.glGetShaderInfoLog(shader)
            GLES20.glDeleteShader(shader)
            error("Unable to compile shader type=$type: $log")
        }

        return shader
    }

    private fun checkGlError(stage: String) {
        val error = GLES20.glGetError()
        if (error != GLES20.GL_NO_ERROR) {
            throw IllegalStateException("OpenGL error at $stage: 0x${error.toString(16)}")
        }
    }

    private fun releaseGlResources() {
        inputSurface?.release()
        inputSurface = null

        surfaceTexture?.setOnFrameAvailableListener(null)
        surfaceTexture?.release()
        surfaceTexture = null

        if (textureId != 0) {
            GLES20.glDeleteTextures(1, intArrayOf(textureId), 0)
            textureId = 0
        }

        if (shaderProgram != 0) {
            GLES20.glDeleteProgram(shaderProgram)
            shaderProgram = 0
            positionLocation = -1
            textureLocation = -1
            matrixLocation = -1
            cropScaleLocation = -1
            cropShiftLocation = -1
            samplerLocation = -1
        }

        if (eglDisplay != EGL14.EGL_NO_DISPLAY) {
            EGL14.eglMakeCurrent(
                eglDisplay,
                EGL14.EGL_NO_SURFACE,
                EGL14.EGL_NO_SURFACE,
                EGL14.EGL_NO_CONTEXT
            )

            if (eglSurface != EGL14.EGL_NO_SURFACE) {
                EGL14.eglDestroySurface(eglDisplay, eglSurface)
            }
            if (eglContext != EGL14.EGL_NO_CONTEXT) {
                EGL14.eglDestroyContext(eglDisplay, eglContext)
            }
            EGL14.eglTerminate(eglDisplay)
        }

        eglSurface = EGL14.EGL_NO_SURFACE
        eglContext = EGL14.EGL_NO_CONTEXT
        eglDisplay = EGL14.EGL_NO_DISPLAY
    }

    private data class CropTransform(
        val scaleX: Float,
        val scaleY: Float,
        val shiftX: Float,
        val shiftY: Float
    ) {
        companion object {
            val IDENTITY = CropTransform(1f, 1f, 0f, 0f)
        }
    }

    companion object {
        private const val SCALE_MODE_STRETCH = "stretch"
        private const val SCALE_MODE_CROP = "crop"

        private const val VERTEX_SHADER = """
            attribute vec4 aPosition;
            attribute vec2 aTexCoord;
            uniform mat4 uTexMatrix;
            uniform vec2 uCropScale;
            uniform vec2 uCropShift;
            varying vec2 vTexCoord;

            void main() {
                gl_Position = aPosition;
                vec2 transformed = (uTexMatrix * vec4(aTexCoord, 0.0, 1.0)).xy;
                vTexCoord = vec2(0.5) +
                    ((transformed - vec2(0.5)) * uCropScale) +
                    uCropShift;
            }
        """

        private const val FRAGMENT_SHADER = """
            #extension GL_OES_EGL_image_external : require
            precision mediump float;
            uniform samplerExternalOES uTexture;
            varying vec2 vTexCoord;

            void main() {
                gl_FragColor = texture2D(uTexture, vTexCoord);
            }
        """

        private fun floatBufferOf(vararg values: Float): FloatBuffer {
            return ByteBuffer.allocateDirect(values.size * Float.SIZE_BYTES)
                .order(ByteOrder.nativeOrder())
                .asFloatBuffer()
                .apply {
                    put(values)
                    position(0)
                }
        }
    }
}
