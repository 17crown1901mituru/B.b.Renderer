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
 * - `/* ... */` コメントの除去
 * - `@media (min-width: Npx)` / `(max-width: Npx)` / `screen` / `all` / `print` の評価、
 *   および `and` によるAND結合(2026-08対応。下記詳細参照)
 * flexbox/grid等の値解釈はLayoutEngine側の責務とし、ここでは文字列のまま保持する。
 *
 * 未対応の@media条件(orientation/resolution等)・@font-face/@keyframes等の他のat-ruleは、
 * 中身をセレクタとして誤解釈しないよう安全側でまるごと無視する
 * (@media未対応条件のみ「常に適用」扱いにしている。詳細はevaluateMediaCondition参照)。
 * `or`・`not`・複数メディアタイプのカンマ区切り(`@media screen, print`)は非対応。
 *
 * 2026-08発覚の不具合について: 旧実装は `Regex("""([^{}]+)\{([^{}]*)\}""")` という
 * ネストを考慮しないフラットな正規表現で `{...}` ブロックを拾っていたため、
 * `@media (...) { .foo { color:red } }` のような入力に対して外側の`@media`部分を
 * 「意味のない前置き文字列」とみなし、内側の`.foo`ルールをメディアクエリの条件を
 * 一切見ずに常時適用してしまっていた(「@media未対応→無視される」のではなく、
 * 「@media自体が消え、中身が無条件適用される」というより悪い結果になっていた)。
 * splitTopLevelBlocks()で実際に波かっこの深さを数えることでこれを解消している。
 */
class CssParser {
    /**
     * @param viewportWidth `@media (min-width/max-width: ...)` の判定に使う基準幅。
     *   呼び出し側(EngineActivity)は実ビューポート幅(displayMetrics.widthPixels)を渡すこと。
     *   省略時は「常にtrue」相当のFloat.MAX_VALUEとして扱われる(min-width条件は素通り、
     *   max-width条件は基本的に非該当になる点に注意。UserAgentStyles等、@mediaを含まない
     *   固定CSSの解析にのみ省略値を使うこと)。
     */
    fun parse(css: String, viewportWidth: Float = Float.MAX_VALUE): Stylesheet {
        val rules = mutableListOf<CssRule>()
        var order = 0
        splitTopLevelBlocks(stripComments(css)).forEach { (prelude, body) ->
            when {
                prelude.startsWith("@media", ignoreCase = true) -> {
                    if (evaluateMediaCondition(prelude, viewportWidth)) {
                        splitTopLevelBlocks(body).forEach { (innerPrelude, innerBody) ->
                            order = addRules(innerPrelude, innerBody, order, rules)
                        }
                    }
                    // 条件を満たさない@mediaブロックは中身ごと丸々無視する
                }
                prelude.startsWith("@") -> {
                    // @font-face/@keyframes等、今回未対応の他のat-ruleは安全側で無視する
                    // (通常ルールの分岐に流すと、@font-faceの中身をセレクタ+宣言として
                    // 誤解釈してしまうため)
                }
                else -> {
                    order = addRules(prelude, body, order, rules)
                }
            }
        }
        return Stylesheet(rules)
    }

    private fun stripComments(css: String): String =
        css.replace(Regex("/\\*.*?\\*/", RegexOption.DOT_MATCHES_ALL), "")

    /**
     * cssをトップレベルの「prelude { body }」単位に分割する。波かっこの深さを実際に
     * 数えることで、@media等のネストしたブレースを1ブロックとして正しく扱う
     * (このクラス冒頭のドキュメント参照)。@media用に1回、その中身用にもう1回、
     * という形で2回呼ばれる想定(3階層以上のネストは今回のCSS対応範囲(@mediaのみ)では
     * 発生しないため非対応)。
     */
    private fun splitTopLevelBlocks(css: String): List<Pair<String, String>> {
        val blocks = mutableListOf<Pair<String, String>>()
        var depth = 0
        var preludeStart = 0
        var bodyStart = -1
        for (i in css.indices) {
            when (css[i]) {
                '{' -> {
                    if (depth == 0) bodyStart = i + 1
                    depth++
                }
                '}' -> {
                    depth--
                    if (depth == 0 && bodyStart != -1) {
                        val prelude = css.substring(preludeStart, bodyStart - 1).trim()
                        val body = css.substring(bodyStart, i)
                        if (prelude.isNotEmpty()) blocks.add(prelude to body)
                        preludeStart = i + 1
                        bodyStart = -1
                    }
                }
            }
        }
        return blocks
    }

    /** selector shorthand(カンマ区切り)を展開しつつCssRuleへ変換し、rulesへ追記する。戻り値は更新後のorder。 */
    private fun addRules(selectorsRaw: String, body: String, orderStart: Int, rules: MutableList<CssRule>): Int {
        var order = orderStart
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
        return order
    }

    /**
     * `@media`のprelude(条件部分)を評価する。
     *   - 条件無し("@media"単体、通常書かれない)は常にtrue
     *   - "and"で複数条件をANDとして評価する(CSS仕様通り。"or"・カンマ区切りの
     *     複数メディアタイプ・"not"は非対応)
     *   - "print"は画面レンダラーなので常にfalse、"screen"/"all"は常にtrue
     *   - "(min-width: Npx)"/"(max-width: Npx)"はviewportWidthと比較
     *   - それ以外(orientation/resolution/prefers-color-scheme等)は安全側として
     *     「常にtrue」扱いにする(未対応条件で本来出したいはずのスタイルを
     *     誤って消してしまうより、無条件適用の方が実害が少ないと判断)
     */
    private fun evaluateMediaCondition(prelude: String, viewportWidth: Float): Boolean {
        val condition = prelude.trim().removePrefix("@media").trim()
        if (condition.isEmpty()) return true

        val parts = condition.split(Regex("\\band\\b", RegexOption.IGNORE_CASE)).map { it.trim() }
        return parts.all { part ->
            when {
                part.isEmpty() -> true
                part.equals("print", ignoreCase = true) -> false
                part.equals("screen", ignoreCase = true) -> true
                part.equals("all", ignoreCase = true) -> true
                else -> {
                    val minWidth = Regex("min-width\\s*:\\s*(\\d+(?:\\.\\d+)?)px")
                        .find(part)?.groupValues?.get(1)?.toFloatOrNull()
                    val maxWidth = Regex("max-width\\s*:\\s*(\\d+(?:\\.\\d+)?)px")
                        .find(part)?.groupValues?.get(1)?.toFloatOrNull()
                    when {
                        minWidth != null -> viewportWidth >= minWidth
                        maxWidth != null -> viewportWidth <= maxWidth
                        else -> true // orientation等の未対応条件
                    }
                }
            }
        }
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
