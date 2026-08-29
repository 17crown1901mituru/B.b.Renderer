package com.B.b.Renderer.input

import android.graphics.Paint
import android.graphics.RectF

/**
 * テキスト選択(長押し+ドラッグ)のためのジオメトリ計算。SelectionInputHandler自体は
 * 「文字インデックスの範囲(startIndex/endIndex)」という抽象的な状態しか持たないため、
 * 実際に画面へ描画するハイライト矩形(RectF)への変換はこちらで行う。
 *
 * 【既知の制約】1つの要素(<p>text</p>のような、テキストのみを子に持つ要素)の
 * computedRectを「1行分の帯」として扱う単純化実装。以下は非対応:
 *   - 複数行に折り返されたテキスト(ハイライトが実際の行形状と一致せず、
 *     computedRect全体の高さを覆う1本の帯になる)
 *   - <a>等が混在するインラインフロー(layout/LayoutEngine.ktのinlineRuns)内の選択
 * 将来これらに対応する場合、LayoutEngineが行単位で持つInlineRunLayout側の情報
 * (Element.inlineRuns、core/Element.kt参照)を使って矩形を行ごとに分割する形に
 * 拡張すること。
 */

/**
 * 1文字ずつの描画幅(px)を測る。CanvasRenderer.render()と同じ「textSizeにfontSizeを
 * そのまま渡す」Paint設定に合わせているため、実際の描画結果とほぼ一致する
 * (フォント自体の違いや文字間カーニングまでは再現しない近似値)。
 */
fun measureCharWidths(text: String, fontSize: Float): List<Float> {
    val paint = Paint().apply { textSize = fontSize }
    return text.map { paint.measureText(it.toString()) }
}

/**
 * 選択範囲(startIndex..endIndex、endIndexは含まない)に対応するハイライト矩形を求める。
 * charWidthsの合計幅がboxRectの幅を超える(=折り返されている)場合でも矩形自体は
 * 1本のまま返す(上記クラスコメントの既知の制約を参照)。
 */
fun computeHighlightRects(
    boxRect: RectF,
    startIndex: Int,
    endIndex: Int,
    charWidths: List<Float>,
): List<RectF> {
    if (charWidths.isEmpty() || startIndex < 0 || endIndex <= startIndex) return emptyList()

    val clampedStart = startIndex.coerceIn(0, charWidths.size)
    val clampedEnd = endIndex.coerceIn(clampedStart, charWidths.size)
    if (clampedStart >= clampedEnd) return emptyList()

    var startX = boxRect.left
    for (i in 0 until clampedStart) startX += charWidths[i]

    var endX = startX
    for (i in clampedStart until clampedEnd) endX += charWidths[i]

    return listOf(RectF(startX, boxRect.top, endX, boxRect.bottom))
}
