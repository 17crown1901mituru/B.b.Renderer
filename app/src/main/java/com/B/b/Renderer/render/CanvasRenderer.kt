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
            // 2026-08、<a>タグのデフォルト下線対応。GLEngineRenderer側(StaticLayout+
            // TextPaint.isUnderlineText)と同じPaint標準機能で揃える。
            textPaint.isUnderlineText = style.textDecoration == com.B.b.Renderer.style.TextDecoration.UNDERLINE
            canvas.drawText(
                text,
                rect.x.toFloat() + resolveEdgeApprox(style.padding.left, rect),
                rect.y.toFloat() + resolveEdgeApprox(style.padding.top, rect) + style.fontSize,
                textPaint,
            )
        }
    }

    /**
     * padding%の簡易解決(2026-08、margin/paddingの%対応にあわせた修正)。
     * 本来CSS仕様ではcontaining blockの幅が基準だが、この暫定レンダラーは
     * LayoutEngineが既に確定させたcomputedRectしか持っておらず、元のcontaining
     * block幅を辿れない。そのため要素自身のrect.width(=既にpadding込みで確定済みの
     * 幅)を基準に近似する——GPU本実装(GLEngineRenderer)側はLayoutEngine.resolveEdge()で
     * 正しくcontaining block幅を基準に解決しているため、この近似のズレはPiPプレビュー
     * (このCanvasRendererの主な用途)でのみ発生し、実際のページ表示には影響しない。
     */
    private fun resolveEdgeApprox(value: com.B.b.Renderer.style.CssValue, rect: com.B.b.Renderer.core.LayoutRect): Float = when (value) {
        is com.B.b.Renderer.style.CssValue.Px -> value.value
        is com.B.b.Renderer.style.CssValue.Percent -> rect.width * (value.value / 100f)
        com.B.b.Renderer.style.CssValue.Auto -> 0f
    }
}
