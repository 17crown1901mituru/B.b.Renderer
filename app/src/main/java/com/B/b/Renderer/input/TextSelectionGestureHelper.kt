package com.B.b.Renderer.input

import android.content.Context
import android.graphics.RectF
import android.view.GestureDetector
import android.view.MotionEvent
import com.B.b.Renderer.core.Element
import com.B.b.Renderer.layout.LayoutEngine

/**
 * 画面長押し→ドラッグでのテキスト範囲選択を扱う。ZoomGestureHelper(ピンチズーム)と
 * 同じく、TouchInputController(タップ・縦スクロール)とは別の入力経路として扱う。
 * 呼び出し側は本ヘルパーがtrueを返している間、TouchInputControllerへイベントを
 * 渡さないこと(EngineView/GLEngineView.onTouchEvent参照)。
 *
 * 対象要素は「テキストのみを子に持つ要素(<p>text</p>等)」に限定する
 * (SelectionInputHandler.onLongPress呼び出し時にElement.collectVisibleText()で
 * まとめて1つの文字列として扱うため。ハイライト矩形の精度についてはTextSelectionGeometry.kt
 * のコメント参照)。
 *
 * ライフサイクル:
 *   1. 長押し検知(内部のGestureDetector) → ヒットテストで対象要素を決定 → SelectionInputHandler.onLongPress
 *   2. ドラッグ中 → SelectionInputHandler.onDrag、ハイライト矩形を都度再計算
 *   3. 指を離しても選択状態は保持する(コピー操作やキャンセル操作は呼び出し側のUIに委ねる)
 *   4. 選択中に、ハイライト矩形の外側を新規タップされたら選択解除する
 */
class TextSelectionGestureHelper(
    context: Context,
    private val rootProvider: () -> Element?,
    private val layoutEngineProvider: () -> LayoutEngine?,
    private val onSelectionChanged: () -> Unit,
) {
    private val selectionHandler = SelectionInputHandler(context)

    /** 現在の選択状態。SelectionOverlayRendererへそのまま渡して描画に使う想定。 */
    val state: TextSelectionState get() = selectionHandler.state

    private var boxRect: RectF = RectF()
    private var charWidths: List<Float> = emptyList()

    /** trueの間はこのヘルパーが入力を占有している(=長押し確定後、選択解除まで)。 */
    var isSelectionActive: Boolean = false
        private set

    private val gestureDetector = GestureDetector(
        context,
        object : GestureDetector.SimpleOnGestureListener() {
            override fun onLongPress(e: MotionEvent) {
                val zoom = layoutEngineProvider()?.zoomScale ?: 1f
                beginSelection(e.x / zoom, e.y / zoom)
            }
        },
    )

    /**
     * @return trueの場合、このイベントは選択関連の処理に使われた(呼び出し側は
     *         TouchInputControllerへ回さないこと)。falseの場合は通常のタップ/
     *         スクロール判定に委ねてよい。
     */
    fun onTouchEvent(event: MotionEvent): Boolean {
        val zoom = layoutEngineProvider()?.zoomScale ?: 1f
        val scrollY = layoutEngineProvider()?.scrollY ?: 0f

        // 選択中に範囲外を新規タップされたら、その場で選択解除する(タップ自体の
        // 意味付け=通常のリンク遷移等は呼び出し側のTouchInputControllerに任せるため、
        // ここではfalseを返す=「選択解除はしたが、このイベント自体は消費していない」)。
        if (event.actionMasked == MotionEvent.ACTION_DOWN && isSelectionActive) {
            val pageY = event.y / zoom + scrollY
            val insideHighlight = state.highlightRects.any { it.contains(event.x / zoom, pageY) }
            if (!insideHighlight) {
                clearSelection()
            }
        }

        gestureDetector.onTouchEvent(event)

        if (!isSelectionActive) return false

        when (event.actionMasked) {
            MotionEvent.ACTION_MOVE -> {
                val pageY = event.y / zoom + scrollY
                selectionHandler.onDrag(event.x / zoom, pageY, charWidths, boxRect)
                refreshHighlight()
                onSelectionChanged()
            }
            else -> Unit
        }
        return true
    }

    private fun beginSelection(x: Float, y: Float) {
        val root = rootProvider() ?: return
        val engine = layoutEngineProvider() ?: return
        val pageY = y + engine.scrollY
        val target = hitTest(root, x, pageY) ?: return
        val text = target.collectVisibleText()
        if (text.isBlank()) return

        boxRect = RectF(
            target.computedRect.x.toFloat(),
            target.computedRect.y.toFloat(),
            (target.computedRect.x + target.computedRect.width).toFloat(),
            (target.computedRect.y + target.computedRect.height).toFloat(),
        )
        charWidths = measureCharWidths(text, target.computedStyle.fontSize)

        selectionHandler.onLongPress(x, pageY, text, charWidths, boxRect)
        refreshHighlight()
        isSelectionActive = true
        onSelectionChanged()
    }

    private fun refreshHighlight() {
        state.highlightRects.clear()
        state.highlightRects.addAll(computeHighlightRects(boxRect, state.startIndex, state.endIndex, charWidths))
    }

    /** 現在選択中のテキストを返す(未選択なら空文字)。 */
    fun selectedText(): String = state.getSelectedText()

    fun clearSelection() {
        isSelectionActive = false
        boxRect = RectF()
        charWidths = emptyList()
        state.clear()
        onSelectionChanged()
    }
}
