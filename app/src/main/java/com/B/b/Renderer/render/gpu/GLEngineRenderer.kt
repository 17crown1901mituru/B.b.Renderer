package com.B.b.Renderer.render.gpu

import android.content.Context
import android.opengl.GLES30
import android.opengl.GLSurfaceView
import android.opengl.Matrix
import android.text.Spannable
import android.text.SpannableStringBuilder
import android.text.style.AbsoluteSizeSpan
import android.text.style.ForegroundColorSpan
import android.text.style.UnderlineSpan
import com.B.b.Renderer.benchmark.RenderTierBenchmark
import com.B.b.Renderer.core.Element
import com.B.b.Renderer.core.ImageElement
import com.B.b.Renderer.core.ImageLoadState
import com.B.b.Renderer.core.InlineRunLayout
import com.B.b.Renderer.core.MediaElement
import com.B.b.Renderer.core.TextNode
import com.B.b.Renderer.input.resolvePaintOrder
import com.B.b.Renderer.layout.LayoutEngine
import com.B.b.Renderer.media.JsMediaElement
import com.B.b.Renderer.style.ComputedStyle
import com.B.b.Renderer.style.Display
import com.B.b.Renderer.style.TextDecoration
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
        // 2026-08、<a>タグのインラインフロー対応。以前は(Element, Entry)のペアで
        // 「描画位置はelement.computedRect+padding」という前提だったが、1つのコンテナに
        // 複数のinline run(テキストとリンクが混在した折り返し塊)が乗るようになったため、
        // 描画位置(drawX, drawY)をrun側の原点からそのまま持ち回る形に変更した
        // (LayoutEngine.InlineRunLayoutのoriginX/originYは、コンテナのpadding込みで
        // 既に確定した「1行目の描画開始位置」なので、ここでpaddingを重ねて足す必要はない)。
        val textDraws = mutableListOf<Triple<Float, Float, TextTextureCache.Entry>>()
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

            // 2026-08、<a>タグのインラインフロー対応。以前は`element.children`直下の
            // TextNodeだけを抜き出して1テクスチャにまとめていたが、これだと<a>等の
            // display:inline要素が混在する段落でリンクのテキストだけが別Elementとして
            // 分離してしまい、正しく同じ行に混ぜて描画できなかった(<a>だけ別行にズレる、
            // 文中リンクなのにコンテナ全幅を占有する等)。今はLayoutEngineが検出した
            // inlineRun単位(テキストとインライン要素が混ざった1つの折り返し塊)ごとに、
            // SpannableStringBuilderで色・下線をスパンとして埋め込み、StaticLayoutへ
            // まとめて渡すことで、実ブラウザ同様「文中リンク」を1つの折り返しとして
            // 描画できるようにしている(詳細はbuildInlineSpanned()、
            // core/Element.ktのInlineRunLayoutコメント参照)。
            element.inlineRuns.forEach { run ->
                val (spanned, contentKey) = buildInlineSpanned(run, style)
                if (spanned.isNotBlank()) {
                    val maxWidthPx = run.maxWidth.toInt().coerceAtLeast(1)
                    val entry = textTextureCache.getOrCreateSpanned(
                        seq = run.nodes.first().seq,
                        spanned = spanned,
                        contentKey = contentKey,
                        fontSizePx = style.fontSize,
                        maxWidthPx = maxWidthPx,
                        textAlign = style.textAlign,
                    )
                    if (entry != null) {
                        textDraws.add(Triple(run.originX, run.originY, entry))
                    }
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
        textDraws.groupBy { it.third.atlasPageIndex }.forEach { (pageIndex, drawsInPage) ->
            atlasQuadRenderer.beginBatch(maxQuads = drawsInPage.size)
            drawsInPage.forEach { (drawX, drawY, entry) ->
                // 2026-08、<a>タグのインラインフロー対応。drawX/drawYはInlineRunLayout.
                // originX/originY(コンテナのpadding込みで既に確定した描画開始位置)を
                // そのまま使うため、以前のようにrect.x/y+padding.left/topを重ねて
                // 足す必要はない(むしろ足すと二重にpaddingがかかってズレる)。
                atlasQuadRenderer.addQuad(
                    x = drawX,
                    y = drawY,
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
     * 1つのinline run(LayoutEngine.layoutBlockChildren()が検出した、TextNode/
     * display:inline要素が連続する折り返し塊)を、色・下線をスパンとして埋め込んだ
     * SpannableStringBuilderへ変換する(2026-08、<a>タグのインラインフロー対応)。
     *
     * 各セグメント(TextNode、またはdisplay:inline要素)の文字範囲へ、それぞれの
     * ForegroundColorSpan(色)・UnderlineSpan(text-decoration:underline)・
     * AbsoluteSizeSpan(コンテナと異なるfont-sizeの場合のみ)を個別に設定する
     * ("重ならない"よう、TextNode部分にもコンテナ自身の色を明示的にスパン化している。
     * 全体に基調色を敷いてから範囲だけ上書きする方式だと、Android側のスパン適用順序
     * (getSpans()の返す順序)に結果が依存してしまい不安定なため、全文字が必ずどれか
     * 1つのForegroundColorSpanで覆われるようにして曖昧さを無くしてある)。
     *
     * 戻り値のcontentKeyは、TextTextureCache側のキャッシュ判定に使う文字列
     * (プレーンテキストだけでなく色・下線・fontSize等スパンの内容まで含める必要がある。
     * SpannableStringBuilder自体はdata classではなくキャッシュ鍵として使えないため)。
     *
     * 既知の制約: このセグメント化はrun.nodes(DOM順そのまま)を単語区切りなしで
     * そのまま連結するため、LayoutEngine.layoutInlineRun()の単語単位greedy折り返しの
     * 結果とは折り返し位置が微妙にズレ得る(両者とも同じテキスト・同じ幅を渡しては
     * いるが、前者はStaticLayoutの内部アルゴリズム、後者はPaint.measureTextの単語単位
     * 積み上げという別アルゴリズムのため。既存のテキスト折り返し全般に共通する
     * 「ほぼ一致するが完全一致は保証しない」という制約の延長)。
     */
    private fun buildInlineSpanned(run: InlineRunLayout, containerStyle: ComputedStyle): Pair<SpannableStringBuilder, String> {
        val sb = SpannableStringBuilder()
        val keyParts = StringBuilder()
        val containerColorArgb = android.graphics.Color.argb(
            containerStyle.color.a, containerStyle.color.r, containerStyle.color.g, containerStyle.color.b,
        )

        run.nodes.forEachIndexed { index, node ->
            if (index > 0) sb.append(' ')
            when (node) {
                is TextNode -> {
                    val text = node.data.trim()
                    val start = sb.length
                    sb.append(text)
                    val end = sb.length
                    if (end > start) {
                        sb.setSpan(ForegroundColorSpan(containerColorArgb), start, end, Spannable.SPAN_EXCLUSIVE_EXCLUSIVE)
                        if (containerStyle.textDecoration == TextDecoration.UNDERLINE) {
                            sb.setSpan(UnderlineSpan(), start, end, Spannable.SPAN_EXCLUSIVE_EXCLUSIVE)
                        }
                    }
                    keyParts.append("T:").append(text).append(':').append(containerColorArgb).append('|')
                }
                is Element -> {
                    val text = node.children.filterIsInstance<TextNode>()
                        .joinToString(" ") { it.data.trim() }.trim()
                    val start = sb.length
                    sb.append(text)
                    val end = sb.length
                    val elStyle = node.computedStyle
                    val elColorArgb = android.graphics.Color.argb(
                        elStyle.color.a, elStyle.color.r, elStyle.color.g, elStyle.color.b,
                    )
                    if (end > start) {
                        sb.setSpan(ForegroundColorSpan(elColorArgb), start, end, Spannable.SPAN_EXCLUSIVE_EXCLUSIVE)
                        if (elStyle.textDecoration == TextDecoration.UNDERLINE) {
                            sb.setSpan(UnderlineSpan(), start, end, Spannable.SPAN_EXCLUSIVE_EXCLUSIVE)
                        }
                        if (elStyle.fontSize != containerStyle.fontSize) {
                            sb.setSpan(AbsoluteSizeSpan(elStyle.fontSize.toInt()), start, end, Spannable.SPAN_EXCLUSIVE_EXCLUSIVE)
                        }
                    }
                    keyParts.append("E:").append(text).append(':').append(elColorArgb).append(':')
                        .append(elStyle.textDecoration).append(':').append(elStyle.fontSize).append('|')
                }
                else -> {}
            }
        }
        return sb to keyParts.toString()
    }

    /**
     * text-align対応。2026-08、StaticLayoutによるラスタライズ側(TextTextureCache)へ
     * 責務を移したため、ここにあった以前の実装(entry幅とcontentWidthの差分を
     * left/center/rightで配分するだけの、複数行を考慮しない簡易版)は削除した。
     * 複数行に折り返した段落は行ごとに幅が異なるため、単一のオフセット計算では
     * 正しく揃わない(1行目だけ中央、2行目以降はズレる、といったことが起きる)。
     * StaticLayoutなら行ごとに正しく計算されたBitmapがそのまま出てくるので、
     * 描画側は常にコンテンツボックス左端に置くだけでよくなった。
     */

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
