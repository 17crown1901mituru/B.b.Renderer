package com.B.b.Renderer.style

import com.B.b.Renderer.core.Element
import com.B.b.Renderer.core.StackingContext

class StyleResolver(private val stylesheet: Stylesheet) {

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
        val matched = stylesheet.rules.filter { CssSelectorEngine.matches(element, it.selector) }
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
