package com.B.b.Renderer.core

import com.B.b.Renderer.style.ComputedStyle
import com.B.b.Renderer.style.Display
import com.B.b.Renderer.style.PointerEvents

open class Element(
    val tag: String,
) : Node() {
    val children: MutableList<Node> = mutableListOf()
    val attributes: MutableMap<String, String> = mutableMapOf()
    val eventListeners: MutableMap<String, MutableList<EventListener>> = mutableMapOf()

    var computedStyle: ComputedStyle = ComputedStyle()
    var computedRect: LayoutRect = LayoutRect(0, 0, 0, 0)
    var stackingContext: StackingContext? = null
    var priorityHint: RenderPriority = RenderPriority.VISIBLE
    var elementState: ElementState = ElementState()

    // ---- 子要素操作 ----

    fun appendChild(node: Node) {
        node.parent = this
        children.add(node)
        markDirty(DirtyLevel.SUBTREE)
    }

    fun removeChild(node: Node) {
        if (children.remove(node)) {
            node.parent = null
            markDirty(DirtyLevel.SUBTREE)
        }
    }

    fun replaceChildren(newChildren: List<Node>) {
        children.forEach { it.parent = null }
        children.clear()
        newChildren.forEach {
            it.parent = this
            children.add(it)
        }
        markDirty(DirtyLevel.SUBTREE)
    }

    // ---- 検索 ----

    fun querySelector(selector: String): Element? =
        com.B.b.Renderer.style.CssSelectorEngine.let { engine ->
            findAll { engine.matches(it, selector) }.firstOrNull()
        }

    fun querySelectorAll(selector: String): List<Element> =
        findAll { com.B.b.Renderer.style.CssSelectorEngine.matches(it, selector) }

    fun findAll(predicate: (Element) -> Boolean): List<Element> {
        val result = mutableListOf<Element>()
        fun walk(node: Node) {
            if (node is Element) {
                if (predicate(node)) result.add(node)
                node.children.forEach { walk(it) }
            }
        }
        walk(this)
        return result
    }

    fun findFirst(predicate: (Element) -> Boolean): Element? {
        if (predicate(this)) return this
        children.forEach {
            if (it is Element) {
                val found = it.findFirst(predicate)
                if (found != null) return found
            }
        }
        return null
    }

    fun siblingIndexAmongSameTag(): Int {
        val siblings = parent?.children?.filterIsInstance<Element>()?.filter { it.tag == tag }
            ?: return 0
        return siblings.indexOf(this)
    }

    // ---- イベント ----

    fun addEventListener(type: String, handler: EventListener) {
        eventListeners.getOrPut(type) { mutableListOf() }.add(handler)
    }

    fun removeEventListener(type: String, handler: EventListener) {
        eventListeners[type]?.remove(handler)
    }

    /**
     * 現状はcapture/bubbleなし(target自身に登録されたリスナーのみ発火)。
     * 将来、親方向へのバブリングを追加する場合はここでparentを辿る形に拡張する。
     * 戻り値のEventでpreventDefault/stopPropagationの呼び出し結果を呼び出し元が確認できる。
     */
    fun dispatchEvent(type: String, detail: Any? = null): Event {
        val event = Event(type, this, detail)
        eventListeners[type]?.toList()?.forEach { listener ->
            if (event.propagationStopped) return@forEach
            listener.invoke(event)
        }
        return event
    }

    // ---- 表示テキスト ----

    override fun collectVisibleText(): String {
        if (computedStyle.display == Display.NONE) return ""
        return children.joinToString("") { it.collectVisibleText() }
    }

    // ---- ヒットテスト適格性 ----

    fun isHitTestable(): Boolean {
        return computedStyle.pointerEvents != PointerEvents.NONE &&
            computedStyle.display != Display.NONE &&
            !elementState.disabled
    }
}

/** input/select/textarea/button 用 */
class FormControlElement(tag: String) : Element(tag) {
    val name: String? get() = attributes["name"]
    val inputType: String? get() = attributes["type"]

    fun currentValue(): String = when (inputType) {
        "checkbox", "radio" -> if (elementState.checked) (attributes["value"] ?: "on") else ""
        else -> attributes["value"] ?: collectVisibleText()
    }
}

/** video/audio 用。実際の再生制御はmediaControllerに委譲する */
class MediaElement(tag: String) : Element(tag) {
    var mediaController: Any? = null // JsMediaElementを保持する想定(mediaモジュール側の型)
}

data class ElementState(
    var checked: Boolean = false,
    var disabled: Boolean = false,
    var readonly: Boolean = false,
    var selected: Boolean = false,
) {
    fun toBits(): Long {
        var b = 0L
        if (checked) b = b or 0x1
        if (disabled) b = b or 0x2
        if (readonly) b = b or 0x4
        if (selected) b = b or 0x8
        return b
    }
}

enum class RenderPriority { CRITICAL, VISIBLE, DEFERRED }

data class StackingContext(
    val zIndex: Int = 0,
    val isolatesChildren: Boolean = false,
)

data class LayoutRect(val x: Int, val y: Int, val width: Int, val height: Int) {
    fun contains(px: Float, py: Float): Boolean =
        px >= x && px <= x + width && py >= y && py <= y + height

    fun intersects(other: LayoutRect): Boolean =
        x < other.x + other.width && x + width > other.x &&
            y < other.y + other.height && y + height > other.y

    fun center(): Pair<Int, Int> = (x + width / 2) to (y + height / 2)
}
