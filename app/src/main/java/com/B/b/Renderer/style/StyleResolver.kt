package com.B.b.Renderer.style

import com.B.b.Renderer.core.Element
import com.B.b.Renderer.core.StackingContext

class StyleResolver(private val stylesheet: Stylesheet) {

    /**
     * 実際にマッチング対象とするルール一覧。UserAgentStyles(タグ既定スタイル)を
     * ページ側ルールより先(=詳細度が同じ場合に負ける側)に置いてマージする
     * (2026-07、<h1>等のタグ既定フォントサイズが無く見出しと本文が同じ
     * サイズで描画されていた問題への対応。詳細はUserAgentStyles.kt参照)。
     */
    private val effectiveRules: List<CssRule> = UserAgentStyles.rules + stylesheet.rules

    /** ツリー全体を上から辿り、継承を正しく伝播させる */
    fun resolveTree(root: Element, parentStyle: ComputedStyle = ComputedStyle()) {
        root.computedStyle = resolve(root, parentStyle)
        root.stackingContext = resolveStackingContext(root)
        root.children.filterIsInstance<Element>().forEach {
            resolveTree(it, root.computedStyle)
        }
    }

    /** 単一要素のみ再計算したい場合(DirtyLevel.STYLE用の軽量パス) */
    fun resolve(element: Element, parentStyle: ComputedStyle): ComputedStyle {
        val matched = effectiveRules.filter { CssSelectorEngine.matches(element, it.selector) }
        val sorted = matched.sortedWith(compareBy<CssRule> { it.specificity }.thenBy { it.sourceOrder })

        var style = parentStyle.inheritableSubset()
        sorted.forEach { rule ->
            rule.declarations.forEach { decl -> style = applyDeclaration(style, decl) }
        }

        // インラインstyle属性は詳細度最強として最後に適用
        element.attributes["style"]?.let { inlineCss ->
            parseInlineDeclarations(inlineCss).forEach { decl -> style = applyDeclaration(style, decl) }
        }

        return style
    }

    private fun applyDeclaration(style: ComputedStyle, decl: CssDeclaration): ComputedStyle = when (decl.property) {
        "color" -> style.copy(color = parseColor(decl.value))
        "background-color" -> style.copy(backgroundColor = parseColor(decl.value))
        "font-size" -> style.copy(fontSize = parsePx(decl.value))
        "display" -> style.copy(display = parseDisplay(decl.value))
        "position" -> style.copy(position = parsePosition(decl.value))
        "width" -> style.copy(width = parseCssValue(decl.value))
        "height" -> style.copy(height = parseCssValue(decl.value))
        "z-index" -> style.copy(zIndex = decl.value.toIntOrNull())
        "pointer-events" -> style.copy(
            pointerEvents = if (decl.value == "none") PointerEvents.NONE else PointerEvents.AUTO,
        )
        // marginはshorthand(1〜4値)と個別プロパティの両方に対応する。
        // 2026-07、UserAgentStyles導入と合わせて追加(それまでmarginは常にZEROで、
        // 見出し・本文・ページ端が隙間なくくっつく原因になっていた)。
        "margin" -> style.copy(margin = parseBoxEdgesShorthand(decl.value, style.margin))
        "margin-top" -> style.copy(margin = style.margin.copy(top = parsePxOrZero(decl.value)))
        "margin-right" -> style.copy(margin = style.margin.copy(right = parsePxOrZero(decl.value)))
        "margin-bottom" -> style.copy(margin = style.margin.copy(bottom = parsePxOrZero(decl.value)))
        "margin-left" -> style.copy(margin = style.margin.copy(left = parsePxOrZero(decl.value)))
        else -> style
    }

    private fun resolveStackingContext(element: Element): StackingContext? {
        val style = element.computedStyle
        val isolates = style.position in setOf(Position.ABSOLUTE, Position.FIXED, Position.STICKY)
        return if (style.zIndex != null || isolates) {
            StackingContext(zIndex = style.zIndex ?: 0, isolatesChildren = isolates)
        } else {
            null
        }
    }

    private fun parseInlineDeclarations(css: String): List<CssDeclaration> =
        css.split(";").mapNotNull { decl ->
            val parts = decl.split(":", limit = 2)
            if (parts.size != 2) return@mapNotNull null
            CssDeclaration(parts[0].trim(), parts[1].trim(), false)
        }

    private fun parseColor(value: String): Color {
        val hex = value.trim().removePrefix("#")
        return when (hex.length) {
            6 -> Color(hex.substring(0, 2).toInt(16), hex.substring(2, 4).toInt(16), hex.substring(4, 6).toInt(16))
            else -> Color.BLACK // named color / rgb()は今後拡張
        }
    }

    private fun parsePx(value: String): Float = value.removeSuffix("px").trim().toFloatOrNull() ?: 16f

    /**
     * margin用。font-sizeと違い未指定時のフォールバックは0が自然なため、
     * parsePx()とは別に用意する(parsePx()は16fにフォールバックするため流用不可)。
     * 現状はpx単位のみ対応。%やem/auto等のmargin値は今後拡張が必要。
     */
    private fun parsePxOrZero(value: String): Float = value.removeSuffix("px").trim().toFloatOrNull() ?: 0f

    /**
     * CSSのmargin shorthand(1〜4値)をBoxEdgesへ展開する。
     *   1値: 全辺同じ
     *   2値: 上下, 左右
     *   3値: 上, 左右, 下
     *   4値: 上, 右, 下, 左(時計回り)
     * パース不能な値数の場合は変更前のBoxEdgesをそのまま返す(安全側)。
     */
    private fun parseBoxEdgesShorthand(value: String, current: BoxEdges): BoxEdges {
        val parts = value.trim().split(Regex("\\s+")).filter { it.isNotBlank() }.map { parsePxOrZero(it) }
        return when (parts.size) {
            1 -> BoxEdges(parts[0], parts[0], parts[0], parts[0])
            2 -> BoxEdges(parts[0], parts[1], parts[0], parts[1])
            3 -> BoxEdges(parts[0], parts[1], parts[2], parts[1])
            4 -> BoxEdges(parts[0], parts[1], parts[2], parts[3])
            else -> current
        }
    }

    private fun parseDisplay(value: String): Display = when (value.trim()) {
        "none" -> Display.NONE
        "flex" -> Display.FLEX
        "inline" -> Display.INLINE
        else -> Display.BLOCK
    }

    private fun parsePosition(value: String): Position = when (value.trim()) {
        "relative" -> Position.RELATIVE
        "absolute" -> Position.ABSOLUTE
        "fixed" -> Position.FIXED
        "sticky" -> Position.STICKY
        else -> Position.STATIC
    }

    private fun parseCssValue(value: String): CssValue = when {
        value == "auto" -> CssValue.Auto
        value.endsWith("%") -> CssValue.Percent(value.removeSuffix("%").toFloatOrNull() ?: 0f)
        value.endsWith("px") -> CssValue.Px(value.removeSuffix("px").toFloatOrNull() ?: 0f)
        else -> CssValue.Auto
    }
}
