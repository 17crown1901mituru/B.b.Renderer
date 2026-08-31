package com.B.b.Renderer.render.gpu

import android.content.Context
import android.opengl.GLSurfaceView
import android.util.AttributeSet
import android.view.MotionEvent
import com.B.b.Renderer.core.Element
import com.B.b.Renderer.input.RadioGroupController
import com.B.b.Renderer.input.TextSelectionGestureHelper
import com.B.b.Renderer.input.TextSelectionState
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

    // 2026-08、画面長押しでのテキスト範囲選択。GPU描画パイプライン(GLEngineRenderer)側には
    // 一切手を入れず、ハイライト矩形の実際の描画はEngineFrameLayout.selectionOverlayView
    // (通常のCanvas View、GLSurfaceViewの上に重ねて表示)側に任せる設計にしている
    // (README記載の「役割の明確な分離」方針に合わせ、Rhino/GL等の重いレイヤーには
    // 触れず、Android標準Viewの重ね合わせだけで完結させる)。
    private val textSelectionGesture = TextSelectionGestureHelper(
        context = context,
        rootProvider = { layoutEngine?.root },
        layoutEngineProvider = { layoutEngine },
        onSelectionChanged = { onTextSelectionChanged?.invoke(); safeRequestRender() },
    )

    override var onHtmxTrigger: ((Element) -> Unit)? = null
    override var onNavigate: ((String) -> Unit)? = null
    override var onTextSelectionChanged: (() -> Unit)? = null
    override val textSelectionState: TextSelectionState get() = textSelectionGesture.state

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
            nudgeSurfaceCreation()
        } else {
            // setRenderer()はインスタンスにつき1回しか呼べない(2回目以降はIllegalStateException)。
            // rendererは使い回し、参照するLayoutEngineだけをGLスレッド上で差し替える。
            // (2回目以降はGLコンテキストが既に生成済みでonSurfaceCreatedが再発火しないため、
            // attach→onSurfaceCreated間の遅延計測は初回起動時のみ意味を持つ)
            queueEvent { existingRenderer.updateLayoutEngine(engine) }
        }

        // 2026-08訂正: 以前はここ(setRenderer()より前)でclearSelection()を呼んでいたため、
        // 選択変更コールバック経由でrequestRender()が実行され、初回タブオープン時に
        // 「setRenderer()未実行=GLThread未生成」の状態でGLSurfaceView.requestRender()を
        // 呼んでNullPointerExceptionになっていた(実機クラッシュログで確認)。
        // setRenderer()/queueEvent()より後、GLThreadが必ず存在する状態まで移動して解消する。
        // タブ切替時、古いroot/Elementを参照したままの選択状態を持ち越さないための処理。
        textSelectionGesture.clearSelection()

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
                dispatchClick(
                    target,
                    onHtmxTrigger = { hxNode -> onHtmxTrigger?.invoke(hxNode) },
                    onNavigate = { url -> onNavigate?.invoke(url) },
                )
            },
            requestRedraw = { requestRender() },
        )
        engine.setFrameScheduler { block -> post(block) }
        com.B.b.Renderer.render.installDomAccessibility(
            hostView = this,
            rootProvider = { engine.root },
            scrollYProvider = { engine.scrollY },
            onActivate = { target ->
                dispatchClick(
                    target,
                    onHtmxTrigger = { hxNode -> onHtmxTrigger?.invoke(hxNode) },
                    onNavigate = { url -> onNavigate?.invoke(url) },
                )
            },
        )
        requestLayoutPass()
    }

    /**
     * 2026-07、初回起動時にGLSurfaceViewのSurfaceが実際には生成されず(RENDER_DIAGログで
     * onSurfaceCreatedが15秒以上発火しないことを確認済み)、バックグラウンド→フォアグラウンド
     * 復帰(ウィンドウフォーカスの喪失→再取得)をきっかけにようやく発火する不具合への
     * 応急策。実際にタスクをバックグラウンドへ送る(moveTaskToBack)方式は、ランチャーへの
     * 一瞬の遷移が視覚的に発生し、フォアグラウンド復帰も保証されないため避け、
     * 効いている本質部分と推測される「ウィンドウフォーカスの喪失→再取得」だけを、
     * ウィンドウにFLAG_NOT_FOCUSABLEを一瞬立てて即座に下ろすことで、画面には何も
     * 見せずに再現する。
     *
     * これは根本原因(端末/OS側でSurfaceViewのハードウェアレイヤー登録が遅れる挙動、
     * 詳細未特定)を修正するものではなく、症状を回避するためのworkaroundである点に注意。
     * 効果が無ければ、GLSurfaceView(独立Surface方式)自体をやめてTextureView方式へ
     * 切り替える、より大掛かりな対応を検討する。
     */
    private fun nudgeSurfaceCreation() {
        val activity = context as? android.app.Activity ?: return
        post {
            val window = activity.window ?: return@post
            window.setFlags(
                android.view.WindowManager.LayoutParams.FLAG_NOT_FOCUSABLE,
                android.view.WindowManager.LayoutParams.FLAG_NOT_FOCUSABLE,
            )
            window.clearFlags(android.view.WindowManager.LayoutParams.FLAG_NOT_FOCUSABLE)
            com.B.b.Renderer.debug.BehaviorAuditLog.record(
                com.B.b.Renderer.debug.BehaviorAuditLog.Category.RENDER_DIAG,
                "nudgeSurfaceCreation: toggled FLAG_NOT_FOCUSABLE",
            )
        }
    }

    override fun requestLayoutPass() {
        glRenderer ?: return
        post { requestRender() }
    }

    /**
     * 2026-08、GLThread未生成(setRenderer()未実行)の状態でrequestRender()を呼ぶと
     * NullPointerExceptionになる(実機クラッシュで確認済み、attach()内の呼び出し順序を
     * 直したのが本質的な修正だが、念のためこちら経由の呼び出し全てにもガードを掛けておく)。
     */
    private fun safeRequestRender() {
        if (glRenderer != null) requestRender()
    }

    override fun selectedText(): String = textSelectionGesture.selectedText()

    override fun clearTextSelection() = textSelectionGesture.clearSelection()

    override fun onTouchEvent(event: MotionEvent): Boolean {
        if (zoomGesture.onTouchEvent(event)) {
            safeRequestRender()
            return true
        }

        // 2026-08、長押しテキスト選択。EngineView(Canvas版)と同じ考え方で、選択が
        // 今まさに始まった瞬間だけTouchInputControllerへCANCELを送り、誤タップ・
        // 誤スクロール判定を防ぐ。
        val wasSelectionActive = textSelectionGesture.isSelectionActive
        if (textSelectionGesture.onTouchEvent(event)) {
            if (!wasSelectionActive && textSelectionGesture.isSelectionActive) {
                touchController?.onTouchEvent(TouchPhase.CANCEL, event.x, event.y)
            }
            safeRequestRender()
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
            "GLEngineView.onAttachedToWindow() hasWindowFocus=${hasWindowFocus()} isShown=$isShown visibility=$visibility width=$width height=$height",
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
