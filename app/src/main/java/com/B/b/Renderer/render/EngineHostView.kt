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

    // 2026-08、<a>タグのタップ遷移対応。「どのhrefへ行きたいか」を伝えるだけで、
    // 相対URL解決・実際のタブ内遷移(TabManager経由)はEngineActivity側の責務とする
    // (onHtmxTriggerと同じ役割分担: Viewはユーザー操作の検知とDOM上の意味付けまで、
    // ナビゲーションの実行は常にActivity)。
    var onNavigate: ((String) -> Unit)?
}
