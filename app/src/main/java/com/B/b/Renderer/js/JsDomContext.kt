package com.B.b.Renderer.js

import com.B.b.Renderer.core.Element
import com.B.b.Renderer.core.HtmlFragmentParser
import com.B.b.Renderer.layout.LayoutEngine
import com.B.b.Renderer.style.ComputedStyle
import com.B.b.Renderer.style.StyleResolver

/**
 * JSからのDOM操作(setAttribute/class変更/innerHTML代入等)が起きた際に
 * レイアウト再計算・スタイル再解決・再描画をどう起動するかをまとめた窓口。
 *
 * Engine側(LayoutEngine/StyleResolver等)の実装は別セッションで進行中のため、
 * このクラスは具象クラスへの直接依存を最小限にし、コンストラクタで注入する形にしてある。
 * 統合時はEngineActivity(あるいはEngine側の対応するホストクラス)からこれを1つ生成して
 * JsEngineに渡すだけでよい。
 *
 * onHtmxTrigger: JS(`element.click()`)やdevice shortcuts(`shortcuts.tap(...)`)からの
 * クリックがhx-post/hx-get要素までバブリングした際に呼ばれる。EngineActivity側で
 * `htmxEngine.handleAction(...)` を呼ぶ既存のコールバックをそのまま渡せばよい
 * (ネイティブタップ・JS発火・device shortcuts発火の3経路が同じ処理に合流する)。
 */
class JsDomContext(
    val layoutEngine: LayoutEngine,
    val htmlParser: HtmlFragmentParser,
    val styleResolver: StyleResolver?,
    val requestRedraw: () -> Unit,
    val onHtmxTrigger: (Element) -> Unit,
) {
    /**
     * JS(innerHTML代入等)によるDOM変更後に呼ばれる。htmx.js統合時、
     * JsEngine側でこれを `htmx.process(element)` 呼び出しに差し替える
     * (MutationObserverを実装しない代わりの手動フック)。
     * 未設定時は何もしない。
     */
    var onDomMutated: (Element) -> Unit = {}

    /** 単一要素のみ再解決する軽量パス。attribute/class変更時に使う。 */
    fun reresolveStyle(element: Element) {
        val resolver = styleResolver ?: return
        val parentStyle = element.parent?.computedStyle ?: ComputedStyle()
        element.computedStyle = resolver.resolve(element, parentStyle)
    }
}
