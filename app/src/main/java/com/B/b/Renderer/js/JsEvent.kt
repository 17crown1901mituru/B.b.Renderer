package com.B.b.Renderer.js

/**
 * DOM Event/CustomEvent相当。
 * type/bubbles/cancelableをvarにしているのは、document.createEvent('CustomEvent')
 * + initCustomEvent(...) という旧式2段階生成パターン(htmx.js等がCustomEvent
 * コンストラクタ非対応環境向けに使うフォールバック)に対応するため。
 */
class JsEvent(
    var type: String,
    var detail: Any? = null,
    var bubbles: Boolean = true,
    var cancelable: Boolean = true,
) {
    var target: JsElement? = null
    var currentTarget: JsElement? = null

    var defaultPrevented: Boolean = false
        private set

    internal var propagationStopped: Boolean = false
        private set

    fun preventDefault() {
        if (cancelable) defaultPrevented = true
    }

    fun stopPropagation() {
        propagationStopped = true
    }

    /** document.createEvent('CustomEvent')経由で生成した場合の初期化(旧式API) */
    fun initCustomEvent(type: String, bubbles: Boolean, cancelable: Boolean, detail: Any?) {
        this.type = type
        this.bubbles = bubbles
        this.cancelable = cancelable
        this.detail = detail
    }
}
