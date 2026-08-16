package com.B.b.Renderer.render.gpu

import android.content.Context
import android.opengl.GLES30
import android.opengl.GLSurfaceView
import android.opengl.Matrix
import com.B.b.Renderer.benchmark.RenderTierBenchmark
import com.B.b.Renderer.core.Element
import com.B.b.Renderer.core.ImageElement
import com.B.b.Renderer.core.ImageLoadState
import com.B.b.Renderer.core.MediaElement
import com.B.b.Renderer.core.TextNode
import com.B.b.Renderer.input.resolvePaintOrder
import com.B.b.Renderer.layout.LayoutEngine
import com.B.b.Renderer.media.JsMediaElement
import com.B.b.Renderer.style.Display
import com.B.b.Renderer.style.TextAlign
import javax.microedition.khronos.egl.EGLConfig
import javax.microedition.khronos.opengles.GL10

class GLEngineRenderer(
    private val appContext: Context,
    private var layoutEngine: LayoutEngine,
    // 2026-07、起動直後の描画遅延調査用。GLEngineView.attach()呼び出し時点の
    // System.nanoTime()を受け取り、onSurfaceCreated発火までの差分をRENDER_DIAGへ記録する。
    // GLコンテキストは初回attach()時にしか作られない(setRendererは1回のみ)ため、
    // この値は「最初のattach()呼び出し時刻」で固定でよい。
    private val attachStartNanos: Long = System.nanoTime(),
) : GLSurfaceView.Renderer {

    private var benchmarkThisSession = false
    private var firstFrameDiagLogged = false
    private var emptyDrawsWarnLogged = false
    private var surfaceCreatedDiagLogged = false

    private val quadRenderer = QuadBatchRenderer()
    private val atlasQuadRenderer = AtlasQuadRenderer()
    private val oesQuadRenderer = OesQuadRenderer()
    // 2026-08、<img>描画用。テキストと違い1要素1テクスチャがほぼ自明な単位になる
    // (テキストのようにアトラスへまとめる最適化は今回は行わない。画像数が多いページで
    // drawCallが増える点は将来的な検討課題として残す)ため、既存のTexturedQuadRenderer
    // (これまで定義だけあって未使用だった)をそのまま使う。
    private val texturedQuadRenderer = TexturedQuadRenderer()
    private val textTextureCache = TextTextureCache()

    /** seq -> OES外部テクスチャID。動画要素ごとに1枚、初回描画時に確保する。 */
    private val videoTextureIds = mutableMapOf<Long, Int>()

    /**
     * seq -> 通常の2Dテクスチャ(sampler2D)ID。画像要素ごとに1枚、デコード完了後の
     * 初回描画時にCPU側Bitmapをアップロードして確保する。以降はこのマップを再利用するだけで、
     * Bitmap自体はアップロード後にImageElement.decodedImageから外して破棄する
     * (videoTextureIdsと同じ「seqキーのGPUリソースキャッシュ」パターン)。
     */
    private val imageTextureIds = mutableMapOf<Long, Int>()

    private val mvpMatrix = FloatArray(16)
    private val videoTexMatrix = FloatArray(16)
    private var viewportWidth = 1
    private var viewportHeight = 1

    override fun onSurfaceCreated(gl: GL10?, config: EGLConfig?) {
        GLES30.glClearColor(1f, 1f, 1f, 1f)
        quadRenderer.init()
        atlasQuadRenderer.init()
        oesQuadRenderer.init()
        texturedQuadRenderer.init()

        // 初回のみ、attach()呼び出しからここまでの遅延を計測する。2回目以降のonSurfaceCreated
        // (通常は発生しないが、GLコンテキストロスト等の異常系で再発火する可能性はある)では
        // attachStartNanosが古い値のままで意味を持たないため、初回のみログに出す。
        if (!surfaceCreatedDiagLogged) {
            surfaceCreatedDiagLogged = true
            val delayMs = (System.nanoTime() - attachStartNanos) / 1_000_000
            com.B.b.Renderer.debug.BehaviorAuditLog.record(
                com.B.b.Renderer.debug.BehaviorAuditLog.Category.RENDER_DIAG,
                "onSurfaceCreated fired (attach→onSurfaceCreated delay=${delayMs}ms)",
            )
        } else {
            com.B.b.Renderer.debug.BehaviorAuditLog.record(
                com.B.b.Renderer.debug.BehaviorAuditLog.Category.RENDER_DIAG,
                "onSurfaceCreated fired",
            )
        }

        // 未判定の端末でのみ、この新しいGLコンテキストの最初の数十フレームを計測する。
        benchmarkThisSession = RenderTierBenchmark.shouldRunSession(appContext)
        if (benchmarkThisSession) {
            RenderTierBenchmark.beginSession()
        }
        firstFrameDiagLogged = false
        emptyDrawsWarnLogged = false
    }

    override fun onSurfaceChanged(gl: GL10?, width: Int, height: Int) {
        viewportWidth = width.coerceAtLeast(1)
        viewportHeight = height.coerceAtLeast(1)
        GLES30.glViewport(0, 0, viewportWidth, viewportHeight)
    }

    /**
     * 正射影の上下端をscrollY分だけシフトすることでスクロールを表現する
     * (Canvas版のcanvas.translate(0, -scrollY)に相当)。zoomScaleは「見えている範囲の
     * 広さ」を縮めることで表現する(zoom>1ほど可視範囲=effectiveWidth/Heightが狭くなり、
     * 結果的に同じ図形が画面上で大きく映る。Canvas版のcanvas.scale(zoom,zoom)に相当)。
     * 毎フレーム呼ぶ必要があるため(以前はリサイズ時にしか再計算されず、
     * スクロール自体が反映されなかった)onSurfaceChangedからonDrawFrameへ移してある。
     */
    private fun updateProjection() {
        val scrollY = layoutEngine.scrollY
        val zoom = layoutEngine.zoomScale.coerceAtLeast(0.01f)
        val effectiveWidth = viewportWidth / zoom
        val effectiveHeight = viewportHeight / zoom
        Matrix.orthoM(
            mvpMatrix, 0,
            0f, effectiveWidth,
            scrollY + effectiveHeight, scrollY,
            -1f, 1f,
        )
    }

    override fun onDrawFrame(gl: GL10?) {
        val frameStartNanos = if (benchmarkThisSession) System.nanoTime() else 0L

        updateProjection()
        GLES30.glClear(GLES30.GL_COLOR_BUFFER_BIT)

        val root = layoutEngine.root
        val paintOrder = resolvePaintOrder(root)

        if (!firstFrameDiagLogged) {
            firstFrameDiagLogged = true
            val samples = paintOrder.take(3).joinToString(" / ") { el ->
                "<${el.tag}> rect=${el.computedRect} bg=${el.computedStyle.backgroundColor}"
            }
            com.B.b.Renderer.debug.BehaviorAuditLog.record(
                com.B.b.Renderer.debug.BehaviorAuditLog.Category.RENDER_DIAG,
                "viewport=${viewportWidth}x$viewportHeight root.rect=${root.computedRect} " +
                    "paintOrder.size=${paintOrder.size} samples=[$samples]",
            )
        }

        quadRenderer.beginFrame(maxQuads = paintOrder.size + 8)
        val textDraws = mutableListOf<Pair<Element, TextTextureCache.Entry>>()
        val videoDraws = mutableListOf<Pair<Element, Int>>() // element, oesTextureId
        val imageDraws = mutableListOf<Pair<Element, Int>>() // element, 2Dテクスチャid

        paintOrder.forEach { element ->
            val style = element.computedStyle
            if (style.display == Display.NONE) return@forEach

            val rect = element.computedRect
            if (style.backgroundColor.a > 0) {
                quadRenderer.addQuad(
                    x = rect.x.toFloat(),
                    y = rect.y.toFloat(),
                    width = rect.width.toFloat(),
                    height = rect.height.toFloat(),
                    r = style.backgroundColor.r / 255f,
                    g = style.backgroundColor.g / 255f,
                    b = style.backgroundColor.b / 255f,
                    a = style.backgroundColor.a / 255f,
                )
            }

            if (element is ImageElement) {
                if (element.loadState == ImageLoadState.LOADED) {
                    val bitmap = element.decodedImage as? android.graphics.Bitmap
                    val textureId = imageTextureIds[element.seq]
                        ?: bitmap?.let { bmp -> uploadBitmapTexture(bmp).also { imageTextureIds[element.seq] = it } }
                    // アップロード済みならCPU側Bitmapはもう不要(2回目以降のフレームでは
                    // bitmapは既にnullなのでこの代入は無害)。
                    element.decodedImage = null
                    if (textureId != null) imageDraws.add(element to textureId)
                }
                // imgタグにテキストの子ノードが入ることは通常無いが、念のため以降の
                // テキスト抽出処理はスキップして次の要素へ進む。
                return@forEach
            }

            if (element is MediaElement) {
                val controller = element.mediaController as? JsMediaElement
                // isVideoElementで判定する(hasVideoSurfaceは再生開始後にしかtrueにならず、
                // それだとbindTextureIdが一生呼ばれず再生側のpendingSurfacePlayerが解消しない)
                if (controller != null && controller.isVideoElement) {
                    val textureId = videoTextureIds.getOrPut(element.seq) {
                        oesQuadRenderer.createOesTexture().also { controller.bindTextureId(it) }
                    }
                    if (controller.updateTexImage(videoTexMatrix)) {
                        videoDraws.add(element to textureId)
                    }
                }
                // videoは自前でフレームを描くため、子ノード(フォールバック用テキスト等)のテキスト抽出はスキップ
                return@forEach
            }

            val text = element.children.filterIsInstance<TextNode>()
                .joinToString(" ") { it.data.trim() }
                .trim()
            if (text.isNotEmpty()) {
                val colorArgb = android.graphics.Color.argb(
                    style.color.a, style.color.r, style.color.g, style.color.b,
                )
                val entry = textTextureCache.getOrCreate(
                    seq = element.seq,
                    text = text,
                    fontSizePx = style.fontSize,
                    colorArgb = colorArgb,
                )
                if (entry != null) {
                    textDraws.add(element to entry)
                }
            }
        }

        quadRenderer.endFrameAndDraw(mvpMatrix)

        if (!emptyDrawsWarnLogged && paintOrder.isNotEmpty() &&
            textDraws.isEmpty() && videoDraws.isEmpty() && imageDraws.isEmpty()
        ) {
            emptyDrawsWarnLogged = true
            com.B.b.Renderer.debug.BehaviorAuditLog.record(
                com.B.b.Renderer.debug.BehaviorAuditLog.Category.RENDER_DIAG,
                "textDraws/videoDraws/imageDraws all empty despite paintOrder.size=${paintOrder.size} " +
                    "(quadRendererのみ描画。テキストテクスチャ生成に失敗している可能性)",
            )
        }

        // 画像は背景の直後・テキストより前に描く(キャプション等のテキストが画像の上に
        // 重なって読める方が自然なため)。1枚ごとに別テクスチャなので画像枚数だけdrawCallが出る
        // (テキストのようなアトラスまとめは今回未対応、上のtexturedQuadRenderer初期化コメント参照)。
        imageDraws.forEach { (element, textureId) ->
            val rect = element.computedRect
            texturedQuadRenderer.draw(
                x = rect.x.toFloat(),
                y = rect.y.toFloat(),
                width = rect.width.toFloat(),
                height = rect.height.toFloat(),
                textureId = textureId,
                mvpMatrix = mvpMatrix,
            )
        }

        // ページ(通常1〜数枚)ごとにグルーピングし、ページにつき1 drawCallでまとめて描画する。
        // 以前は「テキスト要素数」だけdrawCallが出ていたが、これで「アトラスページ数」に減る。
        textDraws.groupBy { it.second.atlasPageIndex }.forEach { (pageIndex, drawsInPage) ->
            atlasQuadRenderer.beginBatch(maxQuads = drawsInPage.size)
            drawsInPage.forEach { (element, entry) ->
                val rect = element.computedRect
                val style = element.computedStyle
                atlasQuadRenderer.addQuad(
                    x = resolveTextDrawX(rect, style, entry),
                    y = rect.y.toFloat() + style.padding.top,
                    width = entry.width.toFloat(),
                    height = entry.height.toFloat(),
                    region = entry.region,
                )
            }
            atlasQuadRenderer.endBatchAndDraw(
                textureId = textTextureCache.getPageTextureId(pageIndex),
                mvpMatrix = mvpMatrix,
            )
        }

        // 動画フレームはサンプラー型が異なる(samplerExternalOES)ため専用パスで最後に描画
        videoDraws.forEach { (element, oesTextureId) ->
            val rect = element.computedRect
            oesQuadRenderer.draw(
                x = rect.x.toFloat(),
                y = rect.y.toFloat(),
                width = rect.width.toFloat(),
                height = rect.height.toFloat(),
                oesTextureId = oesTextureId,
                texMatrix = videoTexMatrix,
                mvpMatrix = mvpMatrix,
            )
        }

        if (benchmarkThisSession) {
            // 通常のGLコマンドはキューイングされるだけで非同期に実行されるため、
            // glFinish()でGPU側の完了を待ってから計測を止めないと「積んだだけ」の時間しか測れない。
            // ベンチマーク中の限られたフレームだけの措置で、判定確定後は一切呼ばない。
            GLES30.glFinish()
            RenderTierBenchmark.recordFrame(appContext, System.nanoTime() - frameStartNanos)
        }
    }

    /**
     * デコード済みBitmapをGL_TEXTURE_2Dへアップロードする。呼び出しは必ずGLスレッド上
     * (=onDrawFrame内)から行うこと。アップロード後はCPU側のBitmapはもう不要なため
     * ここでrecycle()する(呼び出し元でelement.decodedImage側の参照も別途nullにしている)。
     */
    private fun uploadBitmapTexture(bitmap: android.graphics.Bitmap): Int {
        val ids = IntArray(1)
        GLES30.glGenTextures(1, ids, 0)
        val textureId = ids[0]
        GLES30.glBindTexture(GLES30.GL_TEXTURE_2D, textureId)
        GLES30.glTexParameteri(GLES30.GL_TEXTURE_2D, GLES30.GL_TEXTURE_MIN_FILTER, GLES30.GL_LINEAR)
        GLES30.glTexParameteri(GLES30.GL_TEXTURE_2D, GLES30.GL_TEXTURE_MAG_FILTER, GLES30.GL_LINEAR)
        GLES30.glTexParameteri(GLES30.GL_TEXTURE_2D, GLES30.GL_TEXTURE_WRAP_S, GLES30.GL_CLAMP_TO_EDGE)
        GLES30.glTexParameteri(GLES30.GL_TEXTURE_2D, GLES30.GL_TEXTURE_WRAP_T, GLES30.GL_CLAMP_TO_EDGE)
        android.opengl.GLUtils.texImage2D(GLES30.GL_TEXTURE_2D, 0, bitmap, 0)
        GLES30.glBindTexture(GLES30.GL_TEXTURE_2D, 0)
        bitmap.recycle()
        return textureId
    }

    /**
     * text-align対応。テキストは要素ごとに単一行のテクスチャとして矩形化されているため、
     * 「コンテンツボックス(padding内側)の幅」と「テクスチャ実測幅(entry.width)」の差分を
     * left/center/rightでどう配分するかだけで表現できる(2026-08、StyleResolver側で
     * text-alignの解決自体は先に対応済みだったが、描画側がrect.x+padding.leftに
     * 固定決め打ちしていたためcenter/right指定が画面に反映されていなかった問題への対応)。
     * contentWidthがテクスチャ幅より狭い(=はみ出す)場合はLEFT相当にフォールバックし、
     * 負のオフセットでテキストがpadding領域より左にはみ出さないようにする。
     */
    private fun resolveTextDrawX(
        rect: com.B.b.Renderer.core.LayoutRect,
        style: com.B.b.Renderer.style.ComputedStyle,
        entry: TextTextureCache.Entry,
    ): Float {
        val contentX = rect.x.toFloat() + style.padding.left
        val contentWidth = rect.width.toFloat() - style.padding.left - style.padding.right
        val extraSpace = (contentWidth - entry.width.toFloat()).coerceAtLeast(0f)
        return when (style.textAlign) {
            TextAlign.LEFT -> contentX
            TextAlign.CENTER -> contentX + extraSpace / 2f
            TextAlign.RIGHT -> contentX + extraSpace
        }
    }

    /**
     * 2回目以降のナビゲーション用。GLSurfaceView.setRenderer()はインスタンスにつき1回しか
     * 呼べないため、rendererは使い回し、参照するLayoutEngineだけをここで差し替える。
     * GLスレッド上で呼ぶこと(GLEngineView.attach()からqueueEvent経由で呼ばれる想定)。
     * 旧ページのGPUリソース(テキストアトラス・動画テクスチャ)はseq単位で紐付いており
     * 新ページでは再利用できないため、ここで解放して新規ページ側で作り直させる。
     */
    fun updateLayoutEngine(newEngine: LayoutEngine) {
        releaseResources()
        layoutEngine = newEngine
    }

    fun releaseResources() {
        textTextureCache.releaseAll()
        if (videoTextureIds.isNotEmpty()) {
            oesQuadRenderer.deleteTextures(videoTextureIds.values.toIntArray())
            videoTextureIds.clear()
        }
        if (imageTextureIds.isNotEmpty()) {
            GLES30.glDeleteTextures(imageTextureIds.size, imageTextureIds.values.toIntArray(), 0)
            imageTextureIds.clear()
        }
    }
}
