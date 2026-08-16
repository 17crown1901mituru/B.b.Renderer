package com.B.b.Renderer.input

import com.B.b.Renderer.core.DirtyLevel
import com.B.b.Renderer.core.Element
import com.B.b.Renderer.core.FormControlElement
import com.B.b.Renderer.style.Display
import com.B.b.Renderer.style.PointerEvents

/** stackingContextに従い、DOM順から実際の描画順(手前が最後)を確定する */
fun resolvePaintOrder(root: Element): List<Element> {
    val output = mutableListOf<Element>()
    collectInPaintOrder(root, output)
    return output
}

private fun collectInPaintOrder(node: Element, output: MutableList<Element>) {
    val elementChildren = node.children.filterIsInstance<Element>()
    val normalFlow = elementChildren.filter { it.stackingContext?.isolatesChildren != true }
    val isolated = elementChildren.filter { it.stackingContext?.isolatesChildren == true }
        .sortedBy { it.stackingContext?.zIndex ?: 0 }

    output.add(node)
    normalFlow.forEach { collectInPaintOrder(it, output) }
    isolated.forEach { collectInPaintOrder(it, output) }
}

/** 手前(paintOrderの後方)から逆順に走査し、最初にヒットした要素を返す */
fun hitTest(root: Element, x: Float, y: Float): Element? {
    val paintList = resolvePaintOrder(root)
    for (node in paintList.asReversed()) {
        if (node.computedRect.contains(x, y) && isHitTestable(node)) {
            return node
        }
    }
    return null
}

private fun isHitTestable(node: Element): Boolean {
    return node.computedStyle.pointerEvents != PointerEvents.NONE &&
        node.computedStyle.display != Display.NONE &&
        !node.elementState.disabled
}

enum class TouchPhase { DOWN, MOVE, UP, CANCEL }

/**
 * タッチのライフサイクル管理。down/upが同一要素上で完結した場合のみクリック確定とする。
 * 縦方向のドラッグはタップ候補を打ち切り、スクロールとして扱う(2026-07議論分: 元々
 * MOVEは無視されるだけでスクロール自体が未実装だったため追加)。
 */
class TouchInputController(
    private val root: Element,
    private val layoutEngine: com.B.b.Renderer.layout.LayoutEngine,
    private val radioGroupController: RadioGroupController,
    private val onClick: (Element) -> Unit,
    private val requestRedraw: () -> Unit,
) {
    private var activePressTarget: Element? = null
    private var downScreenY = 0f
    private var lastScreenY = 0f
    private var isDragging = false

    companion object {
        private const val TOUCH_SLOP_PX = 24f // これ未満の移動はタップのブレとして許容する
    }

    fun onTouchEvent(phase: TouchPhase, x: Float, y: Float) {
        // 画面上の座標(screen space)をページ内座標(page space)に変換する。
        // computedRectはスクロールを考慮しないページ全体基準の座標なので、
        // ヒットテストの前に必ずscrollYを足す。
        val pageY = y + layoutEngine.scrollY
        when (phase) {
            TouchPhase.DOWN -> {
                downScreenY = y
                lastScreenY = y
                isDragging = false
                activePressTarget = hitTest(root, x, pageY)
            }
            TouchPhase.MOVE -> {
                if (!isDragging && kotlin.math.abs(y - downScreenY) > TOUCH_SLOP_PX) {
                    isDragging = true
                    activePressTarget = null // ドラッグ確定したらタップ候補は取り消す
                }
                if (isDragging) {
                    val deltaY = y - lastScreenY
                    // 指を上に動かす(deltaYが負)ほど下方向へスクロールするのでscrollYは加算
                    layoutEngine.scrollBy(-deltaY)
                    requestRedraw()
                }
                lastScreenY = y
            }
            TouchPhase.UP -> {
                if (!isDragging) {
                    val hit = hitTest(root, x, pageY)
                    if (hit != null && hit === activePressTarget) {
                        handleTap(hit)
                    }
                }
                activePressTarget = null
                isDragging = false
            }
            TouchPhase.CANCEL -> {
                activePressTarget = null
                isDragging = false
            }
        }
    }

    private fun handleTap(target: Element) {
        if (target.elementState.disabled) return

        if (target is FormControlElement) {
            when (target.inputType) {
                "radio" -> {
                    radioGroupController.onRadioTapped(target)
                    return
                }
                "checkbox" -> {
                    target.elementState.checked = !target.elementState.checked
                    markVisualDirty(target)
                    target.dispatchEvent("change")
                    return
                }
            }
        }
        onClick(target)
    }
}

/** name属性でグループ化されたラジオボタン群の排他制御 */
class RadioGroupController {
    private val groups = mutableMapOf<String, MutableList<FormControlElement>>()

    fun register(node: FormControlElement) {
        val name = node.attributes["name"] ?: return
        groups.getOrPut(name) { mutableListOf() }.add(node)
    }

    fun onRadioTapped(target: FormControlElement) {
        val name = target.attributes["name"] ?: return
        val group = groups[name] ?: return

        group.forEach { radio ->
            val wasChecked = radio.elementState.checked
            radio.elementState.checked = radio.seq == target.seq
            if (wasChecked != radio.elementState.checked) {
                markVisualDirty(radio)
            }
        }
        target.dispatchEvent("change")
    }
}

/** 座標(box model)は変えず、見た目だけ再計算対象にする軽量dirty化 */
fun markVisualDirty(node: Element) {
    node.markDirty(DirtyLevel.STYLE)
}

/** クリックイベントのバブリング。hx-*要素に到達したらそこで伝播を止める */
fun dispatchClick(target: Element, onHtmxTrigger: (Element) -> Unit) {
    var current: Element? = target
    while (current != null) {
        // NOTE: eventListenersはEvent.kt導入時にEventListener((Event)->Unit)へ移行済み。
        // ここでは(target)ではなくEvent(type="click", target=target)を渡す(旧実装はElementを
        // そのまま渡していて型不一致でビルドが通らなかったため修正)。
        current.eventListeners["click"]?.toList()?.forEach { handler ->
            handler.invoke(com.B.b.Renderer.core.Event("click", target))
        }

        if (current.attributes.containsKey("hx-post") || current.attributes.containsKey("hx-get")) {
            onHtmxTrigger(current)
            break
        }
        current = current.parent
    }
}
