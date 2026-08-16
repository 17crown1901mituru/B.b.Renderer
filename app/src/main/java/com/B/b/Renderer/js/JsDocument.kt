package com.B.b.Renderer.js

import com.B.b.Renderer.core.Element
import org.mozilla.javascript.Function

class JsDocument(
    private val root: Element,
    private val domContext: JsDomContext,
    private val registry: JsElementRegistry,
) {
    fun getElementById(id: String): JsElement? =
        root.findFirst { it.attributes["id"] == id }?.let { registry.wrap(it) }

    fun querySelector(selector: String): JsElement? =
        root.querySelector(selector)?.let { registry.wrap(it) }

    fun querySelectorAll(selector: String): Array<JsElement> =
        root.querySelectorAll(selector).map { registry.wrap(it) }.toTypedArray()

    fun getElementsByTagName(tag: String): Array<JsElement> =
        root.findAll { it.tag.equals(tag, ignoreCase = true) }.map { registry.wrap(it) }.toTypedArray()

    fun createElement(tag: String): JsElement = registry.wrap(Element(tag))

    /**
     * document.createEvent('CustomEvent') + initCustomEvent(...) という旧式2段階生成。
     * `new CustomEvent(...)`非対応環境向けのフォールバックとして
     * htmx.js等が使うことがあるため用意する。typeは空のまま返し、
     * 呼び出し側がinitCustomEvent()で確定させる想定。
     */
    fun createEvent(interfaceName: String): JsEvent = JsEvent(type = "")

    val body: JsElement
        get() = (root.findFirst { it.tag == "body" } ?: root).let { registry.wrap(it) }

    /**
     * document直下ラッパー。実DOMのdocumentノードに相当するものをこのエンジンは
     * 持たないため、ツリーのルート要素(root)を「document相当」として使い回す。
     * root.findFirst{tag=="body"}ではなくrootそのものに委譲することで、
     * <body>より外側(仮にあれば)で発火したイベントも拾える。
     */
    private val documentEventTarget: JsElement by lazy { registry.wrap(root) }

    /**
     * document.addEventListener(type, callback)相当。
     * htmx.jsが起動時に多用する(例: click委譲、DOMContentLoaded相当のフック)。
     * 実際にはrootに登録されるため、子要素で発火したイベントがdispatchEvent()の
     * バブリングでrootまで届いた時点で発火する(実DOMのdocumentレベルリスナーと
     * ほぼ同じ挙動)。
     */
    fun addEventListener(type: String, callback: Function) {
        documentEventTarget.addEventListener(type, callback)
    }

    fun removeEventListener(type: String, callback: Function) {
        documentEventTarget.removeEventListener(type, callback)
    }

    /** document.dispatchEvent(event)相当。root起点でdispatchする。 */
    fun dispatchEvent(eventArg: Any?): Boolean = documentEventTarget.dispatchEvent(eventArg)
}
