package com.B.b.Renderer.js

import org.mozilla.javascript.Scriptable
import org.mozilla.javascript.ScriptableObject

/**
 * `new CustomEvent(type, {detail, bubbles, cancelable})` をJSから呼べるようにする
 * Rhinoホストクラス。実体はJsEventそのままではなく、defineClass経由で生成される
 * 薄いラッパー(JsEventインスタンスを保持し、そちらに処理を委譲する)。
 *
 * htmx.jsは `typeof CustomEvent === 'function'` を見て、trueならこちらを、
 * falseなら document.createEvent + initCustomEvent のフォールバックを使う。
 * 両対応にしておくことで、どちらの経路を通っても動くようにしている。
 */
class JsCustomEventHost : ScriptableObject() {

    companion object {
        fun install(scope: Scriptable) {
            ScriptableObject.defineClass(scope, JsCustomEventHost::class.java)
        }
    }

    override fun getClassName(): String = "CustomEvent"

    lateinit var underlying: JsEvent
        private set

    /**
     * RhinoのdefineClass規約: `new CustomEvent(type, options)` は
     * このメソッドにマッピングされる(引数の型/数が一致するコンストラクタ相当として扱われる)。
     */
    fun jsConstructor(type: String, options: Any?) {
        var detail: Any? = null
        var bubbles = false
        var cancelable = false

        if (options is Scriptable) {
            val d = options.get("detail", options)
            if (d != Scriptable.NOT_FOUND) detail = d
            val b = options.get("bubbles", options)
            if (b is Boolean) bubbles = b
            val c = options.get("cancelable", options)
            if (c is Boolean) cancelable = c
        }

        underlying = JsEvent(type = type, detail = detail, bubbles = bubbles, cancelable = cancelable)
    }

    fun jsGet_type(): String = underlying.type
    fun jsGet_detail(): Any? = underlying.detail
    fun jsGet_bubbles(): Boolean = underlying.bubbles
    fun jsGet_cancelable(): Boolean = underlying.cancelable
    fun jsGet_defaultPrevented(): Boolean = underlying.defaultPrevented

    fun jsFunction_preventDefault() = underlying.preventDefault()
    fun jsFunction_stopPropagation() = underlying.stopPropagation()
}
