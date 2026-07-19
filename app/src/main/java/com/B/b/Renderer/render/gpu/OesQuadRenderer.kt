package com.B.b.Renderer.render.gpu

import android.opengl.GLES11Ext
import android.opengl.GLES30
import java.nio.ByteBuffer
import java.nio.ByteOrder
import java.nio.FloatBuffer

/**
 * SurfaceTexture(video用)をGL_TEXTURE_EXTERNAL_OES経由でそのまま描画する。
 * 通常のTexturedQuadRenderer(sampler2D、テキストテクスチャ用)とはサンプラー型が異なるため
 * 別プログラムとして分離している。
 *
 * SurfaceTexture.getTransformMatrix()が返す4x4行列でUVを補正する必要がある
 * (デコーダ/端末によって上下反転やクロップが入るため、単純な0..1 UVでは映像が崩れる)。
 */
class OesQuadRenderer {

    private var program = 0
    private var mvpHandle = 0
    private var texMatrixHandle = 0
    private var texHandle = 0
    private val quadBuffer: FloatBuffer

    private val vertexShaderSrc = """
        #version 300 es
        layout(location = 0) in vec2 aPosition;
        layout(location = 1) in vec2 aTexCoord;
        uniform mat4 uMvp;
        uniform mat4 uTexMatrix;
        out vec2 vTexCoord;
        void main() {
            gl_Position = uMvp * vec4(aPosition, 0.0, 1.0);
            vTexCoord = (uTexMatrix * vec4(aTexCoord, 0.0, 1.0)).xy;
        }
    """.trimIndent()

    private val fragmentShaderSrc = """
        #version 300 es
        #extension GL_OES_EGL_image_external_essl3 : require
        precision mediump float;
        in vec2 vTexCoord;
        uniform samplerExternalOES uTexture;
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
        texMatrixHandle = GLES30.glGetUniformLocation(program, "uTexMatrix")
        texHandle = GLES30.glGetUniformLocation(program, "uTexture")
    }

    fun draw(
        x: Float,
        y: Float,
        width: Float,
        height: Float,
        oesTextureId: Int,
        texMatrix: FloatArray,
        mvpMatrix: FloatArray,
    ) {
        val x0 = x
        val y0 = y
        val x1 = x + width
        val y1 = y + height
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
        GLES30.glUniformMatrix4fv(texMatrixHandle, 1, false, texMatrix, 0)

        GLES30.glActiveTexture(GLES30.GL_TEXTURE0)
        GLES30.glBindTexture(GLES11Ext.GL_TEXTURE_EXTERNAL_OES, oesTextureId)
        GLES30.glUniform1i(texHandle, 0)

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
    }

    /**
     * OES外部テクスチャを1枚生成して返す。必ずGLスレッド上で呼ぶこと
     * (SurfaceTextureのコンストラクタにそのままIDとして渡せる)。
     */
    fun createOesTexture(): Int {
        val ids = IntArray(1)
        GLES30.glGenTextures(1, ids, 0)
        GLES30.glBindTexture(GLES11Ext.GL_TEXTURE_EXTERNAL_OES, ids[0])
        GLES30.glTexParameteri(GLES11Ext.GL_TEXTURE_EXTERNAL_OES, GLES30.GL_TEXTURE_MIN_FILTER, GLES30.GL_LINEAR)
        GLES30.glTexParameteri(GLES11Ext.GL_TEXTURE_EXTERNAL_OES, GLES30.GL_TEXTURE_MAG_FILTER, GLES30.GL_LINEAR)
        GLES30.glTexParameteri(GLES11Ext.GL_TEXTURE_EXTERNAL_OES, GLES30.GL_TEXTURE_WRAP_S, GLES30.GL_CLAMP_TO_EDGE)
        GLES30.glTexParameteri(GLES11Ext.GL_TEXTURE_EXTERNAL_OES, GLES30.GL_TEXTURE_WRAP_T, GLES30.GL_CLAMP_TO_EDGE)
        return ids[0]
    }

    fun deleteTextures(ids: IntArray) {
        if (ids.isEmpty()) return
        GLES30.glDeleteTextures(ids.size, ids, 0)
    }
}
