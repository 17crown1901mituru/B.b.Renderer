package com.B.b.Renderer.js

import com.B.b.Renderer.core.DirtyLevel
import com.B.b.Renderer.core.Element
import com.B.b.Renderer.core.TextNode
import com.B.b.Renderer.htmx.ChangeDetectionLevel
import com.B.b.Renderer.htmx.SeqReconciler
import com.B.b.Renderer.htmx.detectNodeChange

/**
 * htmx.js自体がAJAX/DOM置換を行う構成向けの「外側から被せる」最適化層。
 * Engine側のSeqReconciler/3段階変化検知(htmxパッケージ)をそのまま再利用し、
 * 変化のなかった要素はcomputedRect/GPU描画コマンドキャッシュを引き継いで
 * 再計算をスキップさせる。
 */
class HtmxSeqOptimizer {
    private var beforeSwapSnapshot: Element? = null

    fun captureBeforeSwap(target: JsElement) {
        beforeSwapSnapshot = snapshotElement(target.element)
    }

    fun applyAfterSwap(target: JsElement) {
        val old = beforeSwapSnapshot ?: return
        beforeSwapSnapshot = null

        val pairs = SeqReconciler.reconcile(old, target.element)
        val changedSeqs = mutableSetOf<Long>()

        // 1st pass: 変化なしの要素はcomputedRect/描画コマンドキャッシュを引き継ぎCLEANにする
        pairs.forEach { (oldEl, newEl) ->
            // detectNodeChange を使って純粋なノード差分を検知します
            val level = detectNodeChange(oldEl, newEl)
            if (level == ChangeDetectionLevel.NONE) {
                newEl.computedRect = oldEl.computedRect
                newEl.cachedCommands = oldEl.cachedCommands
                newEl.dirty = DirtyLevel.CLEAN
            } else {
                changedSeqs.add(newEl.seq)
            }
        }

        // 2nd pass: 変化ありの要素はSUBTREEまで上げ、祖先へ伝播させる。
        pairs.forEach { (_, newEl) ->
            if (newEl.seq in changedSeqs) {
                newEl.markDirty(DirtyLevel.SUBTREE)
            }
        }
    }

    /**
     * 元要素と構造・属性・状態が同じ、かつseq/computedRect/cachedCommandsも
     * 引き継いだ「切り離された」複製を作る。比較専用でDOMツリーには繋がない。
     */
    private fun snapshotElement(source: Element): Element {
        val clone = Element(source.tag)
        clone.seq = source.seq
        clone.attributes.putAll(source.attributes)
        clone.computedStyle = source.computedStyle
        clone.computedRect = source.computedRect
        clone.cachedCommands = source.cachedCommands
        clone.elementState = source.elementState.copy()

        source.children.forEach { child ->
            when (child) {
                is TextNode -> {
                    val textClone = TextNode(child.data)
                    textClone.seq = child.seq
                    clone.appendChild(textClone)
                }
                is Element -> clone.appendChild(snapshotElement(child))
            }
        }
        return clone
    }
}

