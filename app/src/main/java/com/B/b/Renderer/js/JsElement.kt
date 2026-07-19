package com.B.b.Renderer.js

import com.B.b.Renderer.core.DirtyLevel
import com.B.b.Renderer.core.Element
import com.B.b.Renderer.core.FormControlElement
import com.B.b.Renderer.core.TextNode
import com.B.b.Renderer.style.CssSelectorEngine
import org.mozilla.javascript.Context
import org.mozilla.javascript.Function
import org.mozilla.javascript.ScriptableObject

/**
 * DOM Elementの薄いJSラッパー。
 *
 * 設計方針: RhinoはKotlinのpublicメソッド/プロパティ(getX/setX)をLiveConnect経由で
 * そのままJSプロパティ/メソッドとして公開できる。jsFunction_プレフィックス方式
 * (Context.defineClassが必要)より単純で事故が少ないため、このクラス以降は
 * 素のpublicメンバをそのまま公開する方針に統一する。
 */
class JsElement(
    internal val element: Element,
    private val domContext: JsDomContext,
    private val registry: JsElementRegistry,
) {
    // ネイティブタップ経由(Engine側Element.eventListeners)との互換用ブリッジ。
    // removeEventListenerで同じFunctionを指定されたら解除できるよう対応表を持つ。
    // core.Event導入(Engine側)により、EventListenerは(Node)->Unitから(core.Event)->Unitへ変更された。
    private val nativeBridges = mutableMapOf<Pair<String, Function>, com.B.b.Renderer.core.EventListener>()

    // JS/htmx.js独自のCustomEvent(detail/バブリング/preventDefault対応)用の listener 表。
    // Engine側のElement.eventListenersとは別軸で、こちらは本クラス内で完結して
    // 親方向へのバブリングを自前で実装する(input/InputHandling.ktのdispatchClickは
    // clickイベント固有の、hx-*バブリングのための別ルート)。
    private val jsListeners = mutableMapOf<String, MutableList<Function>>()

    val tagName: String get() = element.tag.uppercase()

    var id: String
        get() = element.attributes["id"] ?: ""
        set(value) {
            element.attributes["id"] = value
        }

    var className: String
        get() = element.attributes["class"] ?: ""
        set(value) {
            element.attributes["class"] = value
            domContext.reresolveStyle(element)
            domContext.requestRedraw()
        }

    val classList: JsClassList by lazy { JsClassList(element, domContext) }
    val style: JsStyle by lazy { JsStyle(element, domContext) }

    var textContent: String
        get() = element.collectVisibleText()
        set(value) {
            element.replaceChildren(listOf(TextNode(value)))
            domContext.requestRedraw()
        }

    /**
     * innerHTMLへの代入。HtmlFragmentParserで断片パースし、子要素を丸ごと置き換える。
     * HTMXのswapと同じ経路(replaceChildren)を通るため、Engine側のdirty伝播もそのまま効く。
     * 代入後にonDomMutatedを呼ぶ(htmx.js統合時、ここでhtmx.process()が走る)。
     */
    var innerHTML: String
        get() = "" // シリアライズは未実装。取得は現状非対応。
        set(value) {
            val wrapper = domContext.htmlParser.parseFragment(value)
            element.replaceChildren(wrapper.children.toList())
            domContext.requestRedraw()
            domContext.onDomMutated(element)
        }

    fun getAttribute(name: String): String? = element.attributes[name]

    fun setAttribute(name: String, value: String) {
        element.attributes[name] = value
        if (name == "class" || name == "style") {
            domContext.reresolveStyle(element)
        }
        element.markDirty(DirtyLevel.SUBTREE)
        domContext.requestRedraw()
    }

    fun removeAttribute(name: String) {
        element.attributes.remove(name)
        element.markDirty(DirtyLevel.SUBTREE)
        domContext.requestRedraw()
    }

    fun hasAttribute(name: String): Boolean = element.attributes.containsKey(name)

    // --- フォーム系要素の状態(disabled/checked)への簡易アクセス ---

    var disabled: Boolean
        get() = element.elementState.disabled
        set(value) {
            element.elementState.disabled = value
            element.markDirty(DirtyLevel.STYLE)
            domContext.requestRedraw()
        }

    var checked: Boolean
        get() = element.elementState.checked
        set(value) {
            element.elementState.checked = value
            element.markDirty(DirtyLevel.STYLE)
            domContext.requestRedraw()
        }

    var value: String
        get() = (element as? FormControlElement)?.currentValue() ?: ""
        set(v) {
            element.attributes["value"] = v
        }

    // --- 子要素操作 ---

    fun appendChild(child: JsElement) {
        element.appendChild(child.element)
        domContext.requestRedraw()
        domContext.onDomMutated(element)
    }

    fun removeChild(child: JsElement) {
        element.removeChild(child.element)
        domContext.requestRedraw()
    }

    // --- 検索 ---

    fun querySelector(selector: String): JsElement? =
        element.querySelector(selector)?.let { registry.wrap(it) }

    fun querySelectorAll(selector: String): Array<JsElement> =
        element.querySelectorAll(selector).map { registry.wrap(it) }.toTypedArray()

    /** 自分自身から祖先方向へ、selectorに最初にマッチした要素を返す(htmx.jsが多用する) */
    fun closest(selector: String): JsElement? {
        var current: Element? = element
        while (current != null) {
            if (CssSelectorEngine.matches(current, selector)) return registry.wrap(current)
            current = current.parent
        }
        return null
    }

    fun matches(selector: String): Boolean = CssSelectorEngine.matches(element, selector)

    // --- イベント(ネイティブタップ互換の簡易版) ---

    fun addEventListener(type: String, callback: Function) {
        jsListeners.getOrPut(type) { mutableListOf() }.let { list ->
            if (callback !in list) list.add(callback)
        }

        // ネイティブタップ(input.dispatchClickによるhx-*バブリング検知)からも
        // このリスナーが呼ばれるよう、Engine側のElement.eventListenersにも
        // 互換ブリッジとして登録しておく。
        val key = type to callback
        if (key !in nativeBridges) {
            val bridge: com.B.b.Renderer.core.EventListener = { _ -> invokeListener(callback, type, null) }
            nativeBridges[key] = bridge
            element.addEventListener(type, bridge)
        }
    }

    fun removeEventListener(type: String, callback: Function) {
        jsListeners[type]?.remove(callback)
        val key = type to callback
        nativeBridges.remove(key)?.let { bridge -> element.removeEventListener(type, bridge) }
    }

    /**
     * DOM標準のdispatchEvent(event)相当。祖先方向へバブリングし、
     * stopPropagation()が呼ばれた時点で伝播を止める。
     * htmx.jsが内部で使うカスタムイベント(htmx:beforeSwap等)はこの経路を通る。
     *
     * 引数はAnyで受ける: `new CustomEvent(...)`(JsCustomEventHost)経由と
     * `document.createEvent()+initCustomEvent()`(生のJsEvent)経由の
     * 両方から呼ばれうるため、ここで正規化する。
     *
     * @return preventDefault()されなかった場合true(DOM仕様に合わせる)
     */
    fun dispatchEvent(eventArg: Any?): Boolean {
        val event = when (eventArg) {
            is JsEvent -> eventArg
            is JsCustomEventHost -> eventArg.underlying
            else -> return true
        }

        event.target = this
        com.B.b.Renderer.debug.BehaviorAuditLog.record(
            com.B.b.Renderer.debug.BehaviorAuditLog.Category.DOM_EVENT,
            "dispatchEvent: ${event.type} on <${element.tag}${element.attributes["id"]?.let { " id=$it" } ?: ""}>",
        )
        var current: JsElement? = this
        while (current != null) {
            event.currentTarget = current
            current.jsListeners[event.type]?.toList()?.forEach { fn ->
                current!!.invokeListener(fn, event.type, event)
                if (event.propagationStopped) return !event.defaultPrevented
            }
            if (!event.bubbles) break
            val parentElement = current.element.parent ?: break
            current = registry.wrap(parentElement)
        }
        return !event.defaultPrevented
    }

    private fun invokeListener(callback: Function, type: String, event: JsEvent?) {
        val ctx = Context.enter()
        try {
            val scope = ScriptableObject.getTopLevelScope(callback)
            val arg = event ?: JsEvent(type = type).also { it.target = this; it.currentTarget = this }
            callback.call(ctx, scope, scope, arrayOf(arg))
        } finally {
            Context.exit()
        }
    }

    /**
     * DOM標準の`element.click()`相当。dispatchEvent(JsEvent)によるJS向けバブリングに加え、
     * 祖先方向へ実際にたどってhx-post/hx-get要素を検知する
     * (input/InputHandling.ktのdispatchClickをそのまま再利用、ネイティブタップと同一経路)。
     */
    fun click() {
        dispatchEvent(JsEvent(type = "click"))
        com.B.b.Renderer.input.dispatchClick(element, domContext.onHtmxTrigger)
        domContext.requestRedraw()
    }

    override fun equals(other: Any?): Boolean = other is JsElement && other.element === element
    override fun hashCode(): Int = System.identityHashCode(element)
}
