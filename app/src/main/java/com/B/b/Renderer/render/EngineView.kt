package com.B.b.Renderer.render

import android.content.Context
import android.graphics.Canvas
import android.util.AttributeSet
import android.view.MotionEvent
import android.view.View
import com.B.b.Renderer.core.Element
import com.B.b.Renderer.input.RadioGroupController
import com.B.b.Renderer.input.TouchInputController
import com.B.b.Renderer.input.TouchPhase
import com.B.b.Renderer.input.dispatchClick
import com.B.b.Renderer.layout.LayoutEngine

/**
 * Google製WebViewの代替。DOMツリーはEngineActivity側で構築し、
 * このViewはレイアウト結果の描画とタッチ入力の受け口に専念する。
 */
class EngineView(context: Context, attrs: AttributeSet? = null) :
    View(context, attrs), EngineHostView {

    private val renderer = CanvasRenderer()
    private var layoutEngine: LayoutEngine? = null
    private var touchController: TouchInputController? = null
    override var onHtmxTrigger: ((Element) -> Unit)? = null

    override fun attach(engine: LayoutEngine) {
        layoutEngine = engine
        val radioGroupController = RadioGroupController()
        touchController = TouchInputController(
            root = engine.root,
            layoutEngine = engine,
            radioGroupController = radioGroupController,
            onClick = { target ->
                com.B.b.Renderer.debug.BehaviorAuditLog.record(
                    com.B.b.Renderer.debug.BehaviorAuditLog.Category.NATIVE_TAP,
                    "<${target.tag}${target.attributes["id"]?.let { " id=$it" } ?: ""}>",
                )
                dispatchClick(target) { hxNode -> onHtmxTrigger?.invoke(hxNode) }
            },
            requestRedraw = { postInvalidate() },
        )
        engine.setFrameScheduler { block -> post(block) }
        com.B.b.Renderer.render.installDomAccessibility(
            hostView = this,
            rootProvider = { layoutEngine?.root },
            scrollYProvider = { layoutEngine?.scrollY ?: 0f },
            onActivate = { target -> dispatchClick(target) { hxNode -> onHtmxTrigger?.invoke(hxNode) } },
        )
        requestLayoutPass()
    }

    override fun requestLayoutPass() {
        layoutEngine?.scheduleLayoutPass()
        postInvalidate()
    }

    override fun onDraw(canvas: Canvas) {
        super.onDraw(canvas)
        val engine = layoutEngine ?: return
        canvas.save()
        canvas.translate(0f, -engine.scrollY)
        renderer.render(canvas, engine.root)
        canvas.restore()
    }

    override fun onTouchEvent(event: MotionEvent): Boolean {
        val controller = touchController ?: return super.onTouchEvent(event)
        val phase = when (event.actionMasked) {
            MotionEvent.ACTION_DOWN -> TouchPhase.DOWN
            MotionEvent.ACTION_MOVE -> TouchPhase.MOVE
            MotionEvent.ACTION_UP -> TouchPhase.UP
            MotionEvent.ACTION_CANCEL -> TouchPhase.CANCEL
            else -> return super.onTouchEvent(event)
        }
        controller.onTouchEvent(phase, event.x, event.y)
        postInvalidate()
        return true
    }
}
