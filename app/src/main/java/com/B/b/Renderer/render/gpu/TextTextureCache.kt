package com.B.b.Renderer.render.gpu

import android.graphics.Bitmap
import android.graphics.Canvas
import android.graphics.Color as AndroidColor
import android.graphics.Paint

/**
 * テキストをBitmapへラスタライズし、共有アトラス(TextAtlas)へ敷き詰める。
 * seq+内容のハッシュでキャッシュし、変化のないテキストは再ラスタライズもGPU転送も行わない。
 *
 * 以前は「1要素1テクスチャ・1drawCall」だったが、複数要素を同じアトラスページに集約することで
 * drawCall数を「テキスト要素数」ではなく「アトラスページ数」(通常1)に削減する。
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

    fun getOrCreate(
        seq: Long,
        text: String,
        fontSizePx: Float,
        colorArgb: Int,
    ): Entry? {
        if (text.isBlank()) return null
        val contentHash = "$text|$fontSizePx|$colorArgb".hashCode()

        cache[seq]?.let { existing ->
            if (existing.contentHash == contentHash) return existing
        }

        val bitmap = rasterize(text, fontSizePx, colorArgb)
        val entry = allocateAndUpload(seq, bitmap, contentHash)
        bitmap.recycle()
        return entry
    }

    private fun rasterize(text: String, fontSizePx: Float, colorArgb: Int): Bitmap {
        val paint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
            textSize = fontSizePx
            color = colorArgb
        }
        val width = paint.measureText(text).toInt().coerceAtLeast(1)
        val fontMetrics = paint.fontMetrics
        val height = (fontMetrics.bottom - fontMetrics.top).toInt().coerceAtLeast(1)

        val bitmap = Bitmap.createBitmap(width, height, Bitmap.Config.ARGB_8888)
        val canvas = Canvas(bitmap)
        canvas.drawColor(AndroidColor.TRANSPARENT)
        canvas.drawText(text, 0f, -fontMetrics.top, paint)
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
        val region = newPage.allocate(bitmap.width, bitmap.height) ?: return null
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
