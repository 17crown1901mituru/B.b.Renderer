package com.B.b.Renderer.layout

import com.B.b.Renderer.core.DirtyLevel
import com.B.b.Renderer.core.Element
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
        val width = resolveWidth(style.width, availableWidth) - style.padding.left - style.padding.right
        var cursorY = originY + style.margin.top + style.padding.top
        val contentX = originX + style.margin.left + style.padding.left

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

                    val childBottom = layoutBlock(child, width, contentX, cursorY)
                    cursorY = childBottom + child.computedStyle.margin.bottom
                }
            }
        }

        val contentHeight = cursorY - originY - style.margin.top - style.padding.top
        val totalHeight = resolveHeight(style.height, contentHeight) + style.padding.top + style.padding.bottom

        element.computedRect = LayoutRect(
            x = originX.toInt(),
            y = originY.toInt(),
            width = (width + style.padding.left + style.padding.right).toInt(),
            height = totalHeight.toInt(),
        )

        element.dirty = DirtyLevel.CLEAN
        // 注意: totalHeightの算出には既にstyle.margin.topがcursorYの初期値経由で
        // 織り込まれている。margin.bottomは呼び出し元(cursorY = childBottom + margin.bottom)
        // 側で加算する設計なので、ここではoriginY + totalHeightのみを返す
        // (以前はmargin.top/bottomを二重加算しており、兄弟要素が想定より下にずれる/
        // 詰まって重なるバグの一因になっていた)。
        return originY + totalHeight
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
