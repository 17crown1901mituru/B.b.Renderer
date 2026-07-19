package com.B.b.Renderer.htmx

import com.B.b.Renderer.core.DirtyLevel
import com.B.b.Renderer.core.Element
import com.B.b.Renderer.core.HtmlFragmentParser
import com.B.b.Renderer.core.RenderPriority
import com.B.b.Renderer.layout.LayoutEngine
import okhttp3.FormBody
import okhttp3.OkHttpClient
import okhttp3.Request
import okhttp3.Response

class HtmxRenderEngine(
    private val okHttp: OkHttpClient,
    private val htmlParser: HtmlFragmentParser,
    private val layoutEngine: LayoutEngine,
) {
    companion object {
        const val PROMOTION_THRESHOLD = 5
    }

    private val stabilityStore = mutableMapOf<Long, NodeStability>()
    private val outcomeIndex = mutableMapOf<ActionSignature, KnownOutcome>()
    private val lastParamsBySignature = mutableMapOf<ActionSignature, Map<String, String>>()
    private val commandCache = mutableMapOf<Long, List<Any>>() // 実体はDrawCommand型、後で差し替え

    private val currentTreeRoot: Element get() = layoutEngine.root

    /** submitボタン等がタップされた時のエントリポイント */
    suspend fun handleAction(triggerNode: Element, params: Map<String, String>): Element {
        val signature = buildSignature(triggerNode, params)
        val known = outcomeIndex[signature]

        if (known != null && known.unchangedStreak >= PROMOTION_THRESHOLD && !known.suspiciousFlag) {
            preWarmTarget(signature.hxTarget)
        }

        val response = executeRequest(signature, params)
        val newFragment = htmlParser.parseFragment(response.body?.string() ?: "")
        val targetNode = currentTreeRoot.querySelector(signature.hxTarget) ?: return currentTreeRoot

        val level = detectActionChange(
            oldNode = targetNode,
            newNode = newFragment,
            params = params,
            lastKnownParams = lastParamsBySignature[signature],
        )
        applyResultByLevel(level, signature, targetNode, newFragment, params)

        return targetNode
    }

    private fun buildSignature(node: Element, params: Map<String, String>): ActionSignature {
        val method = if (node.attributes.containsKey("hx-post")) "POST" else "GET"
        return ActionSignature(
            sourcePath = layoutEngine.currentPath,
            method = method,
            requestUrl = node.attributes["hx-post"] ?: node.attributes["hx-get"] ?: "",
            hxTarget = node.attributes["hx-target"] ?: "this",
            hxSwap = node.attributes["hx-swap"] ?: "innerHTML",
            paramKeys = params.keys,
        )
    }

    private fun executeRequest(signature: ActionSignature, params: Map<String, String>): Response {
        val requestBuilder = Request.Builder().url(signature.requestUrl)
        if (signature.method == "POST") {
            val formBody = FormBody.Builder().apply {
                params.forEach { (k, v) -> add(k, v) }
            }.build()
            requestBuilder.post(formBody)
        }
        return okHttp.newCall(requestBuilder.build()).execute()
    }

    private fun applyResultByLevel(
        level: ChangeDetectionLevel,
        signature: ActionSignature,
        targetNode: Element,
        newFragment: Element,
        params: Map<String, String>,
    ) {
        when (level) {
            ChangeDetectionLevel.VISUAL_CUE, ChangeDetectionLevel.STRUCTURAL_HASH -> {
                applySwapWithSeqFilter(targetNode, signature.hxSwap, newFragment)
                recordOutcome(signature, newFragment, streakReset = true)
                lastParamsBySignature[signature] = params
            }
            ChangeDetectionLevel.PARAMETER_SUSPICIOUS -> {
                applySwapWithSeqFilter(targetNode, signature.hxSwap, newFragment)
                outcomeIndex[signature]?.suspiciousFlag = true
                lastParamsBySignature[signature] = params
            }
            ChangeDetectionLevel.NONE -> {
                recordOutcome(signature, newFragment, streakReset = false)
                stabilityStore.getOrPut(targetNode.seq) { NodeStability(targetNode.seq) }.unchangedStreak++
                // 描画は行わない、commandCacheもそのまま流用
            }
        }
    }

    /**
     * swapを実行しつつ、seq単位でreconcileし、変化のあったseqだけをdirty化する。
     * 変化なしと判定されたseqはcomputedRect/描画コマンドともに再利用される。
     */
    private fun applySwapWithSeqFilter(target: Element, swapMode: String, newFragment: Element) {
        val pairs = SeqReconciler.reconcile(target, newFragment)
        val dirtySeqs = mutableSetOf<Long>()

        pairs.forEach { (oldEl, newEl) ->
            val level = detectNodeChange(oldEl, newEl)
            if (level != ChangeDetectionLevel.NONE) {
                dirtySeqs.add(newEl.seq)
            }
        }

        when (swapMode) {
            "outerHTML" -> {
                val parent = target.parent
                if (parent != null) layoutEngine.replaceNode(target, newFragment)
            }
            "beforeend" -> layoutEngine.appendChildren(target, newFragment.children)
            else -> layoutEngine.replaceChildren(target, newFragment.children)
        }

        dirtySeqs.forEach { seq -> commandCache.remove(seq) }
        target.markDirty(DirtyLevel.SUBTREE)
    }

    private fun recordOutcome(signature: ActionSignature, fragment: Element, streakReset: Boolean) {
        val visual = extractVisualFingerprint(fragment)
        val hash = computeStructuralHash(fragment)
        val known = outcomeIndex.getOrPut(signature) { KnownOutcome(signature, visual, hash) }
        known.lastVisualFingerprint = visual
        known.lastStructuralHash = hash
        known.unchangedStreak = if (streakReset) 0 else known.unchangedStreak + 1
    }

    private fun preWarmTarget(hxTargetSelector: String) {
        val targetNode = currentTreeRoot.querySelector(hxTargetSelector) ?: return
        targetNode.priorityHint = RenderPriority.CRITICAL
    }
}
