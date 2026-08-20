package com.B.b.Renderer.render

import android.graphics.Canvas
import android.graphics.Paint
import android.graphics.RectF
import com.B.b.Renderer.input.TextSelectionState

class SelectionOverlayRenderer {

    private val highlightPaint = Paint().apply {
        color = 0x660078D7.toInt() // 半透明の青色 (RGBA: 0, 120, 215, 0.4)
        style = Paint.Style.FILL
    }

    private val handlePaint = Paint().apply {
        color = 0xFF0078D7.toInt() // 濃い青色
        style = Paint.Style.FILL
        isAntiAlias = true
    }

    /**
     * Canvas 描画パス（CPUフォールバック / EngineView 用）
     */
    fun drawSelectionOverlay(canvas: Canvas, state: TextSelectionState) {
        if (state.mode == com.B.b.Renderer.input.SelectionMode.NONE) return

        // 1. 選択ハイライト矩形の描画
        for (rect in state.highlightRects) {
            canvas.drawRect(rect, highlightPaint)
        }

        // 2. テキスト直下のハンドル描画 (指で隠れないようテキスト下端のさらに外側に配置)
        if (state.highlightRects.isNotEmpty()) {
            val firstRect = state.highlightRects.first()
            val lastRect = state.highlightRects.last()

            val handleRadius = 16f
            
            // 左ハンドル (開始位置の左下)
            canvas.drawCircle(firstRect.left, firstRect.bottom + handleRadius, handleRadius, handlePaint)
            
            // 右ハンドル (終了位置の右下)
            canvas.drawCircle(lastRect.right, lastRect.bottom + handleRadius, handleRadius, handlePaint)
        }
    }
}
