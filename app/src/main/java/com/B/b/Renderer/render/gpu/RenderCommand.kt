package com.B.b.Renderer.render.gpu

sealed class DrawCommand {
    data class Quad(
        val x: Float,
        val y: Float,
        val width: Float,
        val height: Float,
        val r: Float,
        val g: Float,
        val b: Float,
        val a: Float,
    ) : DrawCommand()

    data class TexturedQuad(
        val x: Float,
        val y: Float,
        val width: Float,
        val height: Float,
        val textureId: Int,
    ) : DrawCommand()
}
