package com.B.b.Renderer.htmx

import com.B.b.Renderer.core.Element
import com.B.b.Renderer.core.FormControlElement

data class ActionSignature(
    val sourcePath: String,
    val method: String,
    val requestUrl: String,
    val hxTarget: String,
    val hxSwap: String,
    val paramKeys: Set<String>,
)

data class NodeStability(
    val seq: Long,
    var structuralHash: Long = 0,
    var visualFingerprint: VisualFingerprint? = null,
    var unchangedStreak: Int = 0,
    var visitCount: Int = 0,
)

data class KnownOutcome(
    val signature: ActionSignature,
    var lastVisualFingerprint: VisualFingerprint?,
    var lastStructuralHash: Long,
    var unchangedStreak: Int = 0,
    var suspiciousFlag: Boolean = false,
)

enum class ChangeDetectionLevel { VISUAL_CUE, STRUCTURAL_HASH, PARAMETER_SUSPICIOUS, NONE }

/**
 * 知覚レベルの差分。テキスト/色/表示状態/操作可否(disabled等)のみを見る、
 * 最も安いコストの比較。ここで差が出れば以降の判定は不要。
 */
data class VisualFingerprint(
    val textContent: String,
    val backgroundColorHex: String?,
    val textColorHex: String?,
    val visibilityFlags: Long,
    val interactionState: Long,
)

fun extractVisualFingerprint(node: Element): VisualFingerprint {
    var stateBits = 0L
    if (node is FormControlElement) {
        if (node.elementState.disabled) stateBits = stateBits or 0x1
        if (node.elementState.readonly) stateBits = stateBits or 0x2
        if (node.elementState.checked) stateBits = stateBits or 0x4
        if (node.elementState.selected) stateBits = stateBits or 0x8
    }

    return VisualFingerprint(
        textContent = node.collectVisibleText().trim(),
        backgroundColorHex = node.computedStyle.backgroundColor.toHex(),
        textColorHex = node.computedStyle.color.toHex(),
        visibilityFlags = if (node.computedStyle.display.name == "NONE") 1L else 0L,
        interactionState = stateBits,
    )
}

/** タグ構成・属性キー構成・子の数と順序のみを見る、テキストやスタイル値は含めない */
fun computeStructuralHash(node: Element): Long {
    var hash = node.tag.hashCode().toLong()
    hash = hash * 31 + node.attributes.keys.sorted().hashCode()
    node.children.forEach { child ->
        if (child is Element) {
            hash = hash * 31 + computeStructuralHash(child)
        }
    }
    return hash
}

/**
 * ノード単位の純粋な差分判定(VISUAL_CUE → STRUCTURAL_HASHの2段階)。
 * ActionSignatureやリクエストparamsには一切関与しない。SeqReconciler経由の
 * ノードペア比較(reconcile後の各要素の差分検知)はこちらを使う。
 */
fun detectNodeChange(oldNode: Element, newNode: Element): ChangeDetectionLevel {
    val oldVisual = extractVisualFingerprint(oldNode)
    val newVisual = extractVisualFingerprint(newNode)
    if (oldVisual != newVisual) return ChangeDetectionLevel.VISUAL_CUE

    val oldHash = computeStructuralHash(oldNode)
    val newHash = computeStructuralHash(newNode)
    if (oldHash != newHash) return ChangeDetectionLevel.STRUCTURAL_HASH

    return ChangeDetectionLevel.NONE
}

/**
 * アクション単位(hx-get/hx-post実行時)の変化検知。
 * まずdetectNodeChangeでノード自体の差を見て、差が無い場合のみ
 * 送信パラメータの一致/不一致でNONE/PARAMETER_SUSPICIOUSを分ける
 * (見た目は変わらないのにパラメータが変わった=サーバー側の不整合を疑うシグナル)。
 */
fun detectActionChange(
    oldNode: Element,
    newNode: Element,
    params: Map<String, String>,
    lastKnownParams: Map<String, String>?,
): ChangeDetectionLevel {
    val nodeLevel = detectNodeChange(oldNode, newNode)
    if (nodeLevel != ChangeDetectionLevel.NONE) return nodeLevel

    val paramsSame = lastKnownParams == params
    return if (paramsSame) ChangeDetectionLevel.NONE else ChangeDetectionLevel.PARAMETER_SUSPICIOUS
}
