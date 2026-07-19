package com.B.b.Renderer.render.gpu

import android.graphics.Bitmap
import android.opengl.GLES30
import android.opengl.GLUtils

/**
 * 固定サイズ(デフォルト2048x2048)のテクスチャ1枚に、複数のテキストラスタライズ結果を
 * 「棚(shelf)」方式で敷き詰めるアトラス。
 *
 * GL_TEXTURE_2Dを1枚だけ確保し、以降はglTexSubImage2Dで該当領域だけを更新する
 * (テクスチャ全体の再アップロードは発生しない)。allocate()が満杯でnullを返したら、
 * 呼び出し側(TextTextureCache)は新しいページを追加する。
 *
 * 制約: 一度確保した領域を個別に解放する機能は無い(shelfは前進のみ)。
 * 大量のテキスト変化で領域が枯渇した場合はTextTextureCache側でページ全体を作り直す。
 */
class TextAtlas(private val size: Int = 2048) {

    data class Region(val u0: Float, val v0: Float, val u1: Float, val v1: Float)

    var textureId: Int = -1
        private set

    private var shelfY = 0
    private var shelfHeight = 0
    private var cursorX = 0

    /** GLスレッド上で呼ぶこと */
    fun init() {
        val ids = IntArray(1)
        GLES30.glGenTextures(1, ids, 0)
        textureId = ids[0]

        GLES30.glBindTexture(GLES30.GL_TEXTURE_2D, textureId)
        GLES30.glTexParameteri(GLES30.GL_TEXTURE_2D, GLES30.GL_TEXTURE_MIN_FILTER, GLES30.GL_LINEAR)
        GLES30.glTexParameteri(GLES30.GL_TEXTURE_2D, GLES30.GL_TEXTURE_MAG_FILTER, GLES30.GL_LINEAR)
        GLES30.glTexParameteri(GLES30.GL_TEXTURE_2D, GLES30.GL_TEXTURE_WRAP_S, GLES30.GL_CLAMP_TO_EDGE)
        GLES30.glTexParameteri(GLES30.GL_TEXTURE_2D, GLES30.GL_TEXTURE_WRAP_T, GLES30.GL_CLAMP_TO_EDGE)

        // 空の土台を確保しておき、以後の更新はglTexSubImage2Dのみで済ませる
        val blank = Bitmap.createBitmap(size, size, Bitmap.Config.ARGB_8888)
        GLUtils.texImage2D(GLES30.GL_TEXTURE_2D, 0, blank, 0)
        blank.recycle()
    }

    /** (width,height)の矩形を確保できればUV範囲を返す。収まらなければnull(呼び出し側で新ページへ)。 */
    fun allocate(width: Int, height: Int): Region? {
        if (width > size || height > size) return null // アトラス自体に入らない巨大テキストは非対応

        if (cursorX + width > size) {
            shelfY += shelfHeight
            cursorX = 0
            shelfHeight = 0
        }
        if (shelfY + height > size) return null // このページは満杯

        val x = cursorX
        val y = shelfY
        cursorX += width
        shelfHeight = maxOf(shelfHeight, height)

        return Region(
            u0 = x.toFloat() / size,
            v0 = y.toFloat() / size,
            u1 = (x + width).toFloat() / size,
            v1 = (y + height).toFloat() / size,
        )
    }

    fun upload(region: Region, bitmap: Bitmap) {
        val x = (region.u0 * size).toInt()
        val y = (region.v0 * size).toInt()
        GLES30.glBindTexture(GLES30.GL_TEXTURE_2D, textureId)
        GLUtils.texSubImage2D(GLES30.GL_TEXTURE_2D, 0, x, y, bitmap)
    }

    fun release() {
        if (textureId != -1) {
            GLES30.glDeleteTextures(1, intArrayOf(textureId), 0)
            textureId = -1
        }
    }
}
