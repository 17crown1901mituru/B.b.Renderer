package com.B.b.Renderer.render

import android.graphics.Rect
import android.os.Bundle
import android.view.View
import android.view.accessibility.AccessibilityNodeInfo
import android.view.accessibility.AccessibilityNodeProvider
import com.B.b.Renderer.core.Element
import com.B.b.Renderer.core.Node
import com.B.b.Renderer.debug.BehaviorAuditLog

/**
 * 独自レンダリング(GPU/Canvas直描画)のDOMを、Android標準のアクセシビリティ
 * フレームワーク(TalkBack等)に橋渡しする。
 *
 * このエンジンはネイティブView階層を使わず自前でDOMを描画しているため、
 * WebViewと違ってTalkBackには何も見えない状態がデフォルトになる。
 * `View.AccessibilityDelegate.getAccessibilityNodeProvider()`を使い、
 * DOMツリーを「仮想ビュー階層」としてTalkBackに公開する。
 *
 * OSレベルの入力監視やアクセシビリティサービス自体の実装ではない
 * (android.accessibilityservice.AccessibilityServiceは実装しない)。
 * あくまで「このアプリの中身をTalkBack等の既存サービスに正しく説明する」
 * という、標準的なカスタムView向けアクセシビリティ対応の範囲に留める。
 */
fun installDomAccessibility(
    hostView: View,
    rootProvider: () -> Element?,
    onActivate: (Element) -> Unit,
    scrollYProvider: () -> Float = { 0f },
) {
    hostView.setAccessibilityDelegate(object : View.AccessibilityDelegate() {
        override fun getAccessibilityNodeProvider(host: View): AccessibilityNodeProvider =
            DomAccessibilityNodeProvider(host, rootProvider, onActivate, scrollYProvider)
    })
    hostView.isFocusable = true
    hostView.importantForAccessibility = View.IMPORTANT_FOR_ACCESSIBILITY_YES
}

private class DomAccessibilityNodeProvider(
    private val hostView: View,
    private val rootProvider: () -> Element?,
    private val onActivate: (Element) -> Unit,
    private val scrollYProvider: () -> Float,
) : AccessibilityNodeProvider() {

    // virtualViewId <-> Element の対応表。createAccessibilityNodeInfo()のたびに
    // 現在のDOMツリーから作り直す(HTMX swap等でツリーが変わっても常に最新を返すため)。
    private val idToElement = mutableMapOf<Int, Element>()

    private fun virtualId(element: Element): Int = System.identityHashCode(element)

    /** 「アクセシビリティ上、意味のある要素」だけを対象にする(空のwrapper divは除外) */
    private fun isSignificant(element: Element): Boolean {
        if (element.computedStyle.display == com.B.b.Renderer.style.Display.NONE) return false
        val interactive = element.tag in INTERACTIVE_TAGS ||
            element.eventListeners.containsKey("click") ||
            element.attributes.containsKey("hx-post") ||
            element.attributes.containsKey("hx-get")
        val hasOwnText = element.children.any { it is com.B.b.Renderer.core.TextNode && it.collectVisibleText().isNotBlank() }
        return interactive || hasOwnText
    }

    private fun rebuildIndex(): Element? {
        idToElement.clear()
        val root = rootProvider() ?: return null
        fun walk(node: Node) {
            if (node is Element) {
                if (isSignificant(node)) idToElement[virtualId(node)] = node
                node.children.forEach { walk(it) }
            }
        }
        walk(root)
        return root
    }

    /** virtualViewIdから見た「アクセシビリティ上の親」(直近の意味のある祖先、無ければホスト自身) */
    private fun significantParentId(element: Element): Int {
        var current = element.parent
        while (current != null) {
            if (isSignificant(current)) return virtualId(current)
            current = current.parent
        }
        return HOST_VIEW_ID
    }

    private fun significantChildren(element: Element): List<Element> {
        val result = mutableListOf<Element>()
        fun walk(node: Node) {
            if (node !is Element) return
            if (isSignificant(node)) {
                result.add(node)
            } else {
                node.children.forEach { walk(it) }
            }
        }
        element.children.forEach { walk(it) }
        return result
    }

    override fun createAccessibilityNodeInfo(virtualViewId: Int): AccessibilityNodeInfo? {
        val root = rebuildIndex() ?: return null

        if (virtualViewId == HOST_VIEW_ID) {
            val info = AccessibilityNodeInfo.obtain(hostView)
            info.setSource(hostView, HOST_VIEW_ID)
            val topChildren = if (isSignificant(root)) listOf(root) else significantChildren(root)
            topChildren.forEach { info.addChild(hostView, virtualId(it)) }
            return info
        }

        val element = idToElement[virtualViewId] ?: return null
        val info = AccessibilityNodeInfo.obtain(hostView, virtualViewId)
        info.setSource(hostView, virtualViewId)
        info.setParent(hostView, significantParentId(element))
        info.className = element.tag

        val text = element.collectVisibleText().trim().take(MAX_TEXT_LENGTH)
        info.text = text.ifBlank { element.attributes["aria-label"] ?: element.attributes["alt"] }

        val rect = element.computedRect
        val scrollY = scrollYProvider()
        val screenLocation = IntArray(2)
        hostView.getLocationOnScreen(screenLocation)
        val onScreenY = rect.y - scrollY.toInt()
        info.setBoundsInParent(Rect(rect.x, onScreenY, rect.x + rect.width, onScreenY + rect.height))
        info.setBoundsInScreen(
            Rect(
                screenLocation[0] + rect.x,
                screenLocation[1] + onScreenY,
                screenLocation[0] + rect.x + rect.width,
                screenLocation[1] + onScreenY + rect.height,
            ),
        )

        val clickable = element.tag in INTERACTIVE_TAGS ||
            element.eventListeners.containsKey("click") ||
            element.attributes.containsKey("hx-post") ||
            element.attributes.containsKey("hx-get")
        info.isClickable = clickable
        info.isFocusable = clickable || !text.isNullOrBlank()
        info.isEnabled = !(element.elementState.disabled)
        if (clickable) info.addAction(AccessibilityNodeInfo.AccessibilityAction.ACTION_CLICK)

        significantChildren(element).forEach { info.addChild(hostView, virtualId(it)) }

        return info
    }

    override fun performAction(virtualViewId: Int, action: Int, arguments: Bundle?): Boolean {
        if (action != AccessibilityNodeInfo.ACTION_CLICK) return false
        val element = idToElement[virtualViewId] ?: return false
        BehaviorAuditLog.record(
            BehaviorAuditLog.Category.A11Y_ACTION,
            "click: <${element.tag}${element.attributes["id"]?.let { " id=$it" } ?: ""}>",
        )
        onActivate(element)
        return true
    }

    companion object {
        private const val MAX_TEXT_LENGTH = 400
        private val INTERACTIVE_TAGS = setOf("a", "button", "input", "select", "textarea")
    }
}
