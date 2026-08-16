package com.B.b.Renderer.render

import com.B.b.Renderer.core.DirtyLevel
import com.B.b.Renderer.core.Element
import com.B.b.Renderer.core.TextNode
import com.B.b.Renderer.layout.LayoutEngine
import com.B.b.Renderer.style.Color

/**
 * ページ内検索(Find in page)。
 *
 * 実ブラウザは文字列単位でハイライトするが、このエンジンはテキストを要素単位で
 * (GPU版はテクスチャとして)描画しているため、文字列の一部分だけをハイライトする
 * (テキストランを分割して部分背景を塗る)のは割に合わない。JsStyle(インラインstyle代入)と
 * 同じ方針で、「クエリを含むテキストを持つ要素ごと、丸ごと背景色を変える」形の
 * 要素単位ハイライトに割り切る。
 */
class FindInPageController(
    private val root: Element,
    private val layoutEngine: LayoutEngine,
    private val requestRedraw: () -> Unit,
) {
    private data class Match(val element: Element, val originalBackground: Color)

    private var matches: List<Match> = emptyList()
    private var currentIndex = -1

    var query: String = ""
        private set

    val matchCount: Int get() = matches.size

    /** 1始まりの「現在何件目か」。該当なしの間は0。 */
    val currentMatchNumber: Int get() = if (currentIndex < 0) 0 else currentIndex + 1

    fun search(text: String) {
        clearHighlights()
        query = text
        val needle = text.trim().lowercase()
        if (needle.isBlank()) {
            matches = emptyList()
            currentIndex = -1
            requestRedraw()
            return
        }
        val found = root.findAll { element ->
            element.children.filterIsInstance<TextNode>().any { it.data.lowercase().contains(needle) }
        }.map { element -> Match(element, element.computedStyle.backgroundColor) }
        matches = found
        currentIndex = if (found.isEmpty()) -1 else 0
        applyHighlights()
        scrollToCurrent()
        requestRedraw()
    }

    fun next() {
        if (matches.isEmpty()) return
        currentIndex = (currentIndex + 1) % matches.size
        applyHighlights()
        scrollToCurrent()
        requestRedraw()
    }

    fun previous() {
        if (matches.isEmpty()) return
        currentIndex = (currentIndex - 1 + matches.size) % matches.size
        applyHighlights()
        scrollToCurrent()
        requestRedraw()
    }

    /** 検索を終了し、変更したbackgroundColorを元に戻す。 */
    fun clear() {
        clearHighlights()
        matches = emptyList()
        currentIndex = -1
        query = ""
        requestRedraw()
    }

    private fun clearHighlights() {
        matches.forEach { match ->
            match.element.computedStyle = match.element.computedStyle.copy(backgroundColor = match.originalBackground)
            match.element.markDirty(DirtyLevel.STYLE)
        }
    }

    private fun applyHighlights() {
        matches.forEachIndexed { index, match ->
            val highlightColor = if (index == currentIndex) CURRENT_MATCH_COLOR else OTHER_MATCH_COLOR
            match.element.computedStyle = match.element.computedStyle.copy(backgroundColor = highlightColor)
            match.element.markDirty(DirtyLevel.STYLE)
        }
    }

    /** 現在の一致がビューポート内に収まるよう、必要ならスクロールする。 */
    private fun scrollToCurrent() {
        val match = matches.getOrNull(currentIndex) ?: return
        val targetScrollY = (match.element.computedRect.y.toFloat() - SCROLL_MARGIN).coerceAtLeast(0f)
        layoutEngine.scrollBy(targetScrollY - layoutEngine.scrollY)
    }

    companion object {
        private val CURRENT_MATCH_COLOR = Color(255, 165, 0, 255) // 現在の一致: オレンジ、不透明
        private val OTHER_MATCH_COLOR = Color(255, 255, 0, 120) // その他の一致: 半透明の黄
        private const val SCROLL_MARGIN = 40f
    }
}
