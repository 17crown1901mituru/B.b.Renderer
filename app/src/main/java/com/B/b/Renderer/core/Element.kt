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
    // 2026-08、インラインフロー対応(LayoutEngine.layoutInlineRun()参照)。
    // <a>等、テキストと混在して折り返された(display:inline)要素の場合、computedRectは
    // 「その要素の単語が乗った全ての行の外接矩形」に過ぎず、複数行にまたがる要素だと
    // 矩形内に無関係な空白領域を含み得る(例: 1行目の右端付近の単語と2行目の左端付近の
    // 単語だけがその要素のものだと、外接矩形は1〜2行目の間を全部覆ってしまう)。
    // ヒットテスト用に、行ごとの実際の占有矩形をこちらに別途持たせる
    // (InputHandling.hitTest()参照)。インラインフローに参加しない通常のブロック要素では
    // 空リストのままで、その場合はcomputedRectがそのまま正確なヒット領域になる。
    var inlineFragments: List<LayoutRect> = emptyList()
    // 2026-08、インラインフロー対応。このElementを"コンテナ"として直接の子(TextNode/
    // display:inline要素)から検出された折り返し塊(inline run)の一覧。GLEngineRendererが
    // 実際の描画(SpannableStringBuilder化・StaticLayoutでの再折り返し・GPUテクスチャ化)に
    // 使う。ブロック子要素だけを持つ・子要素を持たない等、インライン内容が無い要素では
    // 空リストのまま。
    var inlineRuns: List<InlineRunLayout> = emptyList()
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

/**
 * img 用(2026-08対応)。
 * ネットワーク取得・デコードは非同期(EngineActivity側)で行われるため、naturalWidth/
 * naturalHeightは初期状態では0で、LOADEDになって初めて実サイズが埋まる。
 * LayoutEngineはこの値を見てCSS width/height:autoの際の実寸を決める(未取得の間は
 * 高さ0の仮ボックス扱いとし、取得完了時にmarkDirty(LAYOUT)されて再計算される)。
 *
 * decodedImageは、core層をAndroidに依存させない既存方針(MediaElement.mediaControllerと
 * 同じパターン)に合わせてAny?で保持する。実体はandroid.graphics.Bitmapを想定し、
 * GPUテクスチャへのアップロードが完了したらGLEngineRenderer側でnullに戻す
 * (CPU側Bitmapを不要に保持し続けないため)。
 */
class ImageElement(tag: String) : Element(tag) {
    var naturalWidth: Int = 0
    var naturalHeight: Int = 0
    var loadState: ImageLoadState = ImageLoadState.PENDING
    var decodedImage: Any? = null
}

enum class ImageLoadState { PENDING, LOADING, LOADED, FAILED }

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

/**
 * 2026-08、インラインフロー対応。1つの折り返し塊(TextNode/display:inline要素が
 * 混在した連続列)の位置情報。LayoutEngine.layoutBlock()が、コンテナ要素の子を
 * 走査中に「TextNodeまたはdisplay:inline要素が連続する区間」を1つの塊として検出する
 * たびに1つ生成し、コンテナElement.inlineRunsへ積む(詳細はLayoutEngine.layoutInlineRun()
 * のコメント参照)。
 *
 *   originX/originY: この塊の1行目の描画開始位置(コンテナのpadding込み、物理px)。
 *     StaticLayoutは常に(0,0)起点でレイアウトするため、GLEngineRenderer側は
 *     ラスタライズ後のBitmapをこの座標へオフセットして描画するだけでよい。
 *   maxWidth: 折り返しの基準幅(物理px)。LayoutEngine側の見積もり(Paint.measureTextに
 *     よる単語単位のgreedy折り返し)と、GLEngineRenderer側の実際のラスタライズ
 *     (StaticLayoutによる折り返し)の両方に同じ値を渡すことで、二つの結果が
 *     ほぼ一致するようにしている(完全一致を保証するものではない、既存のテキスト
 *     折り返し全般に共通する制約)。
 *   nodes: この塊を構成するNode列(TextNode、またはdisplay:inline要素)。DOM順のまま
 *     保持し、GLEngineRenderer側で改めてSpannableStringBuilderへ変換する
 *     (各ノードの現在のcomputedStyleを毎フレーム読み直すため、色・下線等の
 *     見た目だけの変更(DirtyLevel.STYLE)がlayoutBlock再実行なしでも反映される)。
 */
data class InlineRunLayout(
    val originX: Float,
    val originY: Float,
    val maxWidth: Float,
    val nodes: List<Node>,
)
