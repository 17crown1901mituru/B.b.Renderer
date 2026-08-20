package com.B.b.Renderer.render.gpu

import android.graphics.Bitmap
import android.graphics.Canvas
import android.graphics.Color as AndroidColor
import android.graphics.Paint
import android.text.Layout
import android.text.StaticLayout
import android.text.TextPaint
import com.B.b.Renderer.style.TextAlign

/**
 * テキストをBitmapへラスタライズし、共有アトラス(TextAtlas)へ敷き詰める。
 * seq+内容のハッシュでキャッシュし、変化のないテキストは再ラスタライズもGPU転送も行わない。
 *
 * 以前は「1要素1テクスチャ・1drawCall」だったが、複数要素を同じアトラスページに集約することで
 * drawCall数を「テキスト要素数」ではなく「アトラスページ数」(通常1)に削減する。
 *
 * 2026-08、複数行折り返し対応。以前は`Paint.measureText()`で1行のビットマップとして
 * ラスタライズしており、ボックス幅を無視して常に横一直線に描画していた
 * (density対応で文字が大きくなったことで、長い段落がアトラスページのサイズ上限を
 * 超えて描画自体が消える事故につながった)。`android.text.StaticLayout`
 * (Android SDK標準。Unicode基準の改行位置判定を内蔵)に置き換え、実際に複数行へ
 * 折り返した上でラスタライズするようにした。
 *
 * 2026-08、<a>タグのインラインフロー対応にあわせ、プレーンテキストのみを受け取る
 * getOrCreate(text: String, ...)は廃止し、android.text.Spanned(SpannableStringBuilder)を
 * 受け取るgetOrCreateSpanned()に一本化した。<a>等のdisplay:inline要素が周囲のテキストと
 * 混ざって折り返される(LayoutEngine.layoutInlineRun参照)場合、色や下線が単語ごとに
 * 異なり得るため、1つのStaticLayoutで色・下線混在のテキストを描画する必要がある——
 * ForegroundColorSpan/UnderlineSpanで範囲ごとに指定できるSpanned前提の実装にすることで、
 * 「単一色のテキスト」も「色混在のテキスト」も同じ経路で扱える(GLEngineRenderer.
 * buildInlineSpanned()が呼び出し側で組み立てる)。
 */
class TextTextureCache {

    data class Entry(
        val atlasPageIndex: Int,
        val region: TextAtlas.Region,
        val width: Int,
        val height: Int,
        val contentHash: Int,
    )

    private val pages = mutableListOf<TextAtlas>()
    private val cache = mutableMapOf<Long, Entry>()

    /** ページ数がこれを超えたら、生存中のエントリのみを残して作り直す(簡易デフラグ) */
    private val rebuildPageThreshold = 4

    /**
     * @param spanned 描画するテキスト。ForegroundColorSpan/UnderlineSpan等で
     *   範囲ごとに色・下線を指定済みのもの(GLEngineRenderer.buildInlineSpanned()参照)。
     * @param contentKey スパン情報まで含めたキャッシュ鍵の材料(プレーンな文字列だけでは
     *   スパンの変化を検知できないため、呼び出し側で色・下線等を織り込んだ文字列を渡すこと)。
     * @param fontSizePx 基準フォントサイズ。AbsoluteSizeSpanで個別に上書きされていない
     *   範囲に適用される。
     * @param maxWidthPx 折り返しの基準になる、要素のコンテンツボックス幅(px)。
     *   text-alignがcenter/rightの場合、行ごとの配置がこの幅を基準に計算されるため、
     *   ラスタライズ後のBitmapの意味も変わる(下記rasterize()参照)。
     */
    fun getOrCreateSpanned(
        seq: Long,
        spanned: CharSequence,
        contentKey: String,
        fontSizePx: Float,
        maxWidthPx: Int,
        textAlign: TextAlign,
    ): Entry? {
        if (spanned.isBlank()) return null
        val safeMaxWidth = maxWidthPx.coerceAtLeast(1)
        val contentHash = "$contentKey|$fontSizePx|$safeMaxWidth|$textAlign".hashCode()

        cache[seq]?.let { existing ->
            if (existing.contentHash == contentHash) return existing
        }

        val bitmap = rasterize(spanned, fontSizePx, safeMaxWidth, textAlign)
        val entry = allocateAndUpload(seq, bitmap, contentHash)
        bitmap.recycle()
        return entry
    }

