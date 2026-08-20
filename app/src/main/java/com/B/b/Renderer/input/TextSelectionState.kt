package com.B.b.Renderer.input

import android.graphics.RectF

/**
 * 選択モード
 */
enum class SelectionMode {
    NONE,       // 未選択
    WORD,       // 初期長押し（単語/まとまり単位）
    CHARACTER   // 折り返し後の精密選択（1文字単位）
}

/**
 * テキスト選択状態管理クラス
 */
data class TextSelectionState(
    var mode: SelectionMode = SelectionMode.NONE,
    
    // 選択対象のノード/ボックス情報
    var targetNodeId: String? = null,
    var fullText: String = "",
    
    // 文字列のインデックス範囲
    var startIndex: Int = -1,
    var endIndex: Int = -1,
    
    // ドラッグ追跡用座標
    var initialTouchX: Float = 0f,
    var initialTouchY: Float = 0f,
    var maxDragDistanceX: Float = 0f, // 外側への最大ドラッグ移動量
    var isDraggingStartHandle: Boolean = false, // 開始ハンドルの操作中か
    
    // 描画用のハイライト矩形リスト（複数行にまたがる場合も考慮）
    val highlightRects: MutableList<RectF> = mutableListOf(),
    
    // 左右ハンドルの描画位置（画面座標）
    var startHandlePos: RectF = RectF(),
    var endHandlePos: RectF = RectF()
) {
    fun clear() {
        mode = SelectionMode.NONE
        targetNodeId = null
        fullText = ""
        startIndex = -1
        endIndex = -1
        maxDragDistanceX = 0f
        highlightRects.clear()
    }

    /** 選択テキストの抽出 */
    fun getSelectedText(): String {
        if (startIndex < 0 || endIndex < 0 || startIndex >= endIndex || endIndex > fullText.length) {
            return ""
        }
        return fullText.substring(startIndex, endIndex)
    }
}
