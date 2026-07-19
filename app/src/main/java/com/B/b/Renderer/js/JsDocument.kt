package com.B.b.Renderer.js

import com.B.b.Renderer.core.Element

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
}
