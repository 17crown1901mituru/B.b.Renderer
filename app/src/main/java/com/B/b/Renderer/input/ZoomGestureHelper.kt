package com.B.b.Renderer.input

import android.content.Context
import android.view.MotionEvent
import android.view.ScaleGestureDetector
import com.B.b.Renderer.layout.LayoutEngine

/**
 * ピンチイン/アウトでのページズームを扱う。TouchInputController(タップ・縦スクロール)とは
 * 別の入力経路として扱い、ScaleGestureDetectorがマルチタッチ操作中と判定している間は
 * 呼び出し側でTouchInputControllerに渡さないこと(同時に反応するとタップ/スクロールが
 * ガタつくため)。
 */
class ZoomGestureHelper(context: Context, private val layoutEngine: () -> LayoutEngine?) {

    private val detector = ScaleGestureDetector(
        context,
        object : ScaleGestureDetector.SimpleOnScaleGestureListener() {
            override fun onScale(detector: ScaleGestureDetector): Boolean {
                val engine = layoutEngine() ?: return false
                engine.setZoom(engine.zoomScale * detector.scaleFactor)
                return true
            }
        },
    )

    /** trueを返した場合はピンチ操作中。呼び出し側はその間タップ/スクロール処理をスキップすること。 */
    fun onTouchEvent(event: MotionEvent): Boolean {
        detector.onTouchEvent(event)
        return detector.isInProgress
    }
}
