package com.B.b.Renderer.render.gpu

import android.opengl.GLES30
import java.nio.ByteBuffer
import java.nio.ByteOrder
import java.nio.FloatBuffer

/**
 * 背景色矩形をまとめて1回(ないし数回)のglDrawArraysで描画する。
 * 個別のdrawCallを積み上げず、フレーム開始時にバッファへ書き込んで
 * 最後に一括発行する設計。
 */
class QuadBatchRenderer {

    private var program = 0
    private var positionHandle = 0
    private var colorHandle = 0
    private var mvpHandle = 0

    private val vertexShaderSrc = """
        #version 300 es
        layout(location = 0) in vec2 aPosition;
        layout(location = 1) in vec4 aColor;
        uniform mat4 uMvp;
        out vec4 vColor;
        void main() {
            gl_Position = uMvp * vec4(aPosition, 0.0, 1.0);
            vColor = aColor;
        }
    """.trimIndent()

    private val fragmentShaderSrc = """
        #version 300 es
        precision mediump float;
        in vec4 vColor;
        out vec4 fragColor;
        void main() {
            fragColor = vColor;
        }
    """.trimIndent()

    private var vertexBuffer: FloatBuffer? = null
    private var pendingVertexCount = 0

    fun init() {
        program = GpuShaderUtil.buildProgram(vertexShaderSrc, fragmentShaderSrc)
        mvpHandle = GLES30.glGetUniformLocation(program, "uMvp")
    }

    fun beginFrame(maxQuads: Int) {
        val floatsPerVertex = 6 // x, y, r, g, b, a
        val verticesPerQuad = 6 // 2 triangles
        val capacity = maxQuads * verticesPerQuad * floatsPerVertex
        val bb = ByteBuffer.allocateDirect(capacity * 4).order(ByteOrder.nativeOrder())
        vertexBuffer = bb.asFloatBuffer()
        pendingVertexCount = 0
    }

    fun addQuad(x: Float, y: Float, width: Float, height: Float, r: Float, g: Float, b: Float, a: Float) {
        val buffer = vertexBuffer ?: return
        val x0 = x
        val y0 = y
        val x1 = x + width
        val y1 = y + height

        // 2枚の三角形でquadを構成
        val verts = floatArrayOf(
            x0, y0, r, g, b, a,
            x1, y0, r, g, b, a,
            x0, y1, r, g, b, a,

            x1, y0, r, g, b, a,
            x1, y1, r, g, b, a,
            x0, y1, r, g, b, a,
        )
        buffer.put(verts)
        pendingVertexCount += 6
    }

    fun endFrameAndDraw(mvpMatrix: FloatArray) {
        val buffer = vertexBuffer ?: return
        if (pendingVertexCount == 0) return
        buffer.position(0)

        GLES30.glUseProgram(program)
        GLES30.glUniformMatrix4fv(mvpHandle, 1, false, mvpMatrix, 0)

        val stride = 6 * 4
        buffer.position(0)
        GLES30.glEnableVertexAttribArray(0)
        GLES30.glVertexAttribPointer(0, 2, GLES30.GL_FLOAT, false, stride, buffer)

        buffer.position(2)
        GLES30.glEnableVertexAttribArray(1)
        GLES30.glVertexAttribPointer(1, 4, GLES30.GL_FLOAT, false, stride, buffer)

        GLES30.glDrawArrays(GLES30.GL_TRIANGLES, 0, pendingVertexCount)

        GLES30.glDisableVertexAttribArray(0)
        GLES30.glDisableVertexAttribArray(1)
    }
}

object GpuShaderUtil {
    fun buildProgram(vertexSrc: String, fragmentSrc: String): Int {
        val vertexShader = compileShader(GLES30.GL_VERTEX_SHADER, vertexSrc)
        val fragmentShader = compileShader(GLES30.GL_FRAGMENT_SHADER, fragmentSrc)

        val program = GLES30.glCreateProgram()
        GLES30.glAttachShader(program, vertexShader)
        GLES30.glAttachShader(program, fragmentShader)
        GLES30.glLinkProgram(program)

        val linkStatus = IntArray(1)
        GLES30.glGetProgramiv(program, GLES30.GL_LINK_STATUS, linkStatus, 0)
        if (linkStatus[0] == 0) {
            val log = GLES30.glGetProgramInfoLog(program)
            GLES30.glDeleteProgram(program)
            throw RuntimeException("Shader program link failed: $log")
        }

        GLES30.glDeleteShader(vertexShader)
        GLES30.glDeleteShader(fragmentShader)
        return program
    }

    private fun compileShader(type: Int, source: String): Int {
        val shader = GLES30.glCreateShader(type)
        GLES30.glShaderSource(shader, source)
        GLES30.glCompileShader(shader)

        val compileStatus = IntArray(1)
        GLES30.glGetShaderiv(shader, GLES30.GL_COMPILE_STATUS, compileStatus, 0)
        if (compileStatus[0] == 0) {
            val log = GLES30.glGetShaderInfoLog(shader)
            GLES30.glDeleteShader(shader)
            throw RuntimeException("Shader compile failed: $log")
        }
        return shader
    }
}
