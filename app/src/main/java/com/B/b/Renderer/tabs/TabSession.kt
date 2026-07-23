package com.B.b.Renderer.tabs

import com.B.b.Renderer.core.Element
import com.B.b.Renderer.htmx.HtmxRenderEngine
import com.B.b.Renderer.js.JsEngine
import com.B.b.Renderer.layout.LayoutEngine

/**
 * 1タブ分の状態一式。
 *
 * `pinned`(生存させ続けるか)と`showAsPip`(小窓として画面に映すか)は独立したフラグにしている:
 *   - pinned=false: 非フォアグラウンドになったら完全休止(エンジン一式を破棄、URLだけ保持)
 *   - pinned=true, showAsPip=false: JS/タイマー/メディア再生は裏で動き続けるが、画面には出ない
 *   - pinned=true, showAsPip=true:  上記に加えて小窓(PiP)として画面に表示する
 *
 * JsEngineを破棄しない限り、setTimeout/setInterval(Handler経由)やExoPlayerの再生は
 * フォアグラウンドかどうかに関わらず動き続ける。つまり「pinnedにする」= 「破棄しない」
 * だけで、裏で動き続けるという要件はほぼ自動的に満たされる。
 */
class TabSession(
    var url: String,
    val root: Element,
    val layoutEngine: LayoutEngine,
    val jsEngine: JsEngine,
    val htmxEngine: HtmxRenderEngine,
    val jsDomContext: com.B.b.Renderer.js.JsDomContext,
    val onHtmxTrigger: (Element) -> Unit,
) {
    var pinned: Boolean = false
    var showAsPip: Boolean = false

    /** PiP表示用の小さな描画View。showAsPip=trueの間だけ実体を持つ(CPU/Canvas固定、発熱対策)。 */
    var pipHostView: com.B.b.Renderer.render.EngineHostView? = null

    /** <title>から取れた実タイトル。取れなかった/空だった場合はhost名にフォールバックする。 */
    var pageTitle: String? = null

    val title: String
        get() = pageTitle?.takeIf { it.isNotBlank() }
            ?: runCatching { java.net.URI(url).host }.getOrNull()
            ?: url

    fun dispose() {
        jsEngine.dispose()
    }
}
