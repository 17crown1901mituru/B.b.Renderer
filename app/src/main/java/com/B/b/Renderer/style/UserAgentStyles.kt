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
 * 2026-07追記: marginも同時に未実装だったため(StyleResolverがmarginプロパティを
 * 一切解決していなかった)、見出し・本文・ページ端が隙間なくくっつき、
 * 全体が画面左上に小さく固まって見える問題があった。StyleResolverにmargin対応を
 * 追加したのと合わせて、ここにも既定marginを足す。値は実ブラウザの
 * 既定em値(例: h1は0.67em)を、各タグのfont-size既定値で絶対px換算した近似値。
 * ブラウザ実装によって多少の差はあるが、視覚的階層を再現する目的としては十分。
 *
 * 制約: StyleResolverが現状対応するCSSプロパティ(color/background-color/
 * font-size/display/position/width/height/z-index/pointer-events/margin系/
 * text-align/text-decoration)の範囲内でのみ既定値を持たせている。
 * font-weight(太字)はComputedStyleにフィールドはあるがStyleResolverが未対応のため、
 * ここでは扱えない(今後対応を追加する際、あわせてこのファイルにも既定値を足すこと)。
 *
 * 2026-08追記: <a>タグのタップ遷移(navigate)対応にあわせ、実ブラウザの既定表現
 * (青字+下線)をtext-decoration: underline で再現。href解決・実際のタップ判定/遷移は
 * StyleResolver/UserAgentStylesの範囲外(InputHandling.dispatchClick→
 * EngineHostView.onNavigate→EngineActivity.navigateForegroundTo)で行う。
 *
 * paddingはComputedStyle/LayoutEngine/GLEngineRendererが既に参照しているにも
 *関わらず、StyleResolverがpaddingプロパティを一切解決していない(marginと
 * 同種の未実装)。UAスタイルとしての既定paddingは無いため実害は目立ちにくいが、
 * ページ側CSSでpadding指定しても反映されない状態は残っている(別途対応要)。
 *
 * 単位はpxのみで指定すること: CssParser経由で解決されるfont-size/marginは
 * StyleResolverのparsePx()/parsePxOrZero()が"px"サフィックスの数値しか
 * 解釈せず、em/rem/キーワードは未対応でフォールバック値になってしまうため、
 * ここでは全て絶対px値(各タグのfont-size既定値を基準にした換算値)で指定する。
 */
object UserAgentStyles {
    private const val DEFAULT_CSS = """
        h1 { font-size: 32px; margin: 21px 0; }
        h2 { font-size: 24px; margin: 20px 0; }
        h3 { font-size: 19px; margin: 19px 0; }
        h4 { font-size: 16px; margin: 21px 0; }
        h5 { font-size: 13px; margin: 22px 0; }
        h6 { font-size: 11px; margin: 25px 0; }
        p { font-size: 16px; margin: 16px 0; }
        body { font-size: 16px; margin: 8px; }
        div { font-size: 16px; }
        small { font-size: 13px; }
        a { color: #0000EE; text-decoration: underline; }
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