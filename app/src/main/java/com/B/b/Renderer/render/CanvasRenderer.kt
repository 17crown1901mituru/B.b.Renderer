package com.B.b.Renderer.render

import android.graphics.Canvas
import android.graphics.Color as AndroidColor
import android.graphics.Paint
import com.B.b.Renderer.core.Element
import com.B.b.Renderer.core.TextNode
import com.B.b.Renderer.input.resolvePaintOrder
import com.B.b.Renderer.style.Display

/**
 * 暫定レンダラー。GPU本実装(GLSurfaceView + wgpu相当のバッチ描画)が
 * 完成するまでの間、動作確認用にsoftware Canvasで描画する。
 * paintOrder(stackingContext由来の重なり順)はここで既に反映済みの前提で走査する。
 */
class CanvasRenderer {
    private val boxPaint = Paint().apply { style = Paint.Style.FILL }
    private val textPaint = Paint().apply { isAntiAlias = true }

    fun render(canvas: Canvas, root: Element) {
        canvas.drawColor(AndroidColor.WHITE)
        val paintOrder = resolvePaintOrder(root)
        paintOrder.forEach { element -> drawElement(canvas, element) }
    }

    private fun drawElement(canvas: Canvas, element: Element) {
        val style = element.computedStyle
        if (style.display == Display.NONE) return

        val rect = element.computedRect
        if (style.backgroundColor.a > 0) {
            boxPaint.color = AndroidColor.argb(
                style.backgroundColor.a,
                style.backgroundColor.r,
                style.backgroundColor.g,
                style.backgroundColor.b,
            )
            canvas.drawRect(
                rect.x.toFloat(),
                rect.y.toFloat(),
                (rect.x + rect.width).toFloat(),
                (rect.y + rect.height).toFloat(),
                boxPaint,
            )
        }

        val text = element.children.filterIsInstance<TextNode>().joinToString(" ") { it.data.trim() }
        if (text.isNotBlank()) {
            textPaint.color = AndroidColor.argb(
                style.color.a, style.color.r, style.color.g, style.color.b,
            )
            textPaint.textSize = style.fontSize
            canvas.drawText(
                text,
                rect.x.toFloat() + style.padding.left,
                rect.y.toFloat() + style.padding.top + style.fontSize,
                textPaint,
            )
        }
    }
}
