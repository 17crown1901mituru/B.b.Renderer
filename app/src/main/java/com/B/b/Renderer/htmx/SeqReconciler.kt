package com.B.b.Renderer.htmx

import com.B.b.Renderer.core.Element

/**
 * 新旧ツリー間で「同一要素」を対応付ける。
 * id属性を持つ要素のみを追跡対象とし、id無しの要素は毎回新規(EPHEMERAL)扱いにする。
 * 誤ったseq流用による事故を避けるための意図的な割り切り。
 */
object SeqReconciler {

    fun reconcile(oldRoot: Element, newRoot: Element): List<Pair<Element, Element>> {
        val oldById = mutableMapOf<String, Element>()
        indexById(oldRoot, oldById)

        val pairs = mutableListOf<Pair<Element, Element>>()
        matchRecursive(oldById, newRoot, pairs)
        return pairs
    }

    private fun indexById(node: Element, out: MutableMap<String, Element>) {
        node.attributes["id"]?.let { id -> out[id] = node }
        node.children.filterIsInstance<Element>().forEach { indexById(it, out) }
    }

    private fun matchRecursive(oldById: Map<String, Element>, newNode: Element, pairs: MutableList<Pair<Element, Element>>) {
        val id = newNode.attributes["id"]
        if (id != null) {
            oldById[id]?.let { oldMatch ->
                newNode.seq = oldMatch.seq // internal setはこのモジュール内から呼ぶ運用ルール
                pairs.add(oldMatch to newNode)
            }
        }
        newNode.children.filterIsInstance<Element>().forEach { matchRecursive(oldById, it, pairs) }
    }
}
