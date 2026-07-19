package com.B.b.Renderer.render.gpu

import android.opengl.GLES30
import java.nio.ByteBuffer
import java.nio.ByteOrder
import java.nio.FloatBuffer

/**
 * TextAtlasの1ページ分の描画をバッチ化する。addQuadを好きなだけ呼んでから
 * endBatchAndDrawを1回呼べば、そのページに属する全テキストが1 drawCallで出る。
 * ページが複数(テキスト量が多くアトラスが溢れた場合)なら、ページごとにこのサイクルを回す
 * ので、drawCall数は「テキスト要素数」ではなく「アトラスページ数」に比例するようになる。
 */
class AtlasQuadRenderer {

    private var program = 0
    private var mvpHandle = 0
    private var texHandle = 0
    private var vertexBuffer: FloatBuffer? = null
    private var pendingVertexCount = 0

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

    fun init() {
        program = GpuShaderUtil.buildProgram(vertexShaderSrc, fragmentShaderSrc)
        mvpHandle = GLES30.glGetUniformLocation(program, "uMvp")
        texHandle = GLES30.glGetUniformLocation(program, "uTexture")
    }

    /** maxQuadsはこのフレームでこのページに描く見込みの最大quad数(小さすぎるとoverflowするので余裕を持たせる) */
    fun beginBatch(maxQuads: Int) {
        val floatsPerVertex = 4 // x, y, u, v
        val verticesPerQuad = 6
        val needed = maxOf(maxQuads, 1) * verticesPerQuad * floatsPerVertex * 4
        val existing = vertexBuffer
        if (existing == null || existing.capacity() < maxOf(maxQuads, 1) * verticesPerQuad * floatsPerVertex) {
            val bb = ByteBuffer.allocateDirect(needed).order(ByteOrder.nativeOrder())
            vertexBuffer = bb.asFloatBuffer()
        }
        vertexBuffer?.clear()
        pendingVertexCount = 0
    }

    fun addQuad(x: Float, y: Float, width: Float, height: Float, region: TextAtlas.Region) {
        val buffer = vertexBuffer ?: return
        val x0 = x
        val y0 = y
        val x1 = x + width
        val y1 = y + height
        val verts = floatArrayOf(
            x0, y0, region.u0, region.v0,
            x1, y0, region.u1, region.v0,
            x0, y1, region.u0, region.v1,

            x1, y0, region.u1, region.v0,
            x1, y1, region.u1, region.v1,
            x0, y1, region.u0, region.v1,
        )
        buffer.put(verts)
        pendingVertexCount += 6
    }

    fun endBatchAndDraw(textureId: Int, mvpMatrix: FloatArray) {
        val buffer = vertexBuffer ?: return
        if (pendingVertexCount == 0) return
        buffer.position(0)

        GLES30.glUseProgram(program)
        GLES30.glUniformMatrix4fv(mvpHandle, 1, false, mvpMatrix, 0)

        GLES30.glActiveTexture(GLES30.GL_TEXTURE0)
        GLES30.glBindTexture(GLES30.GL_TEXTURE_2D, textureId)
        GLES30.glUniform1i(texHandle, 0)

        GLES30.glEnable(GLES30.GL_BLEND)
        GLES30.glBlendFunc(GLES30.GL_SRC_ALPHA, GLES30.GL_ONE_MINUS_SRC_ALPHA)

        val stride = 4 * 4
        buffer.position(0)
        GLES30.glEnableVertexAttribArray(0)
        GLES30.glVertexAttribPointer(0, 2, GLES30.GL_FLOAT, false, stride, buffer)

        buffer.position(2)
        GLES30.glEnableVertexAttribArray(1)
        GLES30.glVertexAttribPointer(1, 2, GLES30.GL_FLOAT, false, stride, buffer)

        GLES30.glDrawArrays(GLES30.GL_TRIANGLES, 0, pendingVertexCount)

        GLES30.glDisableVertexAttribArray(0)
        GLES30.glDisableVertexAttribArray(1)
        GLES30.glDisable(GLES30.GL_BLEND)
    }
}
