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
 */
class TouchInputController(
    private val root: Element,
    private val radioGroupController: RadioGroupController,
    private val onClick: (Element) -> Unit,
    private val requestRedraw: () -> Unit,
) {
    private var activePressTarget: Element? = null

    fun onTouchEvent(phase: TouchPhase, x: Float, y: Float) {
        when (phase) {
            TouchPhase.DOWN -> {
                val hit = hitTest(root, x, y)
                activePressTarget = hit
            }
            TouchPhase.UP -> {
                val hit = hitTest(root, x, y)
                if (hit != null && hit === activePressTarget) {
                    handleTap(hit)
                }
                activePressTarget = null
            }
            TouchPhase.CANCEL -> {
                activePressTarget = null
            }
            TouchPhase.MOVE -> Unit
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
