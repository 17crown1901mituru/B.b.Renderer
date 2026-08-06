package com.B.b.Renderer.render.gpu

import android.content.Context
import android.opengl.GLSurfaceView
import android.util.AttributeSet
import android.view.MotionEvent
import com.B.b.Renderer.core.Element
import com.B.b.Renderer.input.RadioGroupController
import com.B.b.Renderer.input.TouchInputController
import com.B.b.Renderer.input.TouchPhase
import com.B.b.Renderer.input.ZoomGestureHelper
import com.B.b.Renderer.input.dispatchClick
import com.B.b.Renderer.layout.LayoutEngine
import com.B.b.Renderer.render.EngineHostView

class GLEngineView(context: Context, attrs: AttributeSet? = null) :
    GLSurfaceView(context, attrs), EngineHostView {

    private var glRenderer: GLEngineRenderer? = null
    private var touchController: TouchInputController? = null
    private var layoutEngine: LayoutEngine? = null
    private val zoomGesture = ZoomGestureHelper(context) { layoutEngine }
    override var onHtmxTrigger: ((Element) -> Unit)? = null

    init {
        setEGLContextClientVersion(3)
    }

    override fun attach(engine: LayoutEngine) {
        // 2026-07、「起動直後、読み込みゲージが消えても描画されない」体感遅延の調査用。
        // GLSurfaceViewはウィンドウが実際にフォーカスを得るまでサーフェスを生成しないため、
        // 起動直後の権限ダイアログ等でウィンドウフォーカス確定が遅れると、attach()呼び出しから
        // 実際の初回描画(onSurfaceCreated)までの間が長くなる可能性がある。ここで基準時刻を取り、
        // GLEngineRenderer.onSurfaceCreated側で差分を計算してRENDER_DIAGへ記録する。
        val attachStartNanos = System.nanoTime()
        com.B.b.Renderer.debug.BehaviorAuditLog.record(
            com.B.b.Renderer.debug.BehaviorAuditLog.Category.RENDER_DIAG,
            "GLEngineView.attach() called",
        )

        layoutEngine = engine
        val existingRenderer = glRenderer
        if (existingRenderer == null) {
            val renderer = GLEngineRenderer(context.applicationContext, engine, attachStartNanos)
            glRenderer = renderer
            setRenderer(renderer)
            renderMode = RENDERMODE_WHEN_DIRTY
        } else {
            // setRenderer()はインスタンスにつき1回しか呼べない(2回目以降はIllegalStateException)。
            // rendererは使い回し、参照するLayoutEngineだけをGLスレッド上で差し替える。
            // (2回目以降はGLコンテキストが既に生成済みでonSurfaceCreatedが再発火しないため、
            // attach→onSurfaceCreated間の遅延計測は初回起動時のみ意味を持つ)
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
        if (zoomGesture.onTouchEvent(event)) {
            requestRender()
            return true
        }
        val controller = touchController ?: return super.onTouchEvent(event)
        val phase = when (event.actionMasked) {
            MotionEvent.ACTION_DOWN -> TouchPhase.DOWN
            MotionEvent.ACTION_MOVE -> TouchPhase.MOVE
            MotionEvent.ACTION_UP -> TouchPhase.UP
            MotionEvent.ACTION_CANCEL -> TouchPhase.CANCEL
            else -> return super.onTouchEvent(event)
        }
        val zoom = layoutEngine?.zoomScale ?: 1f
        controller.onTouchEvent(phase, event.x / zoom, event.y / zoom)
        requestRender()
        return true
    }

    override fun onAttachedToWindow() {
        super.onAttachedToWindow()
        // 2026-07、起動直後にonSurfaceCreatedが62秒近く発火せず、バックグラウンド→
        // フォアグラウンド復帰でようやく描画される不具合の調査用。
        // onAttachedToWindow/onWindowFocusChangedがonSurfaceCreatedよりどれだけ先行/
        // 後続するかを見ることで、「ウィンドウ描画イベントが一度も起きていない」
        // 仮説を検証する。
        com.B.b.Renderer.debug.BehaviorAuditLog.record(
            com.B.b.Renderer.debug.BehaviorAuditLog.Category.RENDER_DIAG,
            "GLEngineView.onAttachedToWindow() hasWindowFocus=$hasWindowFocus isShown=$isShown visibility=$visibility width=$width height=$height",
        )
    }

    override fun onWindowFocusChanged(hasFocus: Boolean) {
        super.onWindowFocusChanged(hasFocus)
        com.B.b.Renderer.debug.BehaviorAuditLog.record(
            com.B.b.Renderer.debug.BehaviorAuditLog.Category.RENDER_DIAG,
            "GLEngineView.onWindowFocusChanged(hasFocus=$hasFocus) isShown=$isShown width=$width height=$height",
        )
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
