package com.B.b.Renderer.js

import com.B.b.Renderer.core.Element

/**
 * element.classList.add('foo') のようなJSアクセスに応えるヘルパー。
 * Rhinoは公開メソッドをJavaBean/LiveConnect経由でそのままJSから呼べるため、
 * ScriptableObjectを継承する必要はない。
 */
class JsClassList(private val element: Element, private val domContext: JsDomContext) {

    private fun currentClasses(): MutableList<String> =
        (element.attributes["class"] ?: "").split(" ").filter { it.isNotBlank() }.toMutableList()

    private fun commit(classes: List<String>) {
        element.attributes["class"] = classes.joinToString(" ")
        domContext.reresolveStyle(element)
        domContext.requestRedraw()
    }

    fun add(name: String) {
        val classes = currentClasses()
        if (name !in classes) {
            classes.add(name)
            commit(classes)
        }
    }

    fun remove(name: String) {
        val classes = currentClasses()
        if (classes.remove(name)) {
            commit(classes)
        }
    }

    fun toggle(name: String): Boolean {
        val classes = currentClasses()
        return if (classes.remove(name)) {
            commit(classes)
            false
        } else {
            classes.add(name)
            commit(classes)
            true
        }
    }

    fun contains(name: String): Boolean = name in currentClasses()
}
