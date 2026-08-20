package com.B.b.Renderer.layout

import com.B.b.Renderer.core.DirtyLevel
import com.B.b.Renderer.core.Element
import com.B.b.Renderer.core.ImageElement
import com.B.b.Renderer.core.ImageLoadState
import com.B.b.Renderer.core.InlineRunLayout
import com.B.b.Renderer.core.LayoutRect
import com.B.b.Renderer.core.Node
import com.B.b.Renderer.core.StackingContext
import com.B.b.Renderer.core.TextNode
import com.B.b.Renderer.style.AlignItems
import com.B.b.Renderer.style.CssValue
import com.B.b.Renderer.style.ComputedStyle
import com.B.b.Renderer.style.Display
import com.B.b.Renderer.style.FlexDirection
import com.B.b.Renderer.style.JustifyContent
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
        val cursorYStart = originY + style.margin.top + style.padding.top
        val contentX = originX + marginLeft + style.padding.left

        // 2026-08、display:flex対応。box model(margin/padding/width)の解決はブロックと
        // 共通のまま、子要素の配置アルゴリズムだけをlayoutFlexChildren()へ切り替える。
        val cursorYEnd = if (style.display == Display.FLEX) {
            element.inlineRuns = emptyList() // flexコンテナ自身の直下にinline runは無い(仕様上の簡易割り切り、コメント参照)
            layoutFlexChildren(element, width, contentX, cursorYStart, style)
        } else {
            layoutBlockChildren(element, availableWidth, width, contentX, cursorYStart, style)
        }

        val contentHeight = cursorYEnd - originY - style.margin.top - style.padding.top
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
     * 通常のブロックフロー(display:flex以外)における子要素の配置。2026-08、
     * <a>タグのタップ遷移対応にあわせてリファクタ。以前はTextNodeが出現するたびに
     * 個別に折り返し計算していたため、`<p>text <a>link</a> more</p>`のような
     * テキストとインライン要素(display:inline)が混在する内容だと、<a>だけが
     * 独立したブロック要素として別の行に分離してしまい、実ブラウザの「文中リンク」の
     * 見た目にならなかった(タップ判定域も<a>のテキスト幅ではなくコンテナの全幅に
     * なってしまっていた)。
     *
     * 今はTextNode・display:inline要素が連続する区間を「1つの折り返し塊(inline run)」
     * としてまとめて検出し(pendingInline)、ブロック要素・img・display:noneに遭遇したら
     * そこで塊を確定させる(flushInlineRun)方式にした。実際の折り返し計算・
     * hitTest用の行単位矩形の算出はlayoutInlineRun()が行う。
     */
    private fun layoutBlockChildren(
        element: Element,
        availableWidth: Float,
        width: Float,
        contentX: Float,
        cursorYStart: Float,
        style: ComputedStyle,
    ): Float {
        var cursorY = cursorYStart
        val inlineRuns = mutableListOf<InlineRunLayout>()
        var pendingInline = mutableListOf<Node>()

        fun flushInlineRun() {
            if (pendingInline.isEmpty()) return
            val run = InlineRunLayout(originX = contentX, originY = cursorY, maxWidth = width, nodes = pendingInline.toList())
            inlineRuns.add(run)
            cursorY = layoutInlineRun(run, style)
            pendingInline = mutableListOf()
        }

        element.children.forEach { child ->
            when (child) {
                is TextNode -> pendingInline.add(child)
                is Element -> {
                    if (child.computedStyle.display == Display.NONE) return@forEach

                    if (child.computedStyle.position == Position.ABSOLUTE) {
                        // 絶対配置は通常のフローから完全に外れるため、進行中のinline runを
                        // 中断させない(実ブラウザでも同様: out-of-flow要素はインライン
                        // フローの一部にならない)。widthの基準は従来通りavailableWidth
                        // (このelement自身のpadding控除前、親から見た利用可能幅)のまま。
                        layoutAbsolute(child, availableWidth, viewportHeight)
                        return@forEach
                    }

                    // display:inlineなElement(<a>等)はTextNode同様、単語単位で
                    // 折り返しに参加させる。ただしimgは自然幅を持つ置換要素で、
                    // テキストと同じ折り返しアルゴリズムに乗せられないため対象外
                    // (img自体をインライン化する対応は今回のスコープ外。
                    // core/Element.ktのInlineRunLayoutコメント参照)。
                    if (child.computedStyle.display == Display.INLINE && child !is ImageElement) {
                        pendingInline.add(child)
                        return@forEach
                    }

                    flushInlineRun()

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
        flushInlineRun()

        element.inlineRuns = inlineRuns
        return cursorY
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

    /**
     * インラインフロー(TextNode + display:inline要素が混在した折り返し塊)の
     * 折り返し高さ見積もりと、各インライン要素(<a>等)の当たり判定用矩形を算出する。
     * 2026-08、<a>タグのタップ遷移対応にあわせてlayoutInlineText()から改名・拡張。
     *
     * 見積もりアルゴリズム自体は旧layoutInlineText()と同じ「単語単位のgreedy折り返し
     * (Paint.measureTextによる実測)」で、実際の描画側(GLEngineRenderer→
     * TextTextureCache、こちらはandroid.text.StaticLayoutで折り返す)とはほぼ一致する
     * はずだが完全一致は保証しない、という既存の制約をそのまま引き継いでいる。
     *
     * 単語ごとに「どのNode由来か」を記録しておき、display:inline要素(sourceElement)の
     * 単語が乗った行ごとの矩形をinlineFragments(ヒットテスト用、InputHandling.kt参照)、
     * その外接矩形をcomputedRect(背景色描画等の従来用途向け、複数行にまたがる場合は
     * 厳密ではない旨コメント参照)として、その要素自身に書き戻す。
     *
     * 既知の制約:
     *   - この塊を構成するdisplay:inline要素は「自身の直接の子TextNodeのテキスト」
     *     のみを対象とする(`<a><b>text</b></a>`のような入れ子のインライン要素は
     *     今回非対応。<b>側のテキストは無視される)。
     *   - 行の高さ(lineHeight)はコンテナ自身のfontSizeを基準に一律で決める
     *     (インライン要素ごとに異なるfont-sizeがあっても、行の高さ自体には反映しない)。
     */
    private fun layoutInlineRun(run: InlineRunLayout, containerStyle: ComputedStyle): Float {
        data class Word(val text: String, val sourceElement: Element?)

        val words = mutableListOf<Word>()
        run.nodes.forEach { node ->
            when (node) {
                is TextNode -> {
                    node.data.trim().split(Regex("\\s+")).filter { it.isNotBlank() }.forEach { w ->
                        words.add(Word(w, null))
                    }
                }
                is Element -> {
                    val text = node.children.filterIsInstance<TextNode>()
                        .joinToString(" ") { it.data.trim() }.trim()
                    text.split(Regex("\\s+")).filter { it.isNotBlank() }.forEach { w ->
                        words.add(Word(w, node))
                    }
                }
                else -> {}
            }
        }

        // 単語を持たなかった(空白のみ/空)インライン要素も、無限にdirtyのまま
        // 残らないようここでCLEANへ落としておく(下の本処理では単語が無ければ
        // fragmentsByElementに現れず、書き戻しの機会が無いため)。
        if (words.isEmpty()) {
            run.nodes.filterIsInstance<Element>().forEach { it.dirty = DirtyLevel.CLEAN }
            return run.originY
        }

        val paint = android.graphics.Paint().apply { textSize = containerStyle.fontSize }
        val spaceWidth = paint.measureText(" ")
        val fontMetrics = paint.fontMetrics
        val lineHeight = fontMetrics.bottom - fontMetrics.top

        var cursorX = run.originX
        var cursorY = run.originY

        // 要素ごとの行単位フラグメント。同一要素・同一行の単語が連続する限り、
        // 直前のフラグメントのx2を伸ばすだけにして1行=1矩形にまとめる(単語ごとに
        // 矩形を作ると、当たり判定は正しくてもGLEngineRenderer側の想定(1インライン
        // 要素あたり複数行分のfragmentがあればよい)より粒度が細かくなりすぎるため)。
        val fragmentsByElement = mutableMapOf<Element, MutableList<FloatArray>>() // [x1,y1,x2,y2]
        var lastElement: Element? = null

        words.forEach { word ->
            val wordWidth = paint.measureText(word.text)
            if (cursorX + wordWidth > run.originX + run.maxWidth && cursorX > run.originX) {
                cursorX = run.originX
                cursorY += lineHeight
                lastElement = null // 改行をまたいで前のフラグメントを伸ばさないようにする
            }
            val x1 = cursorX
            val x2 = cursorX + wordWidth
            val y1 = cursorY
            val y2 = cursorY + lineHeight

            if (word.sourceElement != null) {
                val frags = fragmentsByElement.getOrPut(word.sourceElement) { mutableListOf() }
                val last = frags.lastOrNull()
                if (last != null && lastElement == word.sourceElement && last[1] == y1) {
                    last[2] = x2 // 同一要素・同一行の続き → 既存フラグメントを伸ばす
                } else {
                    frags.add(floatArrayOf(x1, y1, x2, y2))
                }
            }
            lastElement = word.sourceElement
            cursorX += wordWidth + spaceWidth
        }

        fragmentsByElement.forEach { (el, frags) ->
            val rects = frags.map { f ->
                LayoutRect(f[0].toInt(), f[1].toInt(), (f[2] - f[0]).toInt(), (f[3] - f[1]).toInt())
            }
            el.inlineFragments = rects
            el.computedRect = LayoutRect(
                x = rects.minOf { it.x },
                y = rects.minOf { it.y },
                width = rects.maxOf { it.x + it.width } - rects.minOf { it.x },
                height = rects.maxOf { it.y + it.height } - rects.minOf { it.y },
            )
            el.dirty = DirtyLevel.CLEAN
        }
        run.nodes.filterIsInstance<Element>().filterNot { fragmentsByElement.containsKey(it) }
            .forEach { it.dirty = DirtyLevel.CLEAN }

        return cursorY + lineHeight
    }

    /**
     * display:flexコンテナの子要素配置(2026-08対応)。flex-directionに応じて
     * layoutFlexRow()/layoutFlexColumn()へ振り分けるだけの薄いディスパッチャ。
     * 各関数のスコープ限定事項はそちらのコメントを参照。
     */
    private fun layoutFlexChildren(element: Element, width: Float, originX: Float, originY: Float, style: ComputedStyle): Float =
        if (style.flexDirection == FlexDirection.ROW) {
            layoutFlexRow(element, width, originX, originY, style)
        } else {
            layoutFlexColumn(element, width, originX, originY, style)
        }

    /**
     * flexコンテナの直接の子から、実際にflexアイテムとして並べる対象を集める。
     * TextNodeは対象外(実ブラウザは匿名flexアイテムを生成するが、この簡易実装では
     * 「flexコンテナ直下にテキストを直接置く」ケースは非対応とする——実用上はflex
     * アイテムは常に要素として書かれることがほとんどのため、割り切りとして許容する)。
     * display:noneはスキップ、position:absoluteは通常のflexフローから外し、
     * 従来通りlayoutAbsolute()で個別配置する(副作用としてここで呼んでしまう)。
     */
    private fun collectFlexItems(element: Element, containerWidth: Float): List<Element> {
        val items = mutableListOf<Element>()
        element.children.forEach { child ->
            if (child !is Element) return@forEach
            if (child.computedStyle.display == Display.NONE) return@forEach
            if (child.computedStyle.position == Position.ABSOLUTE) {
                layoutAbsolute(child, containerWidth, viewportHeight)
                return@forEach
            }
            items.add(child)
        }
        return items
    }

    /**
     * flexアイテムの基準サイズ(hypothetical main size)を決定する。
     *   1. flex-basisが明示(px/%)されていればそれを使う
     *   2. 未指定(auto)なら、主軸方向に対応するCSSプロパティ(rowならwidth、
     *      columnならheight)がpx/%指定されていればそれを使う
     *   3. それも無ければ0(コンテンツ量に応じた自動サイズ(shrink-to-fit)は今回非対応。
     *      `flex: 1`のようにgrow指定と組み合わせて使う前提の簡易実装)
     */
    private fun resolveFlexBasis(item: Element, mainAxisSize: Float, isRow: Boolean): Float {
        val itemStyle = item.computedStyle
        val basis = itemStyle.flexBasis
        if (basis is CssValue.Px) return basis.value
        if (basis is CssValue.Percent) return mainAxisSize * (basis.value / 100f)

        val sizeProp = if (isRow) itemStyle.width else itemStyle.height
        return when (sizeProp) {
            is CssValue.Px -> sizeProp.value
            is CssValue.Percent -> mainAxisSize * (sizeProp.value / 100f)
            CssValue.Auto -> 0f
        }
    }

    /**
     * 空きスペース(または不足)をflex-grow/flex-shrinkに応じて配分する。
     * 空き(freeSpace>0): grow比率で按分して基準サイズに加算する(sum(grow)==0なら誰も伸びない)。
     * 不足(freeSpace<0): CSS仕様通り「shrink係数×基準サイズ」を重みとして按分し、
     *   基準サイズから減算する(0未満にはならないようcoerceAtLeastする)。
     */
    private fun distributeGrowShrink(items: List<Element>, basisSizes: FloatArray, mainAxisSize: Float, gapTotal: Float): FloatArray {
        val freeSpace = mainAxisSize - (basisSizes.sum() + gapTotal)
        val result = FloatArray(items.size)
        when {
            freeSpace > 0f -> {
                val totalGrow = items.sumOf { it.computedStyle.flexGrow.toDouble() }.toFloat()
                items.forEachIndexed { i, item ->
                    result[i] = basisSizes[i] + if (totalGrow > 0f) freeSpace * (item.computedStyle.flexGrow / totalGrow) else 0f
                }
            }
            freeSpace < 0f -> {
                val totalShrinkWeight = items.indices.sumOf { i ->
                    (items[i].computedStyle.flexShrink * basisSizes[i]).toDouble()
                }.toFloat()
                items.forEachIndexed { i, item ->
                    val weight = item.computedStyle.flexShrink * basisSizes[i]
                    val reduction = if (totalShrinkWeight > 0f) (-freeSpace) * (weight / totalShrinkWeight) else 0f
                    result[i] = (basisSizes[i] - reduction).coerceAtLeast(0f)
                }
            }
            else -> basisSizes.copyInto(result)
        }
        return result
    }

    /** justify-contentに応じた「先頭アイテムの開始オフセット」と「アイテム間隔」を返す */
    private fun justifyMainAxis(justify: JustifyContent, itemCount: Int, mainAxisSize: Float, usedMainSize: Float, gap: Float): Pair<Float, Float> {
        val leftover = (mainAxisSize - usedMainSize).coerceAtLeast(0f)
        return when (justify) {
            JustifyContent.FLEX_START -> 0f to gap
            JustifyContent.FLEX_END -> leftover to gap
            JustifyContent.CENTER -> (leftover / 2f) to gap
            JustifyContent.SPACE_BETWEEN -> 0f to (if (itemCount > 1) gap + leftover / (itemCount - 1) else gap)
            JustifyContent.SPACE_AROUND -> {
                val spacing = gap + if (itemCount > 0) leftover / itemCount else 0f
                (spacing / 2f) to spacing
            }
        }
    }

    /**
     * flex-direction: row(既定)のアイテム配置。
     *
     * 交差軸(縦方向)のサイズは2パスで決める:
     *   1パス目: 各アイテムを「自身の主軸サイズ(finalMainSizes)・原点(0,0)」で
     *     一度layoutBlock()し、自然な高さ(naturalHeights)を計測する。
     *   2パス目: align-items:stretch(既定)かつheight:autoなアイテムには、
     *     1パス目で求めた行の交差軸サイズ(lineCrossSize = 全アイテムの自然高さの最大値)を
     *     採用し、それ以外は自然な高さのまま、最終的な位置(originX+mainCursor,
     *     originY+crossOffset)で改めてlayoutBlock()する。stretch分の高さは
     *     layoutBlock()自体には伝えず(=子孫のheight:100%等への伝播は非対応、
     *     クラス冒頭コメント参照)、computedRect.heightだけを直接上書きする。
     *
     * 2パス目の直前でmarkDirty(LAYOUT)しているのは、1パス目のlayoutBlock()呼び出しで
     * 各アイテムのdirtyがCLEANになってしまい、そのままでは2パス目がdirtyチェックの
     * 早期returnに引っかかって座標が更新されないため。
     */
    private fun layoutFlexRow(element: Element, mainAxisSize: Float, originX: Float, originY: Float, style: ComputedStyle): Float {
        val items = collectFlexItems(element, mainAxisSize)
        if (items.isEmpty()) return originY

        val gap = style.gap
        val gapTotal = gap * (items.size - 1).coerceAtLeast(0)
        val basisSizes = FloatArray(items.size) { i -> resolveFlexBasis(items[i], mainAxisSize, isRow = true) }
        val mainSizes = distributeGrowShrink(items, basisSizes, mainAxisSize, gapTotal)

        val naturalHeights = FloatArray(items.size)
        items.forEachIndexed { i, item ->
            item.markDirty(DirtyLevel.LAYOUT)
            layoutBlock(item, mainSizes[i], 0f, 0f)
            naturalHeights[i] = item.computedRect.height.toFloat()
        }
        val lineCrossSize = naturalHeights.maxOrNull() ?: 0f

        val usedMainSize = mainSizes.sum() + gapTotal
        val (startOffset, spacing) = justifyMainAxis(style.justifyContent, items.size, mainAxisSize, usedMainSize, gap)

        var mainCursor = startOffset
        items.forEachIndexed { i, item ->
            if (i > 0) mainCursor += spacing
            val stretched = style.alignItems == AlignItems.STRETCH && item.computedStyle.height == CssValue.Auto
            val itemHeight = if (stretched) lineCrossSize else naturalHeights[i]
            val crossOffset = when (style.alignItems) {
                AlignItems.STRETCH, AlignItems.FLEX_START -> 0f
                AlignItems.FLEX_END -> lineCrossSize - itemHeight
                AlignItems.CENTER -> (lineCrossSize - itemHeight) / 2f
            }
            item.markDirty(DirtyLevel.LAYOUT)
            layoutBlock(item, mainSizes[i], originX + mainCursor, originY + crossOffset)
            if (stretched) {
                item.computedRect = item.computedRect.copy(height = itemHeight.toInt())
            }
            mainCursor += mainSizes[i]
        }
        return originY + lineCrossSize
    }

    /**
     * flex-direction: columnのアイテム配置。
     *
     * 交差軸(横方向=width)は、この関数のavailable width自体が常に確定値なので
     * (=このエンジンのブロック幅解決は常にwidth:autoを「利用可能幅いっぱい」として
     * 確定させるため)、row方向のような2パス計測は不要——align-itemsに応じた
     * itemWidthをその場で決め、1回のlayoutBlock()で最終配置できる。
     *
     * 主軸(縦方向=height)は、コンテナ自身に明示的なheight(px)指定が無い限り
     * 「不定サイズ」として扱い、flex-grow/flex-shrink・justify-contentは適用しない
     * (基準サイズのまま単純に積み上げるだけ)。これは手抜きではなく実際のCSS仕様に
     * 沿った挙動——auto heightなコンテナの主軸サイズはコンテンツ自身が決めるものであり、
     * 「余白を分配する」という概念自体が循環参照になって成立しないため。
     */
    private fun layoutFlexColumn(element: Element, width: Float, originX: Float, originY: Float, style: ComputedStyle): Float {
        val items = collectFlexItems(element, width)
        if (items.isEmpty()) return originY

        val gap = style.gap
        val gapTotal = gap * (items.size - 1).coerceAtLeast(0)
        val explicitMainAxisSize = (style.height as? CssValue.Px)?.value

        val basisSizes = FloatArray(items.size) { i -> resolveFlexBasis(items[i], explicitMainAxisSize ?: 0f, isRow = false) }
        val mainSizes = if (explicitMainAxisSize != null) {
            distributeGrowShrink(items, basisSizes, explicitMainAxisSize, gapTotal)
        } else {
            basisSizes
        }

        val usedMainSize = mainSizes.sum() + gapTotal
        val (startOffset, spacing) = if (explicitMainAxisSize != null) {
            justifyMainAxis(style.justifyContent, items.size, explicitMainAxisSize, usedMainSize, gap)
        } else {
            0f to gap
        }

        var mainCursor = startOffset
        items.forEachIndexed { i, item ->
            if (i > 0) mainCursor += spacing
            val itemStyleWidth = item.computedStyle.width
            val naturalWidth = when (itemStyleWidth) {
                is CssValue.Px -> itemStyleWidth.value
                is CssValue.Percent -> width * (itemStyleWidth.value / 100f)
                CssValue.Auto -> width
            }
            val itemWidth = if (style.alignItems == AlignItems.STRETCH && itemStyleWidth == CssValue.Auto) width else naturalWidth
            val crossOffset = when (style.alignItems) {
                AlignItems.STRETCH, AlignItems.FLEX_START -> 0f
                AlignItems.FLEX_END -> width - itemWidth
                AlignItems.CENTER -> (width - itemWidth) / 2f
            }
            item.markDirty(DirtyLevel.LAYOUT)
            layoutBlock(item, itemWidth, originX + crossOffset, originY + mainCursor)
            mainCursor += mainSizes[i]
        }
        return originY + mainCursor
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
