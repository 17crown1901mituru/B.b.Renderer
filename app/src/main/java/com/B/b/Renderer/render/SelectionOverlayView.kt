package com.B.b.Renderer.render

import android.content.Context
import android.graphics.Canvas
import android.view.View
import com.B.b.Renderer.input.TextSelectionState

/**
 * 2026-08、画面長押しでのテキスト範囲選択のハイライト表示専用View。
 *
 * GLEngineView(GPU描画)側の選択ハイライトを、GLEngineRenderer(OpenGL)の描画パイプライン
 * に組み込むのは(GL側のシェーダー/バッファ管理に手を入れる必要があり)大掛かりになるため、
 * EngineFrameLayout.mainContainer上でcontentView(EngineView/GLEngineViewどちらでも)の
 * 「さらに上」に重ねる、普通のAndroid Canvas Viewとして実装する。既存のloadingIndicator
 * (帯だけの単純なView)と同じ「Android標準Viewの重ね合わせで済ませる」考え方に合わせている。
 *
 * タッチ自体はこのViewでは受けない(isClickable=falseかつリスナー未設定のため、
 * onTouchEventはデフォルトでfalseを返し、下のcontentViewへそのままタッチが渡る)。
 */
class SelectionOverlayView(context: Context) : View(context) {

    private val renderer = SelectionOverlayRenderer()
    private var state: TextSelectionState? = null
    private var zoom = 1f
    private var scrollY = 0f

    /**
     * @param state nullを渡すと非表示(選択なし)として扱う。
     * @param zoom contentView側のcanvas.scale(zoomScale)と同じ値を渡すこと。
     * @param scrollY contentView側のcanvas.translate(0f, -scrollY)と同じ値を渡すこと。
     */
    fun update(state: TextSelectionState?, zoom: Float, scrollY: Float) {
        this.state = state
        this.zoom = zoom
        this.scrollY = scrollY
        invalidate()
    }

    override fun onDraw(canvas: Canvas) {
        super.onDraw(canvas)
        val currentState = state ?: return
        // contentView(EngineView.onDraw等)と全く同じ座標変換を再現することで、
        // ページ座標系で計算されたハイライト矩形がそのまま正しい画面位置に重なる。
        canvas.save()
        canvas.scale(zoom, zoom)
        canvas.translate(0f, -scrollY)
        renderer.drawSelectionOverlay(canvas, currentState)
        canvas.restore()
    }
}
