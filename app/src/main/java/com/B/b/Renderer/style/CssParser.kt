package com.B.b.Renderer.style

import com.B.b.Renderer.core.Element

data class CssDeclaration(val property: String, val value: String, val important: Boolean)

data class CssRule(
    val selector: String,
    val declarations: List<CssDeclaration>,
    val specificity: Specificity,
    val sourceOrder: Int,
)

data class Specificity(val idCount: Int, val classCount: Int, val tagCount: Int) : Comparable<Specificity> {
    override fun compareTo(other: Specificity): Int {
        if (idCount != other.idCount) return idCount - other.idCount
        if (classCount != other.classCount) return classCount - other.classCount
        return tagCount - other.tagCount
    }
}

class Stylesheet(val rules: List<CssRule>)

/**
 * 最小実装のCSSパーサー。対応範囲:
 * - `selector { prop: value; }` の基本構文
 * - カンマ区切りの複合セレクタ
 * - !important
 * flexbox/grid等の値解釈はLayoutEngine側の責務とし、ここでは文字列のまま保持する。
 */
class CssParser {
    fun parse(css: String): Stylesheet {
        val rules = mutableListOf<CssRule>()
        var order = 0

        val ruleRegex = Regex("""([^{}]+)\{([^{}]*)\}""")
        ruleRegex.findAll(css).forEach { match ->
            val selectorsRaw = match.groupValues[1].trim()
            val body = match.groupValues[2].trim()

            val declarations = body.split(";").mapNotNull { decl ->
                val parts = decl.split(":", limit = 2)
                if (parts.size != 2) return@mapNotNull null
                val prop = parts[0].trim()
                var value = parts[1].trim()
                val important = value.contains("!important")
                if (important) value = value.replace("!important", "").trim()
                CssDeclaration(prop, value, important)
            }

            selectorsRaw.split(",").forEach { rawSelector ->
                val selector = rawSelector.trim()
                if (selector.isEmpty()) return@forEach
                rules.add(
                    CssRule(
                        selector = selector,
                        declarations = declarations,
                        specificity = computeSpecificity(selector),
                        sourceOrder = order++,
                    ),
                )
            }
        }
        return Stylesheet(rules)
    }

    private fun computeSpecificity(selector: String): Specificity {
        val idCount = Regex("#[\\w-]+").findAll(selector).count()
        val classCount = Regex("\\.[\\w-]+").findAll(selector).count()
        val tagCount = Regex("(?:^|[\\s>+~])[a-zA-Z][\\w-]*").findAll(selector).count()
        return Specificity(idCount, classCount, tagCount)
    }
}

/**
 * セレクタマッチングの最小実装。
 * 対応: タグ名, #id, .class, 子孫結合子(半角スペース)
 * 未対応: 擬似クラス(:disabled等)、子結合子(>)、属性セレクタ
 */
object CssSelectorEngine {
    fun matches(element: Element, selector: String): Boolean {
        val parts = selector.trim().split(Regex("\\s+"))
        return matchChain(element, parts, parts.size - 1)
    }

    private fun matchChain(element: Element?, parts: List<String>, index: Int): Boolean {
        if (element == null) return false
        if (!matchesSingle(element, parts[index])) return false
        if (index == 0) return true
        var ancestor = element.parent
        while (ancestor != null) {
            if (matchChain(ancestor, parts, index - 1)) return true
            ancestor = ancestor.parent
        }
        return false
    }

    private fun matchesSingle(element: Element, part: String): Boolean {
        Regex("#([\\w-]+)").find(part)?.let {
            if (element.attributes["id"] != it.groupValues[1]) return false
        }
        Regex("\\.([\\w-]+)").findAll(part).forEach {
            val classes = element.attributes["class"]?.split(" ") ?: emptyList()
            if (it.groupValues[1] !in classes) return false
        }
        val tagMatch = Regex("^[a-zA-Z][\\w-]*").find(part)
        if (tagMatch != null && !element.tag.equals(tagMatch.value, ignoreCase = true)) return false
        return true
    }
}
