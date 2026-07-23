package com.B.b.Renderer.render.gpu

import android.content.Context
import android.opengl.GLSurfaceView
import android.util.AttributeSet
import android.view.MotionEvent
import com.B.b.Renderer.core.Element
import com.B.b.Renderer.input.RadioGroupController
import com.B.b.Renderer.input.TouchInputController
import com.B.b.Renderer.input.TouchPhase
import com.B.b.Renderer.input.dispatchClick
import com.B.b.Renderer.layout.LayoutEngine
import com.B.b.Renderer.render.EngineHostView

class GLEngineView(context: Context, attrs: AttributeSet? = null) :
    GLSurfaceView(context, attrs), EngineHostView {

    private var glRenderer: GLEngineRenderer? = null
    private var touchController: TouchInputController? = null
    override var onHtmxTrigger: ((Element) -> Unit)? = null

    init {
        setEGLContextClientVersion(3)
    }

    override fun attach(engine: LayoutEngine) {
        val existingRenderer = glRenderer
        if (existingRenderer == null) {
            val renderer = GLEngineRenderer(context.applicationContext, engine)
            glRenderer = renderer
            setRenderer(renderer)
            renderMode = RENDERMODE_WHEN_DIRTY
        } else {
            // setRenderer()はインスタンスにつき1回しか呼べない(2回目以降はIllegalStateException)。
            // rendererは使い回し、参照するLayoutEngineだけをGLスレッド上で差し替える。
            queueEvent { existingRenderer.updateLayoutEngine(engine) }
        }

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
            requestRedraw = { requestRender() },
        )
        engine.setFrameScheduler { block -> post(block) }
        com.B.b.Renderer.render.installDomAccessibility(
            hostView = this,
            rootProvider = { engine.root },
            scrollYProvider = { engine.scrollY },
            onActivate = { target -> dispatchClick(target) { hxNode -> onHtmxTrigger?.invoke(hxNode) } },
        )
        requestLayoutPass()
    }

    override fun requestLayoutPass() {
        glRenderer ?: return
        post { requestRender() }
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
        requestRender()
        return true
    }

    override fun onDetachedFromWindow() {
        super.onDetachedFromWindow()
        // queueEvent()自体がGLSurfaceView内部のGLThreadに触るため、setRenderer()が
        // 一度も呼ばれていない(=attach()未完了、glRendererがまだnull)状態で呼ぶと
        // GLThreadがnullでNullPointerExceptionになる。ラムダ内のnullチェックだけでは
        // 防げない(queueEvent呼び出し自体がクラッシュする)ため、外側でガードする。
        if (glRenderer != null) {
            queueEvent { glRenderer?.releaseResources() }
        }
    }
}
