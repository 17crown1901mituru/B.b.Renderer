package com.B.b.Renderer.js

import com.B.b.Renderer.core.Element
import java.util.WeakHashMap

/**
 * 同じElementに対してJSから複数回アクセスしても同一のJsElementインスタンスが
 * 返るようにする。これが無いと `el === el` のようなJS側の同一性比較が壊れ、
 * addEventListenerで登録したリスナーの重複登録/解除判定もおかしくなる。
 */
class JsElementRegistry(private val domContext: JsDomContext) {
    private val cache = WeakHashMap<Element, JsElement>()

    fun wrap(element: Element): JsElement =
        cache.getOrPut(element) { JsElement(element, domContext, this) }
}
