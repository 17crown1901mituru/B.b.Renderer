package com.B.b.Renderer.render

import com.B.b.Renderer.core.Element
import com.B.b.Renderer.layout.LayoutEngine

/**
 * EngineView(Canvas版)とGLEngineView(GPU版)の双方が実装する共通契約。
 * EngineActivity側はこのインターフェース越しに操作することで、
 * 描画バックエンドの違いを意識しなくて済む。
 */
interface EngineHostView {
    fun attach(engine: LayoutEngine)
    fun requestLayoutPass()
    var onHtmxTrigger: ((Element) -> Unit)?
}
