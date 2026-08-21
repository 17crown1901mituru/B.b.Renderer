package com.B.b.Renderer.style

data class ComputedStyle(
    // --- 継承されるプロパティ ---
    val color: Color = Color.BLACK,
    val fontSize: Float = 16f,
    val fontWeight: Int = 400,
    val textAlign: TextAlign = TextAlign.LEFT,
    val pointerEvents: PointerEvents = PointerEvents.AUTO,

    // --- 継承されないプロパティ ---
    val display: Display = Display.BLOCK,
    val position: Position = Position.STATIC,
    val width: CssValue = CssValue.Auto,
    val height: CssValue = CssValue.Auto,
    val margin: BoxEdges = BoxEdges.ZERO,
    // margin-left/right:auto(中央寄せ)の指定有無(2026-08対応)。marginのfloat値自体は
    // auto指定時0のまま保持し、実際の配分はLayoutEngine.resolveHorizontalMargins()が
    // レイアウト時にavailableWidthを見て計算する(StyleResolverの時点ではまだ
    // containing blockの幅が分からないため、ここではフラグを立てるだけに留める)。
    val marginLeftAuto: Boolean = false,
    val marginRightAuto: Boolean = false,
    val padding: BoxEdges = BoxEdges.ZERO,
    val backgroundColor: Color = Color.TRANSPARENT,
    val zIndex: Int? = null,
    // text-decoration(2026-08、<a>タグ対応)。CSS仕様上は継承プロパティではない
    // (装飾線自体は要素ごとに描画されるべきもの)ため、他の非継承プロパティと同じ扱いにする。
    // UserAgentStylesが`a { text-decoration: underline }`を直接<a>要素に指定するため、
    // それで用は足りる(子孫要素側で改めて指定する必要が出てくるケースは今回のスコープ外)。
    val textDecoration: TextDecoration = TextDecoration.NONE,

    // ---- Flexbox(2026-08対応) ----
    // コンテナ側(display:flexの要素自身)が使うプロパティ。
    val flexDirection: FlexDirection = FlexDirection.ROW,
    val justifyContent: JustifyContent = JustifyContent.FLEX_START,
    val alignItems: AlignItems = AlignItems.STRETCH,
    // gap。row-gap/column-gapを個別に持たず、簡易実装として主軸方向の間隔にのみ使う
    // (flex-wrap非対応のため交差軸方向に複数行が並ぶことが無く、column-gapの出番が無い)。
    val gap: Float = 0f,
    // アイテム側(display:flexコンテナの子要素)が使うプロパティ。コンテナ自身では未使用。
    val flexGrow: Float = 0f,
    val flexShrink: Float = 1f,
    val flexBasis: CssValue = CssValue.Auto,
) {
    fun inheritableSubset(): ComputedStyle = ComputedStyle(
        color = color,
        fontSize = fontSize,
        fontWeight = fontWeight,
        textAlign = textAlign,
        pointerEvents = pointerEvents,
        // 非継承プロパティは意図的にデフォルト値へリセット
    )
}

enum class Display { BLOCK, INLINE, FLEX, NONE }
enum class Position { STATIC, RELATIVE, ABSOLUTE, FIXED, STICKY }
enum class TextAlign { LEFT, CENTER, RIGHT }
enum class PointerEvents { AUTO, NONE }
enum class TextDecoration { NONE, UNDERLINE }
enum class FlexDirection { ROW, COLUMN }
enum class JustifyContent { FLEX_START, FLEX_END, CENTER, SPACE_BETWEEN, SPACE_AROUND }
enum class AlignItems { STRETCH, FLEX_START, FLEX_END, CENTER }

sealed class CssValue {
    object Auto : CssValue()
    data class Px(val value: Float) : CssValue()
    data class Percent(val value: Float) : CssValue()
}

// 2026-08、margin/paddingの%対応。以前はFloat(px解決済み)で持っていたが、
// %指定はStyleResolverの時点ではまだcontaining blockの幅(=LayoutEngine側の
// availableWidth)が分からず解決できないため、width/heightと同じCssValueベースに
// 変更した。実際のpx解決はLayoutEngine.resolveEdge()がレイアウト時に行う。
data class BoxEdges(val top: CssValue, val right: CssValue, val bottom: CssValue, val left: CssValue) {
    companion object {
        val ZERO = BoxEdges(CssValue.Px(0f), CssValue.Px(0f), CssValue.Px(0f), CssValue.Px(0f))
    }
}

data class Color(val r: Int, val g: Int, val b: Int, val a: Int = 255) {
    companion object {
        val BLACK = Color(0, 0, 0)
        val WHITE = Color(255, 255, 255)
        val TRANSPARENT = Color(0, 0, 0, 0)
    }

    fun toHex(): String = String.format("#%02X%02X%02X%02X", r, g, b, a)
}
