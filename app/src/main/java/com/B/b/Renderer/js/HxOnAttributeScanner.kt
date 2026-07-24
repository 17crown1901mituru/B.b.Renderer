package com.B.b.Renderer.js

import com.B.b.Renderer.core.Element

/**
 * htmx.js が `hx-on:`/`data-hx-on:`/`hx-on-`/`data-hx-on-` 属性を持つ子孫要素を
 * 探索するために内部で使う `(new XPathEvaluator).createExpression(...)` を、
 * このエンジンではXPath実装を持たないため代替する限定スキャナ。
 *
 * htmx.js(2.0.10)側は常に同一のXPath式(このプレフィックス4種のみ)しか
 * 評価しないため、汎用XPathを実装せずこの1パターンに絞ったポリフィルで足りる
 * (JS側のbootstrapグルーコードとセット。JsEngine.loadHtmx()参照)。
 * 今後別のXPath式が必要になった場合は、その時点でこのクラスにパターンを
 * 追加する形で拡張すること(汎用XPathパーサーの実装は過剰投資と判断)。
 */
class HxOnAttributeScanner(private val registry: JsElementRegistry) {

    private val prefixes = listOf("hx-on:", "data-hx-on:", "hx-on-", "data-hx-on-")

    /**
     * contextNodeの子孫(contextNode自身は含まない。htmx.js側で自分自身は
     * 別途`Et(e)`チェックで処理されるため)から、対象属性を持つ要素を
     * document順(深さ優先)で返す。
     *
     * @param contextNode JS側からRhino LiveConnect経由で渡ってくる`JsElement`。
     *   それ以外の型が来た場合は空配列を返す(型不一致による例外を避ける)。
     */
    fun scan(contextNode: Any?): Array<JsElement> {
        val root = (contextNode as? JsElement)?.element ?: return emptyArray()
        val result = mutableListOf<Element>()
        fun walk(el: Element) {
            el.children.forEach { child ->
                if (child is Element) {
                    if (hasHxOnAttribute(child)) result.add(child)
                    walk(child)
                }
            }
        }
        walk(root)
        return result.map { registry.wrap(it) }.toTypedArray()
    }

    private fun hasHxOnAttribute(element: Element): Boolean =
        element.attributes.keys.any { key -> prefixes.any { prefix -> key.startsWith(prefix) } }
}