package com.B.b.Renderer.core

/**
 * DOM木の基底クラス。全てのノードはseqで一意に識別され、
 * dirtyフラグでレイアウト/描画の再計算範囲を制御する。
 */
sealed class Node(
    seq: Long = SeqAllocator.next(),
) {
    /**
     * reconcile(swap時の同一要素判定)でのみ書き換える想定。
     * 通常のパース経路では新規発行された値のまま変わらない。
     */
    var seq: Long = seq
        internal set

    var parent: Element? = null
    var dirty: DirtyLevel = DirtyLevel.SUBTREE
    var cachedCommands: List<Any>? = null // 実体はGPU描画コマンド型、後で差し替え

    fun markDirty(level: DirtyLevel) {
        if (level.ordinal > dirty.ordinal) dirty = level
        cachedCommands = null
        if (level == DirtyLevel.LAYOUT || level == DirtyLevel.SUBTREE) {
            parent?.markDirty(DirtyLevel.LAYOUT)
        }
    }

    abstract fun collectVisibleText(): String
}

object SeqAllocator {
    private var counter = 0L
    @Synchronized fun next(): Long = counter++
}

/**
 * CLEAN   : 再計算不要、既存の結果をそのまま使う
 * STYLE   : 見た目のみ変化、座標(box model)は据え置き
 * LAYOUT  : 寸法/位置が変わりうる、再フロー必要
 * SUBTREE : 子要素の構成自体が変わった(swap後など)
 */
enum class DirtyLevel { CLEAN, STYLE, LAYOUT, SUBTREE }

class TextNode(var data: String) : Node() {
    override fun collectVisibleText(): String = data
}
