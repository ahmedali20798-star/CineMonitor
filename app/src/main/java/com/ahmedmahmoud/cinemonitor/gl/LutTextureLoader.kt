package com.ahmedmahmoud.cinemonitor.gl

import android.opengl.GLES30
import java.nio.ByteBuffer
import java.nio.ByteOrder

object LutTextureLoader {

    fun uploadToGpu(lut: CubeLutData): Int {
        val textureIds = IntArray(1)
        GLES30.glGenTextures(1, textureIds, 0)
        val textureId = textureIds[0]

        if (textureId == 0) {
            throw RuntimeException("Failed to create 3D Texture in OpenGL ES 3.0")
        }

        GLES30.glBindTexture(GLES30.GL_TEXTURE_3D, textureId)
        GLES30.glTexParameteri(GLES30.GL_TEXTURE_3D, GLES30.GL_TEXTURE_MIN_FILTER, GLES30.GL_LINEAR)
        GLES30.glTexParameteri(GLES30.GL_TEXTURE_3D, GLES30.GL_TEXTURE_MAG_FILTER, GLES30.GL_LINEAR)
        GLES30.glTexParameteri(GLES30.GL_TEXTURE_3D, GLES30.GL_TEXTURE_WRAP_S, GLES30.GL_CLAMP_TO_EDGE)
        GLES30.glTexParameteri(GLES30.GL_TEXTURE_3D, GLES30.GL_TEXTURE_WRAP_T, GLES30.GL_CLAMP_TO_EDGE)
        GLES30.glTexParameteri(GLES30.GL_TEXTURE_3D, GLES30.GL_TEXTURE_WRAP_R, GLES30.GL_CLAMP_TO_EDGE)

        GLES30.glPixelStorei(GLES30.GL_UNPACK_ALIGNMENT, 1)

        GLES30.glTexImage3D(
            GLES30.GL_TEXTURE_3D,
            0,
            GLES30.GL_RGB16F,
            lut.size,
            lut.size,
            lut.size,
            0,
            GLES30.GL_RGB,
            GLES30.GL_FLOAT,
            lut.rgbBuffer
        )

        GLES30.glBindTexture(GLES30.GL_TEXTURE_3D, 0)
        return textureId
    }

    fun createIdentityLut(size: Int = 33): CubeLutData {
        val total = size * size * size
        val buffer = ByteBuffer
            .allocateDirect(total * 3 * 4)
            .order(ByteOrder.nativeOrder())
            .asFloatBuffer()

        val scale = 1.0f / (size - 1)
        for (b in 0 until size) {
            for (g in 0 until size) {
                for (r in 0 until size) {
                    buffer.put(r * scale)
                    buffer.put(g * scale)
                    buffer.put(b * scale)
                }
            }
        }
        buffer.position(0)
        return CubeLutData("Identity", size, floatArrayOf(0f, 0f, 0f), floatArrayOf(1f, 1f, 1f), buffer)
    }
}
