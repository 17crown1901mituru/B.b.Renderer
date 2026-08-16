package com.B.b.Renderer.layout

import com.B.b.Renderer.core.DirtyLevel
import com.B.b.Renderer.core.Element
import com.B.b.Renderer.core.ImageElement
import com.B.b.Renderer.core.ImageLoadState
import com.B.b.Renderer.core.LayoutRect
import com.B.b.Renderer.core.Node
import com.B.b.Renderer.core.StackingContext
import com.B.b.Renderer.core.TextNode
import com.B.b.Renderer.style.CssValue
import com.B.b.Renderer.style.ComputedStyle
import com.B.b.Renderer.style.Display
import com.B.b.Renderer.style.Position

class LayoutEngine(
    val root: Element,
    private val viewportWidth: Float,
    val viewportHeight: Float,
    // 2026-08、<img>のネットワーク取得・デコードのトリガー用。LayoutEngine自身はネットワークに
    // 触れない(既存方針通りEngineActivity側がOkHttpClientを持つ)ため、「まだ取得していない
    // ImageElementに遭遇した」という事実だけをここから呼び出し元へ伝える形にしてある。
    // 未指定(null)ならimg要素は自然サイズ0の空ボックスのまま(取得トリガーが無いのでずっとPENDING)。
    private val onImageNeeded: ((ImageElement) -> Unit)? = null,
) {
    var currentPath: String = ""
    private var layoutPassScheduled = false
    private var onFrameRequested: (() -> Unit)? = null

    /** ページ全体の高さ(直近のlayoutパス結果)。ビューポートより長い分がスクロール可能域になる */
    var contentHeight: Float = 0f
        private set

    /** 現在の縦スクロール位置(0 = 先頭)。描画・ヒットテスト双方でこの値を差し引く/加算する */
    var scrollY: Float = 0f
        private set

    fun scrollBy(deltaY: Float) {
        scrollY += deltaY
        clampScroll()
    }

    private fun clampScroll() {
        val maxScroll = (contentHeight - viewportHeight).coerceAtLeast(0f)
        scrollY = scrollY.coerceIn(0f, maxScroll)
    }

    /**
     * ページズーム倍率(1.0が等倍)。ピンチ操作(ZoomGestureHelper)と、ドロワーの
     * +/-/リセットボタン(EngineActivity側)の両方から更新される。
     * レイアウト座標系そのものは変えず、描画時(canvas.scale/GLの正射影)と
     * ヒットテスト時(タッチ座標をこの値で割ってから渡す)にだけ効かせる方式なので、
     * ズーム変更でレイアウトのやり直し(scheduleLayoutPass)は不要。
     */
    var zoomScale: Float = 1.0f
        private set

    fun setZoom(scale: Float) {
        zoomScale = scale.coerceIn(MIN_ZOOM, MAX_ZOOM)
    }

    fun resetZoom() {
        zoomScale = 1.0f
    }

    companion object {
        const val MIN_ZOOM = 0.5f
        const val MAX_ZOOM = 3.0f
    }

    /** Choreographer等、フレーム同期の仕組みを外部から注入する */
    fun setFrameScheduler(callback: (() -> Unit) -> Unit) {
        frameSchedulerImpl = callback
    }

    private var frameSchedulerImpl: ((() -> Unit) -> Unit)? = null

    fun scheduleLayoutPass() {
        if (layoutPassScheduled) return
        layoutPassScheduled = true
        val scheduler = frameSchedulerImpl
        if (scheduler != null) {
            scheduler {
                layoutPassScheduled = false
                runLayoutPass()
            }
        } else {
            // スケジューラ未設定時は即時実行(テスト・オフスクリーン用途向け)
            layoutPassScheduled = false
            runLayoutPass()
        }
    }

    fun runLayoutPass() {
        contentHeight = layoutBlock(root, availableWidth = viewportWidth, originX = 0f, originY = 0f)
        clampScroll() // レイアウトのやり直しで内容が短くなった場合、はみ出したscrollYを戻す
    }

    // ---- Box model計算 ----

    fun layoutBlock(element: Element, availableWidth: Float, originX: Float, originY: Float): Float {
        if (element.dirty == DirtyLevel.CLEAN) {
            return (element.computedRect.y + element.computedRect.height).toFloat()
        }

        if (element.dirty == DirtyLevel.STYLE) {
            // 座標は据え置き、見た目だけ変わった扱い。呼び出し元がdrawCommandを再生成する。
            element.dirty = DirtyLevel.CLEAN
            return (element.computedRect.y + element.computedRect.height).toFloat()
        }

        val style = element.computedStyle
        val outerWidth = resolveWidth(style.width, availableWidth)
        val (marginLeft, _) = resolveHorizontalMargins(style, outerWidth, availableWidth)
        val width = outerWidth - style.padding.left - style.padding.right
        var cursorY = originY + style.margin.top + style.padding.top
        val contentX = originX + marginLeft + style.padding.left

        element.children.forEach { child ->
            when (child) {
                is TextNode -> {
                    cursorY = layoutInlineText(child, width, contentX, cursorY, style)
                }
                is Element -> {
                    if (child.computedStyle.display == Display.NONE) return@forEach

                    if (child.computedStyle.position == Position.ABSOLUTE) {
                        layoutAbsolute(child, availableWidth, viewportHeight)
                        return@forEach
                    }

                    // imgは子要素を持たない(=layoutBlockの再帰対象にならない)ため、
                    // 通常のElementとは別に専用のサイズ解決(layoutImage)へ振り分ける。
                    if (child is ImageElement) {
                        val childBottom = layoutImage(child, width, contentX, cursorY)
                        cursorY = childBottom + child.computedStyle.margin.bottom
                        return@forEach
                    }

                    val childBottom = layoutBlock(child, width, contentX, cursorY)
                    cursorY = childBottom + child.computedStyle.margin.bottom
                }
            }
        }

        val contentHeight = cursorY - originY - style.margin.top - style.padding.top
        val totalHeight = resolveHeight(style.height, contentHeight) + style.padding.top + style.padding.bottom

        element.computedRect = LayoutRect(
            x = (originX + marginLeft).toInt(),
            y = (originY + style.margin.top).toInt(),
            width = (width + style.padding.left + style.padding.right).toInt(),
            height = totalHeight.toInt(),
        )

        element.dirty = DirtyLevel.CLEAN
        // 2026-08訂正: 以前はここが「originY + totalHeight」(margin.topを含めない)に
        // なっていた。これは過去にmargin.top/bottomを親側・子側の双方で加算してしまう
        // 二重カウントバグを踏んだ際、対症療法としてここからmargin.topを丸ごと落として
        // 帳尻を合わせていたものだった。だが実際には「子要素は自分のmargin.top分
        // 下にずれて配置される(cursorY/contentXには織り込み済み)のに、自分自身の
        // computedRect.y・親へ返す高さにはその分が反映されない」という逆方向の
        // 不整合(親から見るとこの要素の下端を過小評価し、次の兄弟が本来より
        // margin.top分だけ上に食い込む位置に配置されてしまう)を生んでいた。
        // 親側(呼び出し元)は元々margin.topを一切加算していない(cursorYをそのまま
        // originYとして渡すだけ)ので、ここでmargin.topを1回だけ足す今の形が
        // 二重カウントにはならない。img専用のlayoutImage()は最初から
        // このtopを含む形で実装されていた(結果的にそちらが正しい実装だった)ため、
        // 今回はlayoutBlock()側をlayoutImage()の流儀に合わせて修正している。
        return originY + style.margin.top + totalHeight
    }

    /**
     * 水平方向のmargin(left/right)を解決する。margin:auto centering対応(2026-08)。
     * 両方autoなら余白を左右均等に配分して中央寄せ、片方だけautoならそちら側に
     * 余白を全て寄せる(CSS仕様通り)。width:autoの場合はouterWidth==availableWidthと
     * なりleftoverが0になるため、auto指定の有無に関わらず結果に影響しない
     * (「width:autoの時はauto marginが実質何もしない」というCSS仕様の挙動と自然に一致する)。
     */
    private fun resolveHorizontalMargins(style: ComputedStyle, outerWidth: Float, availableWidth: Float): Pair<Float, Float> {
        val leftover = (availableWidth - outerWidth).coerceAtLeast(0f)
        return when {
            style.marginLeftAuto && style.marginRightAuto -> (leftover / 2f) to (leftover / 2f)
            style.marginLeftAuto -> leftover to style.margin.right
            style.marginRightAuto -> style.margin.left to leftover
            else -> style.margin.left to style.margin.right
        }
    }

    /**
     * <img>専用のレイアウト。layoutBlock()と同じdirtyチェック・box model(margin)の
     * 考え方を踏襲しつつ、子要素を持たない(=cursorYを子要素分進める必要が無い)ぶん
     * 単純にboxサイズを解決するだけで済む。
     *
     * PENDING状態の画像を見つけたら、その場でLOADINGへ遷移させてonImageNeededを
     * 一度だけ呼ぶ(取得トリガー)。実際のfetch/decodeは呼び出し元(EngineActivity)が
     * 非同期に行い、完了時にelement.markDirty(LAYOUT)+scheduleLayoutPass()される
     * ことで、このlayoutImage()が改めて呼ばれ、今度は実サイズで確定する想定。
     */
    private fun layoutImage(element: ImageElement, availableWidth: Float, originX: Float, originY: Float): Float {
        if (element.dirty == DirtyLevel.CLEAN) {
            return (element.computedRect.y + element.computedRect.height).toFloat()
        }

        if (element.dirty == DirtyLevel.STYLE) {
            element.dirty = DirtyLevel.CLEAN
            return (element.computedRect.y + element.computedRect.height).toFloat()
        }

        if (element.loadState == ImageLoadState.PENDING) {
            element.loadState = ImageLoadState.LOADING
            onImageNeeded?.invoke(element)
        }

        val style = element.computedStyle
        val (boxWidth, boxHeight) = resolveImageBoxSize(element, style, availableWidth)
        val (marginLeft, _) = resolveHorizontalMargins(style, boxWidth, availableWidth)
        val originXWithMargin = originX + marginLeft
        val originYWithMargin = originY + style.margin.top

        element.computedRect = LayoutRect(
            x = originXWithMargin.toInt(),
            y = originYWithMargin.toInt(),
            width = boxWidth.toInt(),
            height = boxHeight.toInt(),
        )
        element.dirty = DirtyLevel.CLEAN
        return originYWithMargin + boxHeight
    }

    /**
     * 画像のボックスサイズ解決。
     *   width/height両方明示: そのまま使う(heightはpx指定のみ対応。%は基準となる
     *     親の確定済み高さが無い簡易実装のため、resolveHeight()の他要素向け実装と
     *     同様に今回は非対応としている)。
     *   片方だけ明示: 自然サイズのアスペクト比を保ってもう片方を算出する
     *     (自然サイズ未取得の間は算出できないので0のまま)。
     *   両方auto: 自然サイズをそのまま使うが、availableWidthより広い場合は
     *     アスペクト比を保って縮小する(はみ出し防止)。
     *   自然サイズが未取得(ロード中/失敗): 幅availableWidth・高さ0の仮ボックスとして
     *     扱う。取得完了時にlayoutImage()がmarkDirty(LAYOUT)経由で再び呼ばれ、
     *     そこで実サイズに更新される。
     */
    private fun resolveImageBoxSize(element: ImageElement, style: ComputedStyle, availableWidth: Float): Pair<Float, Float> {
        val hasNatural = element.naturalWidth > 0 && element.naturalHeight > 0
        val naturalWidth = element.naturalWidth.toFloat()
        val naturalHeight = element.naturalHeight.toFloat()
        val aspect = if (hasNatural) naturalHeight / naturalWidth else 0f

        val resolvedWidth = if (style.width != CssValue.Auto) resolveWidth(style.width, availableWidth) else null
        val resolvedHeight = (style.height as? CssValue.Px)?.value

        return when {
            resolvedWidth != null && resolvedHeight != null -> resolvedWidth to resolvedHeight
            resolvedWidth != null -> resolvedWidth to (if (hasNatural) resolvedWidth * aspect else 0f)
            resolvedHeight != null -> (if (hasNatural && aspect > 0f) resolvedHeight / aspect else 0f) to resolvedHeight
            hasNatural -> {
                if (naturalWidth <= availableWidth) {
                    naturalWidth to naturalHeight
                } else {
                    availableWidth to (availableWidth * aspect)
                }
            }
            else -> availableWidth to 0f
        }
    }

    private fun layoutInlineText(
        node: TextNode,
        maxWidth: Float,
        originX: Float,
        originY: Float,
        style: ComputedStyle,
    ): Float {
        val words = node.data.trim().split(Regex("\\s+"))
        if (words.isEmpty() || words == listOf("")) return originY

        val lineHeight = style.fontSize * 1.4f
        var cursorX = originX
        var cursorY = originY
        // 簡易近似。正確なグリフ幅は描画層のフォントメトリクス測定に委ねる。
        val avgCharWidth = style.fontSize * 0.55f

        words.forEach { word ->
            val wordWidth = word.length * avgCharWidth
            if (cursorX + wordWidth > originX + maxWidth && cursorX > originX) {
                cursorX = originX
                cursorY += lineHeight
            }
            cursorX += wordWidth + avgCharWidth
        }

        return cursorY + lineHeight
    }

    private fun layoutAbsolute(element: Element, containerWidth: Float, containerHeight: Float) {
        val style = element.computedStyle
        val x = (style.width as? CssValue.Px)?.value ?: 0f
        val y = (style.height as? CssValue.Px)?.value ?: 0f

        layoutBlock(element, availableWidth = containerWidth, originX = x, originY = y)
        element.stackingContext = element.stackingContext ?: StackingContext(isolatesChildren = true)
    }

    private fun resolveWidth(value: CssValue, available: Float): Float = when (value) {
        is CssValue.Px -> value.value
        is CssValue.Percent -> available * (value.value / 100f)
        CssValue.Auto -> available
    }

    private fun resolveHeight(value: CssValue, contentHeight: Float): Float = when (value) {
        is CssValue.Px -> value.value
        is CssValue.Percent -> contentHeight // ルート基準%は簡易実装では未対応
        CssValue.Auto -> contentHeight
    }

    // ---- DOM操作API(HtmxRenderEngineから利用) ----

    fun replaceNode(old: Element, replacement: Element) {
        val parent = old.parent ?: return
        val index = parent.children.indexOf(old)
        if (index == -1) return
        replacement.parent = parent
        parent.children[index] = replacement
        parent.markDirty(DirtyLevel.SUBTREE)
        scheduleLayoutPass()
    }

    fun appendChildren(target: Element, newChildren: List<Node>) {
        newChildren.forEach {
            it.parent = target
            target.children.add(it)
        }
        target.markDirty(DirtyLevel.SUBTREE)
        scheduleLayoutPass()
    }

    fun replaceChildren(target: Element, newChildren: List<Node>) {
        target.replaceChildren(newChildren)
        scheduleLayoutPass()
    }

    fun querySelector(selector: String): Element? = root.querySelector(selector)
}
