package com.B.b.Renderer.core

import org.jsoup.Jsoup

/**
 * JsoupでHTML構文解析だけを任せ、こちらのElement/TextNodeツリーへ変換する層。
 */
class HtmlFragmentParser {

    /** hx-swapで返る断片HTML用 */
    fun parseFragment(html: String): Element {
        val jsoupDoc = Jsoup.parseBodyFragment(html)
        val wrapper = Element("div")
        jsoupDoc.body().childNodes().forEach { child ->
            wrapper.appendChild(convert(child))
        }
        return wrapper
    }

    /** 初回ロード用、完全なHTMLドキュメント */
    fun parseDocument(html: String): Element {
        val jsoupDoc = Jsoup.parse(html)
        return convert(jsoupDoc.body()) as Element
    }

    /**
     * <head><title>の中身だけを取り出す。履歴・タブ表示・ブックマークの初期タイトルに使う。
     * parseDocument()は<head>側を破棄するため、既存のElementツリーからは取れない
     * (別途jsoupでもう一度パースし直す。履歴記録は1回のナビゲーションにつき1回だけなので、
     * 二重パースのコストは無視できる)。
     */
    fun extractTitle(html: String): String = Jsoup.parse(html).title()

    private fun convert(jsoupNode: org.jsoup.nodes.Node): Node = when (jsoupNode) {
        is org.jsoup.nodes.TextNode -> TextNode(jsoupNode.text())

        is org.jsoup.nodes.Element -> {
            val element = createElementByTag(jsoupNode.tagName())

            jsoupNode.attributes().forEach { attr ->
                element.attributes[attr.key] = attr.value
            }

            if (element is FormControlElement) {
                element.elementState.checked = jsoupNode.hasAttr("checked")
                element.elementState.disabled = jsoupNode.hasAttr("disabled")
                element.elementState.readonly = jsoupNode.hasAttr("readonly")
            }

            element.stackingContext = extractInlineStackingContext(jsoupNode)

            jsoupNode.childNodes().forEach { child ->
                element.appendChild(convert(child))
            }
            element
        }

        else -> TextNode("")
    }

    private fun createElementByTag(tag: String): Element = when (tag.lowercase()) {
        "video", "audio" -> MediaElement(tag)
        "input", "select", "textarea", "button" -> FormControlElement(tag)
        else -> Element(tag)
    }

    /**
     * インラインstyle属性からの早期StackingContext判定。
     * 正式な値はStyleResolver実行後に上書きされる想定の暫定処理。
     */
    private fun extractInlineStackingContext(jsoupNode: org.jsoup.nodes.Element): StackingContext? {
        val styleAttr = jsoupNode.attr("style")
        if (styleAttr.isBlank()) return null

        val zIndex = Regex("z-index:\\s*(-?\\d+)").find(styleAttr)?.groupValues?.get(1)?.toIntOrNull()
        val position = Regex("position:\\s*(\\w+)").find(styleAttr)?.groupValues?.get(1)
        val isolates = position in setOf("absolute", "fixed", "sticky")

        return if (zIndex != null || isolates) {
            StackingContext(zIndex = zIndex ?: 0, isolatesChildren = isolates)
        } else {
            null
        }
    }
}
