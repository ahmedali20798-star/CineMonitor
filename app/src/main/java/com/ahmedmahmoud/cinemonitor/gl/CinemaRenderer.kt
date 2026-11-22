package com.ahmedmahmoud.cinemonitor.gl

import android.graphics.SurfaceTexture
import android.opengl.GLES11Ext
import android.opengl.GLES30
import android.opengl.GLSurfaceView
import android.opengl.Matrix
import java.nio.ByteBuffer
import java.nio.ByteOrder
import java.nio.FloatBuffer
import javax.microedition.khronos.egl.EGLConfig
import javax.microedition.khronos.opengles.GL10

class CinemaRenderer(
    private val onSurfaceTextureReady: (SurfaceTexture) -> Unit
) : GLSurfaceView.Renderer {

    private var programId = 0
    private var oesTextureId = 0
    private var surfaceTexture: SurfaceTexture? = null
    private val stMatrix = FloatArray(16)

    private var currentLutTexId = 0
    private var currentLutSize = 33.0f
    private var domainMin = floatArrayOf(0f, 0f, 0f)
    private var domainMax = floatArrayOf(1f, 1f, 1f)

    @Volatile private var lutIntensity = 0.0f
    @Volatile private var falseColorEnabled = false
    @Volatile private var peakingEnabled = false
    @Volatile private var zebraEnabled = false
    @Volatile private var frameAvailable = false

    private val startTimeNs = System.nanoTime()

    private val quadVertices: FloatBuffer = ByteBuffer.allocateDirect(16 * 4)
        .order(ByteOrder.nativeOrder())
        .asFloatBuffer()
        .put(
            floatArrayOf(
                -1.0f, -1.0f, 0.0f, 0.0f,
                 1.0f, -1.0f, 1.0f, 0.0f,
                -1.0f,  1.0f, 0.0f, 1.0f,
                 1.0f,  1.0f, 1.0f, 1.0f
            )
        ).apply { position(0) }

    override fun onSurfaceCreated(gl: GL10?, config: EGLConfig?) {
        GLES30.glClearColor(0f, 0f, 0f, 1f)
        Matrix.setIdentityM(stMatrix, 0)

        programId = createShaderProgram(VERTEX_SHADER, FRAGMENT_SHADER)

        val textures = IntArray(1)
        GLES30.glGenTextures(1, textures, 0)
        oesTextureId = textures[0]

        GLES30.glBindTexture(GLES11Ext.GL_TEXTURE_EXTERNAL_OES, oesTextureId)
        GLES30.glTexParameteri(GLES11Ext.GL_TEXTURE_EXTERNAL_OES, GLES30.GL_TEXTURE_MIN_FILTER, GLES30.GL_LINEAR)
        GLES30.glTexParameteri(GLES11Ext.GL_TEXTURE_EXTERNAL_OES, GLES30.GL_TEXTURE_MAG_FILTER, GLES30.GL_LINEAR)
        GLES30.glTexParameteri(GLES11Ext.GL_TEXTURE_EXTERNAL_OES, GLES30.GL_TEXTURE_WRAP_S, GLES30.GL_CLAMP_TO_EDGE)
        GLES30.glTexParameteri(GLES11Ext.GL_TEXTURE_EXTERNAL_OES, GLES30.GL_TEXTURE_WRAP_T, GLES30.GL_CLAMP_TO_EDGE)

        currentLutTexId = LutTextureLoader.uploadToGpu(LutTextureLoader.createIdentityLut(33))

        surfaceTexture = SurfaceTexture(oesTextureId).apply {
            setDefaultBufferSize(1920, 1080)
            setOnFrameAvailableListener { frameAvailable = true }
        }
        surfaceTexture?.let { onSurfaceTextureReady(it) }
    }

    override fun onSurfaceChanged(gl: GL10?, width: Int, height: Int) {
        GLES30.glViewport(0, 0, width, height)
    }

    override fun onDrawFrame(gl: GL10?) {
        GLES30.glClear(GLES30.GL_COLOR_BUFFER_BIT)

        if (frameAvailable) {
            surfaceTexture?.updateTexImage()
            surfaceTexture?.getTransformMatrix(stMatrix)
            frameAvailable = false
        }

        GLES30.glUseProgram(programId)

        GLES30.glActiveTexture(GLES30.GL_TEXTURE0)
        GLES30.glBindTexture(GLES11Ext.GL_TEXTURE_EXTERNAL_OES, oesTextureId)
        GLES30.glUniform1i(GLES30.glGetUniformLocation(programId, "uCameraTexture"), 0)
        GLES30.glUniformMatrix4fv(GLES30.glGetUniformLocation(programId, "uSTMatrix"), 1, false, stMatrix, 0)

        GLES30.glActiveTexture(GLES30.GL_TEXTURE1)
        GLES30.glBindTexture(GLES30.GL_TEXTURE_3D, currentLutTexId)
        GLES30.glUniform1i(GLES30.glGetUniformLocation(programId, "uLutTexture"), 1)
        GLES30.glUniform1f(GLES30.glGetUniformLocation(programId, "uLutSize"), currentLutSize)
        GLES30.glUniform3fv(GLES30.glGetUniformLocation(programId, "uDomainMin"), 1, domainMin, 0)
        GLES30.glUniform3fv(GLES30.glGetUniformLocation(programId, "uDomainMax"), 1, domainMax, 0)
        GLES30.glUniform1f(GLES30.glGetUniformLocation(programId, "uLutIntensity"), lutIntensity)

        val elapsedSec = (System.nanoTime() - startTimeNs) / 1_000_000_000.0f
        GLES30.glUniform1i(GLES30.glGetUniformLocation(programId, "uFalseColorMode"), if (falseColorEnabled) 1 else 0)
        GLES30.glUniform1i(GLES30.glGetUniformLocation(programId, "uPeakingMode"), if (peakingEnabled) 1 else 0)
        GLES30.glUniform1f(GLES30.glGetUniformLocation(programId, "uPeakingThreshold"), 0.14f)
        GLES30.glUniform3f(GLES30.glGetUniformLocation(programId, "uPeakingColor"), 1.0f, 0.15f, 0.15f)
        GLES30.glUniform1i(GLES30.glGetUniformLocation(programId, "uZebraMode"), if (zebraEnabled) 1 else 0)
        GLES30.glUniform1f(GLES30.glGetUniformLocation(programId, "uZebraThreshold"), 0.85f)
        GLES30.glUniform1f(GLES30.glGetUniformLocation(programId, "uTime"), elapsedSec)

        quadVertices.position(0)
        GLES30.glVertexAttribPointer(0, 2, GLES30.GL_FLOAT, false, 16, quadVertices)
        GLES30.glEnableVertexAttribArray(0)

        quadVertices.position(2)
        GLES30.glVertexAttribPointer(1, 2, GLES30.GL_FLOAT, false, 16, quadVertices)
        GLES30.glEnableVertexAttribArray(1)

        GLES30.glDrawArrays(GLES30.GL_TRIANGLE_STRIP, 0, 4)
    }

    fun updateLutTexture(lutData: CubeLutData) {
        if (currentLutTexId != 0) {
            GLES30.glDeleteTextures(1, intArrayOf(currentLutTexId), 0)
        }
        currentLutTexId = LutTextureLoader.uploadToGpu(lutData)
        currentLutSize = lutData.size.toFloat()
        domainMin = lutData.domainMin
        domainMax = lutData.domainMax
    }

    fun setLutIntensity(intensity: Float) { lutIntensity = intensity.coerceIn(0f, 1f) }
    fun setFalseColorEnabled(enabled: Boolean) { falseColorEnabled = enabled }
    fun setPeakingEnabled(enabled: Boolean) { peakingEnabled = enabled }
    fun setZebraEnabled(enabled: Boolean) { zebraEnabled = enabled }

    private fun createShaderProgram(vertexSrc: String, fragmentSrc: String): Int {
        val vs = compileShader(GLES30.GL_VERTEX_SHADER, vertexSrc)
        val fs = compileShader(GLES30.GL_FRAGMENT_SHADER, fragmentSrc)
        return GLES30.glCreateProgram().also { prog ->
            GLES30.glAttachShader(prog, vs)
            GLES30.glAttachShader(prog, fs)
            GLES30.glLinkProgram(prog)
        }
    }

    private fun compileShader(type: Int, source: String): Int {
        return GLES30.glCreateShader(type).also { shader ->
            GLES30.glShaderSource(shader, source)
            GLES30.glCompileShader(shader)
        }
    }

    companion object {
        private const val VERTEX_SHADER = """
            #version 300 es
            layout(location = 0) in vec4 aPosition;
            layout(location = 1) in vec4 aTexCoord;
            uniform mat4 uSTMatrix;
            out vec2 vTexCoord;
            void main() {
                gl_Position = aPosition;
                vTexCoord = (uSTMatrix * aTexCoord).xy;
            }
        """

        private const val FRAGMENT_SHADER = """
            #version 300 es
            #extension GL_OES_EGL_image_external_essl3 : require
            precision highp float;
            precision highp sampler3D;

            in vec2 vTexCoord;
            out vec4 fragColor;

            uniform samplerExternalOES uCameraTexture;
            uniform sampler3D uLutTexture;
            uniform float uLutSize;
            uniform vec3 uDomainMin;
            uniform vec3 uDomainMax;
            uniform float uLutIntensity;

            uniform int uFalseColorMode;
            uniform int uPeakingMode;
            uniform float uPeakingThreshold;
            uniform vec3 uPeakingColor;
            uniform int uZebraMode;
            uniform float uZebraThreshold;
            uniform float uTime;

            float getLuminance(vec3 color) {
                return dot(color, vec3(0.2126, 0.7152, 0.0722));
            }

            vec3 applyFalseColor(float luma) {
                if (luma < 0.02) return vec3(0.25, 0.0, 0.5);
                if (luma < 0.15) return vec3(0.0, 0.2, 1.0);
                if (luma >= 0.41 && luma <= 0.48) return vec3(0.0, 0.85, 0.2);
                if (luma >= 0.52 && luma <= 0.58) return vec3(1.0, 0.45, 0.7);
                if (luma > 0.78 && luma <= 0.94) return vec3(1.0, 0.9, 0.0);
                if (luma > 0.94) return vec3(1.0, 0.0, 0.0);
                return vec3(luma);
            }

            void main() {
                vec3 color = texture(uCameraTexture, vTexCoord).rgb;

                if (uLutIntensity > 0.0) {
                    vec3 normColor = clamp((color - uDomainMin) / (uDomainMax - uDomainMin), 0.0, 1.0);
                    vec3 lutCoord = normColor * ((uLutSize - 1.0) / uLutSize) + (0.5 / uLutSize);
                    vec3 lutColor = texture(uLutTexture, lutCoord).rgb;
                    color = mix(color, lutColor, uLutIntensity);
                }

                float luma = getLuminance(color);

                if (uFalseColorMode == 1) {
                    color = applyFalseColor(luma);
                }

                if (uZebraMode == 1 && luma >= uZebraThreshold) {
                    float stripe = sin((gl_FragCoord.x + gl_FragCoord.y) * 0.4 - uTime * 8.0);
                    if (stripe > 0.0) {
                        color = mix(color, vec3(1.0, 0.1, 0.1), 0.65);
                    }
                }

                if (uPeakingMode == 1) {
                    vec2 texel = 1.0 / vec2(textureSize(uCameraTexture, 0));
                    float tl = getLuminance(texture(uCameraTexture, vTexCoord + vec2(-texel.x,  texel.y)).rgb);
                    float tr = getLuminance(texture(uCameraTexture, vTexCoord + vec2( texel.x,  texel.y)).rgb);
                    float bl = getLuminance(texture(uCameraTexture, vTexCoord + vec2(-texel.x, -texel.y)).rgb);
                    float br = getLuminance(texture(uCameraTexture, vTexCoord + vec2( texel.x, -texel.y)).rgb);
                    float edge = length(vec2((tr + br) - (tl + bl), (tl + tr) - (bl + br)));
                    if (edge > uPeakingThreshold) {
                        color = uPeakingColor;
                    }
                }

                fragColor = vec4(color, 1.0);
            }
        """
    }
}
