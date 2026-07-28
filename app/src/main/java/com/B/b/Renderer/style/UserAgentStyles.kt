package com.B.b.Renderer.style

/**
 * 実ブラウザのUser Agent Stylesheetに相当する、タグ既定スタイルの最小実装。
 *
 * 背景: このエンジンのCssParser/StyleResolverはページ側CSSしか解決せず、
 * <h1>や<p>等のタグごとの既定フォントサイズを持っていなかった。そのため、
 * CSSをほとんど持たないページ(example.com等)では、見出しも本文も
 * 全く同じ小さいサイズで描画され、実ブラウザと比べて視覚的な階層が
 * 失われていた(2026-07、白画面バグ解消後にスクショ比較で発覚)。
 *
 * 制約: StyleResolverが現状対応するCSSプロパティ(color/background-color/
 * font-size/display/position/width/height/z-index/pointer-events)の
 * 範囲内でのみ既定値を持たせている。font-weight(太字)やmargin(余白)は
 * StyleResolver/ComputedStyleが未対応のため、ここでは扱えない
 * (今後それらのプロパティ対応を追加する際、あわせてこのファイルにも
 * 既定値を足すこと。現状は見出しの「サイズ」だけが実ブラウザと揃う)。
 *
 * 単位はpxのみで指定すること: CssParser経由で解決されるfont-sizeは
 * StyleResolver.parsePx()が"px"サフィックスの数値しか解釈せず、
 * em/rem/キーワード(large等)は未対応で16px(既定)にフォールバックして
 * しまうため、ここでは全て絶対px値(16pxを基準にした換算値)で指定する。
 */
object UserAgentStyles {
    private const val DEFAULT_CSS = """
        h1 { font-size: 32px; }
        h2 { font-size: 24px; }
        h3 { font-size: 19px; }
        h4 { font-size: 16px; }
        h5 { font-size: 13px; }
        h6 { font-size: 11px; }
        p, body, div { font-size: 16px; }
        small { font-size: 13px; }
        a { color: #0000EE; }
    """

    /**
     * ページ側のルールより必ず優先度が下になるよう、sourceOrderを
     * 大きく負の値にずらしたルール一覧。
     *
     * StyleResolver.resolve()は「詳細度(specificity)が同じ場合、
     * sourceOrderが大きい方を後から適用して勝たせる」という単純な
     * 実装になっている(CSSの「後発の同詳細度ルールが勝つ」という
     * 仕様の簡易再現)。ページ側がsourceOrder=0から採番を始めるため、
     * ここでは必ずそれより小さい値にして、同じタグセレクタ(例: h1)を
     * ページ側が独自に定義していた場合はページ側が確実に上書きできる
     * ようにしている。
     */
    val rules: List<CssRule> by lazy {
        CssParser().parse(DEFAULT_CSS).rules.mapIndexed { index, rule ->
            rule.copy(sourceOrder = index - 1_000_000)
        }
    }
}
