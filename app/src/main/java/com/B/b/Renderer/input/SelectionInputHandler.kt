package com.B.b.Renderer.input

import android.content.Context

class SelectionInputHandler(
    private val context: Context
) {
    // 指で隠れないための Y軸上方向オフセット（例: 14dp）
    private val touchOffsetYPx = 14f * context.resources.displayMetrics.density
    
    val state = TextSelectionState()

    /**
     * 【Step 1】長押し発火（Wordモード起動）
     */
    fun onLongPress(rawX: Float, rawY: Float, nodeText: String, textCharWidths: List<Float>, boxRect: android.graphics.RectF) {
        val adjustedY = rawY - touchOffsetYPx // 指の直上をヒット対象にする
        
        state.clear()
        state.fullText = nodeText
        state.initialTouchX = rawX
        state.initialTouchY = adjustedY
        
        // タッチ位置の文字インデックスを算出
        val hitIndex = calculateCharIndexAt(rawX - boxRect.left, textCharWidths)
        
        // 初期状態: タッチ箇所の単語境界（スペースや句読点）を検索
        val (wStart, wEnd) = findWordBoundary(nodeText, hitIndex)
        
        state.mode = SelectionMode.WORD
        state.startIndex = wStart
        state.endIndex = wEnd
        state.maxDragDistanceX = 0f
    }

    /**
     * 【Step 3】ドラッグ移動処理（折り返し検知とモード切替）
     */
    fun onDrag(currentRawX: Float, currentRawY: Float, textCharWidths: List<Float>, boxRect: android.graphics.RectF) {
        if (state.mode == SelectionMode.NONE) return

        val adjustedY = currentRawY - touchOffsetYPx
        val deltaX = currentRawX - state.initialTouchX

        // 1. 折り返し（逆方向ドラッグ）の検知ロジック
        if (state.mode == SelectionMode.WORD) {
            val currentDistance = Math.abs(deltaX)
            
            if (currentDistance > state.maxDragDistanceX) {
                // 外側へ伸ばしている間は最大ドラッグ距離を更新
                state.maxDragDistanceX = currentDistance
            } else if (state.maxDragDistanceX - currentDistance > 10f) {
                // ✨ 外側へ伸びた距離から 10px 以上内側に引き返した！
                // -> 人間が「行き過ぎた」「微調整したい」と感じた合図なので1文字単位へ降格
                state.mode = SelectionMode.CHARACTER
            }
        }

        // 2. 現在モードに応じた範囲の更新
        val newCharIndex = calculateCharIndexAt(currentRawX - boxRect.left, textCharWidths)
        
        if (state.mode == SelectionMode.CHARACTER) {
            // 1文字単位で厳密に端点を更新
            if (state.isDraggingStartHandle) {
                state.startIndex = newCharIndex.coerceIn(0, state.endIndex - 1)
            } else {
                state.endIndex = newCharIndex.coerceIn(state.startIndex + 1, state.fullText.length)
            }
        } else {
            // WORD モードの拡張（単語単位でスナップ）
            val (wStart, wEnd) = findWordBoundary(state.fullText, newCharIndex)
            if (newCharIndex < state.startIndex) {
                state.startIndex = wStart
            } else {
                state.endIndex = wEnd
            }
        }
    }

    /** タッチX座標から該当する文字インデックスを計算 */
    private fun calculateCharIndexAt(localX: Float, charWidths: List<Float>): Int {
        var accumulatedX = 0f
        for (i in charWidths.indices) {
            accumulatedX += charWidths[i]
            if (localX < accumulatedX) return i
        }
        return charWidths.size
    }

    /** 簡易的な単語境界判定（スペース・記号・和文区切り） */
    private fun findWordBoundary(text: String, index: Int): Pair<Int, Int> {
        if (text.isEmpty()) return Pair(0, 0)
        val safeIndex = index.coerceIn(0, text.length - 1)
        
        var start = safeIndex
        while (start > 0 && !text[start - 1].isWhitespace() && text[start - 1] !in "、。，．,.") {
            start--
        }
        
        var end = safeIndex
        while (end < text.length && !text[end].isWhitespace() && text[end] !in "、。，．,.") {
            end++
        }
        
        return Pair(start, (end + 1).coerceAtMost(text.length))
    }
}
