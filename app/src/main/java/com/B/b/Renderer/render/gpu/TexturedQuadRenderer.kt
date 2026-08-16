package com.B.b.Renderer.render.gpu

import android.opengl.GLES30
import java.nio.ByteBuffer
import java.nio.ByteOrder
import java.nio.FloatBuffer

/**
 * TextTextureCacheが生成したテクスチャを矩形として描画する。
 * テキストごとにテクスチャが異なるため単色バッチとは別扱い(1テクスチャにつき1drawCall)。
 */
class TexturedQuadRenderer {

    private var program = 0
    private var mvpHandle = 0
    private var texHandle = 0
    private val quadBuffer: FloatBuffer

    private val vertexShaderSrc = """
        #version 300 es
        layout(location = 0) in vec2 aPosition;
        layout(location = 1) in vec2 aTexCoord;
        uniform mat4 uMvp;
        out vec2 vTexCoord;
        void main() {
            gl_Position = uMvp * vec4(aPosition, 0.0, 1.0);
            vTexCoord = aTexCoord;
        }
    """.trimIndent()

    private val fragmentShaderSrc = """
        #version 300 es
        precision mediump float;
        in vec2 vTexCoord;
        uniform sampler2D uTexture;
        out vec4 fragColor;
        void main() {
            fragColor = texture(uTexture, vTexCoord);
        }
    """.trimIndent()

    init {
        val bb = ByteBuffer.allocateDirect(6 * 4 * 4).order(ByteOrder.nativeOrder())
        quadBuffer = bb.asFloatBuffer()
    }

    fun init() {
        program = GpuShaderUtil.buildProgram(vertexShaderSrc, fragmentShaderSrc)
        mvpHandle = GLES30.glGetUniformLocation(program, "uMvp")
        texHandle = GLES30.glGetUniformLocation(program, "uTexture")
    }

    fun draw(x: Float, y: Float, width: Float, height: Float, textureId: Int, mvpMatrix: FloatArray) {
        val x0 = x; val y0 = y; val x1 = x + width; val y1 = y + height
        val verts = floatArrayOf(
            x0, y0, 0f, 0f,
            x1, y0, 1f, 0f,
            x0, y1, 0f, 1f,

            x1, y0, 1f, 0f,
            x1, y1, 1f, 1f,
            x0, y1, 0f, 1f,
        )
        quadBuffer.clear()
        quadBuffer.put(verts)
        quadBuffer.position(0)

        GLES30.glUseProgram(program)
        GLES30.glUniformMatrix4fv(mvpHandle, 1, false, mvpMatrix, 0)

        GLES30.glActiveTexture(GLES30.GL_TEXTURE0)
        GLES30.glBindTexture(GLES30.GL_TEXTURE_2D, textureId)
        GLES30.glUniform1i(texHandle, 0)

        GLES30.glEnable(GLES30.GL_BLEND)
        GLES30.glBlendFunc(GLES30.GL_SRC_ALPHA, GLES30.GL_ONE_MINUS_SRC_ALPHA)

        val stride = 4 * 4
        quadBuffer.position(0)
        GLES30.glEnableVertexAttribArray(0)
        GLES30.glVertexAttribPointer(0, 2, GLES30.GL_FLOAT, false, stride, quadBuffer)

        quadBuffer.position(2)
        GLES30.glEnableVertexAttribArray(1)
        GLES30.glVertexAttribPointer(1, 2, GLES30.GL_FLOAT, false, stride, quadBuffer)

        GLES30.glDrawArrays(GLES30.GL_TRIANGLES, 0, 6)

        GLES30.glDisableVertexAttribArray(0)
        GLES30.glDisableVertexAttribArray(1)
        GLES30.glDisable(GLES30.GL_BLEND)
    }
}
