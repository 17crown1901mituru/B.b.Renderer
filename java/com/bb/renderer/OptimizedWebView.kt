package com.bb.renderer.web

import android.content.Context
import android.webkit.WebView
import android.graphics.Canvas
import com.bb.renderer.core.NativeRenderer

class OptimizedWebView(context: Context) : WebView(context) {
    private val nativeRenderer = NativeRenderer()

    override fun onDraw(canvas: Canvas?) {
        super.onDraw(canvas)
        // ここでB.b.Rendererの差分描画を割り込ませる
        renderDifferentialLayers()
    }

    private fun renderDifferentialLayers() {
        // 差分描画の実行ロジックを配置
    }
}