    /**
     * center/rightは、StaticLayoutが行ごとの配置をmaxWidthPx基準で計算するため、
     * その計算結果をそのまま活かせるようBitmapもmaxWidthPxいっぱいの幅で作る
     * (ここで切り詰めてしまうと、center/rightの位置計算だけが空白の無い狭いBitmap基準に
     * ズレてしまう)。leftは単に各行を左詰めで描くだけなので、実際に使われた最大行幅まで
     * 切り詰めてアトラスの消費を抑える。
     */
    private fun rasterize(spanned: CharSequence, fontSizePx: Float, maxWidthPx: Int, textAlign: TextAlign): Bitmap {
        // 基準Paint。色・下線は各Span側で範囲指定されるため、ここでは既定色(黒)・
        // 下線無しのままでよい(Spanが無い範囲があった場合の保険的なフォールバック)。
        val paint = TextPaint(Paint.ANTI_ALIAS_FLAG).apply {
            textSize = fontSizePx
        }
        val alignment = when (textAlign) {
            TextAlign.CENTER -> Layout.Alignment.ALIGN_CENTER
            TextAlign.RIGHT -> Layout.Alignment.ALIGN_OPPOSITE
            TextAlign.LEFT -> Layout.Alignment.ALIGN_NORMAL
        }
        val layout = StaticLayout.Builder.obtain(spanned, 0, spanned.length, paint, maxWidthPx)
            .setAlignment(alignment)
            .setIncludePad(false)
            .build()

        val bitmapWidth = if (textAlign == TextAlign.LEFT) {
            var maxLineWidth = 0f
            for (i in 0 until layout.lineCount) {
                maxLineWidth = maxOf(maxLineWidth, layout.getLineWidth(i))
            }
            maxLineWidth.toInt().coerceAtLeast(1)
        } else {
            maxWidthPx
        }
        val bitmapHeight = layout.height.coerceAtLeast(1)

        val bitmap = Bitmap.createBitmap(bitmapWidth, bitmapHeight, Bitmap.Config.ARGB_8888)
        val canvas = Canvas(bitmap)
        canvas.drawColor(AndroidColor.TRANSPARENT)
        layout.draw(canvas)
        return bitmap
    }

    private fun allocateAndUpload(seq: Long, bitmap: Bitmap, contentHash: Int): Entry? {
        // 既存ページのどれかに空きがあればそこへ詰める
        for ((index, page) in pages.withIndex()) {
            val region = page.allocate(bitmap.width, bitmap.height)
            if (region != null) {
                page.upload(region, bitmap)
                val entry = Entry(index, region, bitmap.width, bitmap.height, contentHash)
                cache[seq] = entry
                return entry
            }
        }

        // どのページにも入らなかった。ページが増えすぎているなら作り直してから新規ページを足す。
        if (pages.size >= rebuildPageThreshold) {
            rebuild()
        }

        val newPage = TextAtlas().apply { init() }
        pages.add(newPage)
        val region = newPage.allocate(bitmap.width, bitmap.height)
        if (region == null) {
            // まっさらな新規ページにすら入らない状態。折り返し対応後はmaxWidthPx
            // (要素のコンテンツボックス幅)を超えることはまず無いが、極端に幅の広い
            // ボックス、または非常に多くの行を持つ要素だと起こり得るため、無言で
            // 消すのではなくログに残す(2026-08対応)。
            com.B.b.Renderer.debug.BehaviorAuditLog.record(
                com.B.b.Renderer.debug.BehaviorAuditLog.Category.RENDER_DIAG,
                "text texture too large for atlas page: ${bitmap.width}x${bitmap.height}",
            )
            return null
        }
        newPage.upload(region, bitmap)
        val entry = Entry(pages.lastIndex, region, bitmap.width, bitmap.height, contentHash)
        cache[seq] = entry
        return entry
    }

    /**
     * ページを全て破棄し空の状態に戻す簡易デフラグ。TextAtlasのshelf方式は個別領域の解放が
     * できないため、ページ数が閾値を超えたらまとめて回収する。
     * cacheもクリアするため、次フレームのgetOrCreateで各要素が自然に再ラスタライズ・再確保される
     * (呼び出し側は毎フレームgetOrCreateする設計のため、視覚的な欠落は起きない)。
     */
    private fun rebuild() {
        pages.forEach { it.release() }
        pages.clear()
        cache.clear()
    }

    fun invalidate(seq: Long) {
        cache.remove(seq)
        // shelf方式のため個別領域は解放されない。枯渇が進んだ場合はrebuild()側で回収する。
    }

    fun getPageTextureId(pageIndex: Int): Int = pages[pageIndex].textureId

    fun pageCount(): Int = pages.size

    fun releaseAll() {
        pages.forEach { it.release() }
        pages.clear()
        cache.clear()
    }
}
