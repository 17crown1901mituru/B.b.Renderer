package com.B.b.Renderer.render

import android.content.Context
import android.view.View
import com.B.b.Renderer.render.gpu.GLEngineView
import com.B.b.Renderer.render.gpu.GpuCapabilityDetector
import com.B.b.Renderer.render.gpu.RenderTier

object RendererFactory {

    /**
     * MINIMAL判定(GLES3.0未満、EGL初期化失敗など)の場合のみCanvas版にフォールバックする。
     * それ以外は全てGPU版を使う(STANDARD以上ならQuadBatch+TexturedQuadで十分動く設計のため)。
     *
     * 戻り値は android.view.View だが、実体は必ず EngineHostView を実装しているので
     * 呼び出し側で `as EngineHostView` すればよい。
     */
    fun create(context: Context): View {
        val caps = GpuCapabilityDetector.detect()
        val tier = GpuCapabilityDetector.classifyTier(caps)

        return if (tier == RenderTier.MINIMAL) {
            EngineView(context)
        } else {
            GLEngineView(context)
        }
    }

    /**
     * 常にCPU(Canvas)版を返す。PiP小窓表示のように、複数の描画ループを同時に
     * 走らせる場面でGPUコンテキストを増やすと発熱に直結するため、
     * GPU判定を無視して強制的にCanvas版を使わせるための入り口(2026-07議論分)。
     */
    fun createForceCpu(context: Context): View = EngineView(context)
}
