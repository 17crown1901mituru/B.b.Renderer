
package com.B.b.Renderer.style

import com.B.b.Renderer.core.Element
import com.B.b.Renderer.core.StackingContext

/**
 * CSSの値解決を担う。2026-08、density対応を追加(下記density引数のコメント参照)。
 */
class StyleResolver(
    private val stylesheet: Stylesheet,
    // 2026-08対応。CSSのpx/em/rem等の長さは「デバイス非依存ピクセル」であり、
    // 物理ピクセルに1:1で焼き込んでしまうと、density(画素密度)が高い端末ほど
    // ページ本文が実際の1/density相当の大きさで描画されてしまう
    // (ネイティブUI側はdensity基準pxで組んでいたため正しくスケールしていたが、
    // ページ本文側だけCSSのpx値をそのまま物理pxとして扱っていたため、
    // 「ネイティブUIだけ肥大化して見える」のではなく実際は「ページ本文だけ縮小されていた」
    // というのがひかるから報告のあった見づらさの正体だった)。
    // ここで受け取ったdensityを、CSSの長さをFloatへ変換する箇所(parseFontSize/
    // parseLength/parseCssValue)でまとめて掛けることで、LayoutEngine以降は
    // 今まで通り「物理px基準のワールド座標」として扱われる(GLの投影行列・タッチ入力・
    // アクセシビリティ座標など、他の座標系には一切手を入れずに済む設計にしてある)。
    private val density: Float = 1f,
    // 2026-08対応。vw/vh(ビューポート幅/高さに対する相対単位)を解決するために必要。
    // CSS px基準(=物理px÷density。@mediaのviewportWidth判定と同じ考え方)で受け取り、
    // parseLength/parseCssValue内でdensityを掛け直して最終的に物理px相当の値にする
    // (densityの掛け方はpx/em/rem等、他の単位と同じ流儀に揃えてある)。
    private val viewportWidthCssPx: Float = 0f,
    private val viewportHeightCssPx: Float = 0f,
) {

    companion object {
        // rem(ルート要素基準)の基準値。このエンジンはルート要素のfont-sizeを可変にする
        // 仕組みを持たないため、CSS仕様上の「html要素の計算済みfont-size」の代わりに
        // 固定16pxを基準として扱う簡易実装(parseFontSize/parseLength双方で共有)。
        private const val ROOT_FONT_SIZE_PX = 16f
    }

    /**
     * 実際にマッチング対象とするルール一覧。UserAgentStyles(タグ既定スタイル)を
     * ページ側ルールより先(=詳細度が同じ場合に負ける側)に置いてマージする
     * (2026-07、<h1>等のタグ既定フォントサイズが無く見出しと本文が同じ
     * サイズで描画されていた問題への対応。詳細はUserAgentStyles.kt参照)。
     */
    private val effectiveRules: List<CssRule> = UserAgentStyles.rules + stylesheet.rules

    /** ツリー全体を上から辿り、継承を正しく伝播させる */
    fun resolveTree(root: Element, parentStyle: ComputedStyle = ComputedStyle(fontSize = 16f * density)) {
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

        // em/%指定のfont-sizeは「親の計算済みfontSize」を基準に変換する(CSS仕様通り、
        // 要素自身が既に適用した別のfont-size宣言ではなく、常にparentStyleを基準にする。
        // 2026-07、ページ側CSSが`h1{font-size:1.5em}`のようなem指定をしていた場合、
        // 旧実装ではparsePx()が"px"サフィックスしか見ておらずtoFloatOrNull()に失敗、
        // 無言で16pxにフォールバックしていた。UserAgentStylesの32px指定が
        // ページ側の壊れたem解釈で上書きされ、見出しが常に16px相当になっていた
        // 不具合の根本原因だったため対応)。
        val parentFontSize = parentStyle.fontSize

        var style = parentStyle.inheritableSubset()
        sorted.forEach { rule ->
            rule.declarations.forEach { decl -> style = applyDeclaration(style, decl, parentFontSize) }
        }

        // インラインstyle属性は詳細度最強として最後に適用
        element.attributes["style"]?.let { inlineCss ->
            parseInlineDeclarations(inlineCss).forEach { decl -> style = applyDeclaration(style, decl, parentFontSize) }
        }

        return style
    }

    private fun applyDeclaration(style: ComputedStyle, decl: CssDeclaration, parentFontSize: Float): ComputedStyle = when (decl.property) {
        "color" -> style.copy(color = parseColor(decl.value))
        "background-color" -> style.copy(backgroundColor = parseColor(decl.value))
        "font-size" -> style.copy(fontSize = parseFontSize(decl.value, parentFontSize, style.fontSize))
        "display" -> style.copy(display = parseDisplay(decl.value))
        "position" -> style.copy(position = parsePosition(decl.value))
        "width" -> style.copy(width = parseCssValue(decl.value))
        "height" -> style.copy(height = parseCssValue(decl.value))
        "z-index" -> style.copy(zIndex = decl.value.toIntOrNull())
        "pointer-events" -> style.copy(
            pointerEvents = if (decl.value == "none") PointerEvents.NONE else PointerEvents.AUTO,
        )
        "margin" -> {
            val parsed = parseMarginShorthand(decl.value, style.margin, style.marginLeftAuto, style.marginRightAuto, style.fontSize)
            style.copy(margin = parsed.edges, marginLeftAuto = parsed.leftAuto, marginRightAuto = parsed.rightAuto)
        }
        "margin-top" -> style.copy(margin = style.margin.copy(top = parseLengthOrPercent(decl.value, style.fontSize)))
        "margin-right" -> {
            val auto = decl.value.trim().equals("auto", ignoreCase = true)
            style.copy(
                margin = style.margin.copy(right = if (auto) CssValue.Px(0f) else parseLengthOrPercent(decl.value, style.fontSize)),
                marginRightAuto = auto,
            )
        }
        "margin-bottom" -> style.copy(margin = style.margin.copy(bottom = parseLengthOrPercent(decl.value, style.fontSize)))
        "margin-left" -> {
            val auto = decl.value.trim().equals("auto", ignoreCase = true)
            style.copy(
                margin = style.margin.copy(left = if (auto) CssValue.Px(0f) else parseLengthOrPercent(decl.value, style.fontSize)),
                marginLeftAuto = auto,
            )
        }
        // padding。marginと同じ長さ/百分率パーサー(parseLengthOrPercent)・shorthand展開
        // パターンを流用する。
        "padding" -> style.copy(padding = parseBoxEdgesShorthand(decl.value, style.padding, style.fontSize))
        "padding-top" -> style.copy(padding = style.padding.copy(top = parseLengthOrPercent(decl.value, style.fontSize)))
        "padding-right" -> style.copy(padding = style.padding.copy(right = parseLengthOrPercent(decl.value, style.fontSize)))
        "padding-bottom" -> style.copy(padding = style.padding.copy(bottom = parseLengthOrPercent(decl.value, style.fontSize)))
        "padding-left" -> style.copy(padding = style.padding.copy(left = parseLengthOrPercent(decl.value, style.fontSize)))
        // text-align。ComputedStyle側には既にフィールドがあったが、ここでの解決が漏れていたため
        // ページ側CSSでtext-align:center等を指定しても常にLEFT扱いになっていた(2026-08対応)。
        "text-align" -> style.copy(textAlign = parseTextAlign(decl.value))
        // text-decoration。2026-08、<a>タグのデフォルト下線対応で追加。shorthand
        // (text-decoration-line/-style/-color)の完全展開はせず、"underline"トークンの
        // 有無だけを見る簡易実装(取り消し線・オーバーラインは今回非対応)。
        "text-decoration" -> style.copy(textDecoration = parseTextDecoration(decl.value))
        // ---- Flexbox(2026-08対応) ----
        "flex-direction" -> style.copy(flexDirection = parseFlexDirection(decl.value))
        "justify-content" -> style.copy(justifyContent = parseJustifyContent(decl.value))
        "align-items" -> style.copy(alignItems = parseAlignItems(decl.value))
        // gap。row-gap/column-gapを個別に持たない簡易実装のため("Style.kt"のgapフィールド
        // 参照)、2値指定("row-gap column-gap")の場合は先頭(row-gap相当)のみを見る。
        "gap", "row-gap" -> style.copy(gap = parseLength(decl.value.trim().split(Regex("\\s+")).first(), style.fontSize))
        "flex-grow" -> style.copy(flexGrow = decl.value.trim().toFloatOrNull() ?: style.flexGrow)
        "flex-shrink" -> style.copy(flexShrink = decl.value.trim().toFloatOrNull() ?: style.flexShrink)
        "flex-basis" -> style.copy(flexBasis = parseCssValue(decl.value.trim()))
        "flex" -> parseFlexShorthand(decl.value, style)
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

    /**
     * font-size専用パーサー。px/em/%/rem/vw/vhに対応する。
     *   px : 絶対値(density倍する。下記density対応参照)
     *   em : parentFontSize(継承元の計算済みfontSize)を基準に乗算
     *   %  : 同じくparentFontSizeを基準にした百分率
     *   rem: ROOT_FONT_SIZE_PX(簡易実装では16px固定)を基準に乗算
     *   vw/vh: ビューポート幅/高さ(CSS px基準)に対する百分率
     * 未対応の単位・パース不能な値は、UserAgentStylesの32px等が
     * 無言で16pxに化けていた旧不具合(parsePx()の暗黙フォールバック)を
     * 繰り返さないよう、「変更前のfontSizeを維持する」(=このdeclarationは無視する)
     * という安全側の挙動にする。16fへの固定フォールバックは行わない。
     *
     * density対応(2026-08): px/rem/vw/vhはCSS側の値をそのままdensity倍する。em/%は
     * parentFontSizeに対する相対値で、parentFontSize自体が既にdensity済みなので
     * 二重に掛けない(parseLengthのem branchと同じ考え方)。
     */
    private fun parseFontSize(value: String, parentFontSize: Float, currentFontSize: Float): Float {
        val trimmed = value.trim()
        return when {
            trimmed.endsWith("px") -> trimmed.removeSuffix("px").toFloatOrNull()?.let { it * density } ?: currentFontSize
            trimmed.endsWith("em") -> trimmed.removeSuffix("em").toFloatOrNull()?.let { it * parentFontSize } ?: currentFontSize
            trimmed.endsWith("%") -> trimmed.removeSuffix("%").toFloatOrNull()?.let { it / 100f * parentFontSize } ?: currentFontSize
            trimmed.endsWith("rem") -> trimmed.removeSuffix("rem").toFloatOrNull()?.let { it * ROOT_FONT_SIZE_PX * density } ?: currentFontSize // ルート基準remは簡易実装では16px固定基準
            trimmed.endsWith("vw") -> trimmed.removeSuffix("vw").toFloatOrNull()?.let { it / 100f * viewportWidthCssPx * density } ?: currentFontSize
            trimmed.endsWith("vh") -> trimmed.removeSuffix("vh").toFloatOrNull()?.let { it / 100f * viewportHeightCssPx * density } ?: currentFontSize
            else -> currentFontSize
        }
    }

    /**
     * margin専用のshorthand展開。padding同様の1〜4値展開("上/上下+左右/上+左右+下/上右下左")に
     * 加え、"auto"トークンをleft/right個別に検出する(margin:auto centering、2026-08対応)。
     * top/bottomの"auto"はこのエンジンの(フレックスコンテキスト等を持たない)ブロックフロー
     * 内では常に0として扱ってよい値のため、parseLengthOrPercent()の非数値→Px(0)フォールバックに
     * 素直に乗せている(padding用のparseBoxEdgesShorthandと処理を分けたのはこのため)。
     */
    private data class ParsedMargin(val edges: BoxEdges, val leftAuto: Boolean, val rightAuto: Boolean)

    private fun parseMarginShorthand(value: String, current: BoxEdges, currentLeftAuto: Boolean, currentRightAuto: Boolean, fontSize: Float): ParsedMargin {
        val tokens = value.trim().split(Regex("\\s+")).filter { it.isNotBlank() }
        if (tokens.size !in 1..4) return ParsedMargin(current, currentLeftAuto, currentRightAuto)

        // "auto"トークンはparseLengthOrPercent()に通すとPx(0)になる(auto自体は
        // marginLeftAuto/marginRightAutoという別フラグ側で表現するため、BoxEdges側の
        // 値としては0で構わない——resolveHorizontalMargins()がautoフラグを見て
        // このPx(0)を無視し、leftover配分に置き換える)。
        val values = tokens.map { parseLengthOrPercent(it, fontSize) }
        val edges = when (tokens.size) {
            1 -> BoxEdges(values[0], values[0], values[0], values[0])
            2 -> BoxEdges(values[0], values[1], values[0], values[1])
            3 -> BoxEdges(values[0], values[1], values[2], values[1])
            else -> BoxEdges(values[0], values[1], values[2], values[3]) // 4値
        }

        // shorthandの並び(上, 右, 下, 左)に沿って、right/leftに対応するトークンだけ
        // "auto"かどうかを見る。値の数によって右・左が同じトークンを共有する場合がある
        // (例: 1値指定の"auto"は上下左右すべてauto扱いになる)。
        fun isAuto(token: String) = token.trim().equals("auto", ignoreCase = true)
        val (rightAuto, leftAuto) = when (tokens.size) {
            1 -> isAuto(tokens[0]) to isAuto(tokens[0])
            4 -> isAuto(tokens[1]) to isAuto(tokens[3])
            else -> isAuto(tokens[1]) to isAuto(tokens[1]) // 2値・3値はどちらも右=左のトークンを共有
        }
        return ParsedMargin(edges, leftAuto, rightAuto)
    }

    /**
     * margin/padding共通の長さ・百分率パーサー。px/em/rem/単位なし数値はparseLength()に
     * そのまま委譲し、"%"だけこちらでCssValue.Percentとして扱う(2026-08、margin/paddingの
     * %対応。以前はここで0にフォールバックしていた)。
     *
     *   px/em/rem/単位なし: parseLength()の結果をCssValue.Pxとして返す
     *   %: CssValue.Percent(実際のpx解決はLayoutEngine.resolveEdge()がレイアウト時に
     *      containing blockの"幅"を基準に行う——CSS仕様上、margin/paddingの%は
     *      top/bottom/left/rightいずれも幅基準になる点に注意。density倍しない
     *      (widthの%指定(parseCssValue)と同じ考え方。比率なので適用先が既に
     *      物理px基準なら二重に掛ける必要が無い)。
     *
     * fontSizeには呼び出し時点のstyle.fontSize(このカスケードでここまでに確定した
     * 値)を渡すこと。同じ要素で"font-size"宣言がmarginより後に来る場合、その宣言が
     * 反映される前の値を使ってしまう点は既知の制約(この簡易的な逐次カスケード評価
     * 全体に共通する制約で、font-size自体のem/%解決がparentFontSizeという
     * "常に確定済みの値"を基準にしているのとは事情が異なる)。
     */
    private fun parseLengthOrPercent(value: String, fontSize: Float): CssValue {
        val trimmed = value.trim()
        if (trimmed.endsWith("%")) {
            return CssValue.Percent(trimmed.removeSuffix("%").toFloatOrNull() ?: 0f)
        }
        return CssValue.Px(parseLength(trimmed, fontSize))
    }

    /**
     * margin/padding共通の長さパーサー(%を除く)。px/em/rem/単位なし数値に対応する
     * (2026-08、px単位のみだった旧parsePxOrZero()を拡張)。
     *   px    : 絶対値そのまま
     *   em    : "その要素自身の"計算済みfontSizeを基準に乗算する。font-sizeのem
     *           (parentFontSize基準)とは基準が異なる点に注意——CSS仕様上、
     *           margin/paddingのemは常に要素自身のfont-sizeを基準にする。
     *   rem   : ROOT_FONT_SIZE_PX(簡易実装では16px固定)を基準に乗算
     *   単位なし: pxとして扱う(旧実装の寛容な挙動を踏襲)
     *   %     : 非対応、0にフォールバックする(この関数はFloatを返す都合上、%を
     *           「px値」として表現できないため)。2026-08、margin/paddingの%対応時、
     *           %の解決だけはこの関数を呼ぶ前段のparseLengthOrPercent()側で
     *           個別に処理するようにした(CssValue.Percentとして保持し、実際のpx解決は
     *           containing blockの幅が判明するLayoutEngine.resolveEdge()まで遅延させる
     *           ——font-sizeのようにStyleResolverの時点で確定できる値とは事情が異なる)。
     *           そのため、margin/padding経由でこの関数に"%"が渡ってくることは無い想定
     *           (呼び出し元は必ずparseLengthOrPercent()経由にすること)。
     * fontSizeには呼び出し時点のstyle.fontSize(このカスケードでここまでに確定した
     * 値)を渡すこと。同じ要素で"font-size"宣言がmarginより後に来る場合、その宣言が
     * 反映される前の値を使ってしまう点は既知の制約(この簡易的な逐次カスケード評価
     * 全体に共通する制約で、font-size自体のem/%解決がparentFontSizeという
     * "常に確定済みの値"を基準にしているのとは事情が異なる)。
     *
     * density対応(2026-08): px/rem/単位なしの各branchはCSS側の値をそのままdensity倍する。
     * emだけは掛けない——fontSize引数は(parseFontSize側で)既にdensity済みの値として
     * 渡ってくるので、ここでさらに掛けると二重にスケールしてしまう。
     *
     * vw/vh対応(2026-08): ビューポート幅/高さ(CSS px基準、コンストラクタ引数
     * viewportWidthCssPx/viewportHeightCssPx)に対する百分率として解決し、density倍する。
     * vmin/vmaxは今回非対応(0にフォールバック)。
     */
    private fun parseLength(value: String, fontSize: Float): Float {
        val trimmed = value.trim()
        return when {
            trimmed.endsWith("rem") -> trimmed.removeSuffix("rem").toFloatOrNull()?.let { it * ROOT_FONT_SIZE_PX * density } ?: 0f
            trimmed.endsWith("em") -> trimmed.removeSuffix("em").toFloatOrNull()?.let { it * fontSize } ?: 0f
            trimmed.endsWith("vw") -> trimmed.removeSuffix("vw").toFloatOrNull()?.let { it / 100f * viewportWidthCssPx * density } ?: 0f
            trimmed.endsWith("vh") -> trimmed.removeSuffix("vh").toFloatOrNull()?.let { it / 100f * viewportHeightCssPx * density } ?: 0f
            trimmed.endsWith("px") -> trimmed.removeSuffix("px").toFloatOrNull()?.let { it * density } ?: 0f
            else -> trimmed.toFloatOrNull()?.let { it * density } ?: 0f
        }
    }

    /**
     * CSSのmargin/padding shorthand(1〜4値)をBoxEdgesへ展開する。
     *   1値: 全辺同じ
     *   2値: 上下, 左右
     *   3値: 上, 左右, 下
     *   4値: 上, 右, 下, 左(時計回り)
     * パース不能な値数の場合は変更前のBoxEdgesをそのまま返す(安全側)。
     * 2026-08、margin/paddingの%対応にあわせparseLengthOrPercent()を使うよう変更
     * (以前は%を0にフォールバックするparseLength()を使っていた)。
     */
    private fun parseBoxEdgesShorthand(value: String, current: BoxEdges, fontSize: Float): BoxEdges {
        val parts = value.trim().split(Regex("\\s+")).filter { it.isNotBlank() }.map { parseLengthOrPercent(it, fontSize) }
        return when (parts.size) {
            1 -> BoxEdges(parts[0], parts[0], parts[0], parts[0])
            2 -> BoxEdges(parts[0], parts[1], parts[0], parts[1])
            3 -> BoxEdges(parts[0], parts[1], parts[2], parts[1])
            4 -> BoxEdges(parts[0], parts[1], parts[2], parts[3])
            else -> current
        }
    }

    /**
     * text-align専用パーサー。left/center/rightのみ対応(justifyはTextAlign未定義のため今回は非対応)。
     * 未対応・パース不能な値は安全側としてLEFTへフォールバックする
     * (font-sizeと違い、text-alignは「変更前の値を維持」より「規定値に戻す」方が
     * ブラウザの一般的な挙動に近く、かつ他プロパティでの実装(parseDisplay/parsePosition)とも
     * パターンが揃うため)。
     */
    private fun parseTextAlign(value: String): TextAlign = when (value.trim()) {
        "center" -> TextAlign.CENTER
        "right" -> TextAlign.RIGHT
        "left" -> TextAlign.LEFT
        else -> TextAlign.LEFT
    }

    /**
     * text-decoration専用パーサー。"underline"トークンの有無だけを見る(2026-08対応)。
     * "none"や未対応値(line-through/overline等)はNONEへフォールバックする——
     * text-alignと同じく、パース不能値は規定値に戻す方が安全側のため。
     */
    private fun parseTextDecoration(value: String): TextDecoration =
        if (value.trim().split(Regex("\\s+")).any { it.equals("underline", ignoreCase = true) }) {
            TextDecoration.UNDERLINE
        } else {
            TextDecoration.NONE
        }

    /**
     * flex-direction。row-reverse/column-reverseは「逆順配置」までは対応しておらず、
     * 単にrow/columnと同じ扱いにフォールバックする(2026-08、Flexbox初期実装のスコープ外。
     * LayoutEngine.layoutFlexRow/Column参照)。
     */
    private fun parseFlexDirection(value: String): FlexDirection = when (value.trim()) {
        "column", "column-reverse" -> FlexDirection.COLUMN
        else -> FlexDirection.ROW
    }

    private fun parseJustifyContent(value: String): JustifyContent = when (value.trim()) {
        "center" -> JustifyContent.CENTER
        "flex-end", "end" -> JustifyContent.FLEX_END
        "space-between" -> JustifyContent.SPACE_BETWEEN
        "space-around" -> JustifyContent.SPACE_AROUND
        else -> JustifyContent.FLEX_START
    }

    private fun parseAlignItems(value: String): AlignItems = when (value.trim()) {
        "center" -> AlignItems.CENTER
        "flex-end", "end" -> AlignItems.FLEX_END
        "flex-start", "start" -> AlignItems.FLEX_START
        else -> AlignItems.STRETCH
    }

    /**
     * flex shorthand。CSS仕様の主要パターンを実用範囲でカバーする簡易パーサー:
     *   none          -> grow:0 shrink:0 basis:auto(伸縮しない、自身の指定サイズのまま)
     *   auto          -> grow:1 shrink:1 basis:auto
     *   initial       -> grow:0 shrink:1 basis:auto(CSS初期値。実質「伸びないが縮む」)
     *   <number>      -> grow:<number> shrink:1 basis:0(最頻出パターン。`flex:1`等)
     *   <number> <number> [<basis>] -> grow shrink basis(3値指定)
     * 上記いずれにも一致しない値は無視し、現在のstyleをそのまま返す(安全側)。
     */
    private fun parseFlexShorthand(value: String, style: ComputedStyle): ComputedStyle {
        val trimmed = value.trim()
        when (trimmed) {
            "none" -> return style.copy(flexGrow = 0f, flexShrink = 0f, flexBasis = CssValue.Auto)
            "auto" -> return style.copy(flexGrow = 1f, flexShrink = 1f, flexBasis = CssValue.Auto)
            "initial" -> return style.copy(flexGrow = 0f, flexShrink = 1f, flexBasis = CssValue.Auto)
        }

        val tokens = trimmed.split(Regex("\\s+")).filter { it.isNotBlank() }
        if (tokens.isEmpty()) return style

        val numbers = tokens.mapNotNull { it.toFloatOrNull() }
        val basisToken = tokens.firstOrNull { it.toFloatOrNull() == null }
        if (numbers.isEmpty() && basisToken == null) return style // パース不能、変更なし

        val grow = numbers.getOrNull(0) ?: 1f
        val shrink = numbers.getOrNull(1) ?: 1f
        // 数値のみ(basisToken無し)の場合、CSS仕様通りbasis:0%(=growだけで幅を決める
        // 最頻出パターン)にする。basisTokenがある場合はそれを解決する。
        val basis = basisToken?.let { parseCssValue(it) } ?: CssValue.Px(0f)
        return style.copy(flexGrow = grow, flexShrink = shrink, flexBasis = basis)
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

    /**
     * density対応(2026-08): Percentは比率なのでdensityを掛ける必要は無い
     * (適用先のavailableWidth側が既に物理px基準のため、掛けるとそちらと二重になる)。
     * Pxは絶対値なのでCSS側の値をそのままdensity倍する。
     */
    /**
     * density対応(2026-08): Percentは比率なのでdensityを掛ける必要は無い
     * (適用先のavailableWidth側が既に物理px基準のため、掛けるとそちらと二重になる)。
     * Pxは絶対値なのでCSS側の値をそのままdensity倍する。
     *
     * vw/vh対応(2026-08): ビューポート幅/高さ(CSS px基準)に対する百分率として
     * 物理pxへ解決し、CssValue.Pxとして返す(width/heightの場合、vw/vhはcontaining
     * blockではなく常にビューポート基準なので、レイアウト時に解決するPercentとは
     * 扱いを分け、StyleResolverの時点でPxへ確定させてしまう)。
     */
    private fun parseCssValue(value: String): CssValue = when {
        value == "auto" -> CssValue.Auto
        value.endsWith("%") -> CssValue.Percent(value.removeSuffix("%").toFloatOrNull() ?: 0f)
        value.endsWith("vw") -> CssValue.Px(
            (value.removeSuffix("vw").toFloatOrNull() ?: 0f) / 100f * viewportWidthCssPx * density,
        )
        value.endsWith("vh") -> CssValue.Px(
            (value.removeSuffix("vh").toFloatOrNull() ?: 0f) / 100f * viewportHeightCssPx * density,
        )
        value.endsWith("px") -> CssValue.Px((value.removeSuffix("px").toFloatOrNull() ?: 0f) * density)
        else -> CssValue.Auto
    }
}
