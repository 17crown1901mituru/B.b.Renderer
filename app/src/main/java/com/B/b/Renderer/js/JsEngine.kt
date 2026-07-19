package com.B.b.Renderer.js

import com.B.b.Renderer.core.Element
import com.B.b.Renderer.core.TextNode
import okhttp3.OkHttpClient
import org.mozilla.javascript.Context
import org.mozilla.javascript.Scriptable
import org.mozilla.javascript.ScriptableObject
import java.io.InputStream

/**
 * ページJS実行のエントリポイント。
 *
 * 統合方法(Engine側で行うこと):
 *   1. LayoutEngine/HtmlFragmentParser/StyleResolverの参照が揃った時点で
 *      JsDomContextを1つ作る
 *   2. JsEngine(root, domContext, okHttpClient) を生成する
 *   3. 初回ロード後、HTMXのswap後など「新しいDOMが確定した」タイミングで
 *      jsEngine.runInlineScripts(rootまたはswap対象) を呼ぶ
 *
 * Rhinoの`Context`はスレッドローカルなので、呼び出しは全てメインスレッドから
 * 行うか、呼び出し側で明示的にスレッドを揃えること。
 */
class JsEngine(
    private val root: Element,
    private val domContext: JsDomContext,
    private val okHttpClient: OkHttpClient,
    capabilityBridge: com.B.b.Renderer.permissions.BrowserCapabilityBridge? = null,
) {
    val registry = JsElementRegistry(domContext)
    val window = JsWindow(capabilityBridge)
    private val globalScope: Scriptable
    private var es6Enabled = false
    private var htmxObject: Scriptable? = null
    private val seqOptimizer = HtmxSeqOptimizer()

    init {
        val ctx = Context.enter()
        try {
            ctx.optimizationLevel = -1 // Android(Dalvik/ART)ではJITコード生成非対応のため必須
            val scope = ctx.initStandardObjects()

            val jsDocument = JsDocument(root, domContext, registry)
            ScriptableObject.putProperty(scope, "document", Context.javaToJS(jsDocument, scope))
            ScriptableObject.putProperty(scope, "window", Context.javaToJS(window, scope))
            ScriptableObject.putProperty(scope, "console", Context.javaToJS(window.console, scope))
            ScriptableObject.putProperty(scope, "navigator", Context.javaToJS(window.navigator, scope))
            ScriptableObject.putProperty(scope, "screen", Context.javaToJS(window.screen, scope))
            ScriptableObject.putProperty(scope, "__seqOptimizer", Context.javaToJS(seqOptimizer, scope))
            // 注意: setTimeout/setIntervalは window.setTimeout(...) の形でのみ呼び出し可能。
            // 素の setTimeout(...) (グローバル関数扱い)はサポートしていない。
            // Rhinoのオブジェクトラップはメソッドをそのまま関数として切り離せないため、
            // 誤ってグローバルエイリアスを作るとNativeJavaObjectが関数呼び出しされて
            // TypeErrorになる。安易に足さないこと。

            // XMLHttpRequest / CustomEvent は `new` 可能なホストクラスとして別途登録する。
            JsXMLHttpRequest.install(scope, okHttpClient)
            JsCustomEventHost.install(scope)

            globalScope = scope
        } finally {
            Context.exit()
        }

        // JS側のDOM操作(innerHTML代入等)後にhtmx.process()を自動で呼べるようにする
        // (MutationObserverを実装しない代わりの手動フック)。
        domContext.onDomMutated = { mutatedElement -> notifyHtmxProcess(mutatedElement) }
    }

    /**
     * ES6+構文サポートを有効化する(Babel経由でES5へ変換してから実行する)。
     * babel.min.js(MIT License, 数MB)をassetsから読んで一度だけロードする。
     * 呼ばなければ全てのスクリプトはES5前提でそのまま実行される(未対応構文は構文エラーになる)。
     */
    fun enableEs6Support(babelJsStream: InputStream) {
        val ctx = Context.enter()
        try {
            ctx.optimizationLevel = -1
            Es6RhinoRunner.init(ctx, globalScope, babelJsStream)
            es6Enabled = true
        } catch (e: Exception) {
            window.console.error("ES6 support init failed: ${e.message}")
        } finally {
            Context.exit()
        }
    }

    /**
     * htmx.js(2.x系、XMLHttpRequestベース。fetch()ベースの4.x系は
     * このエンジンのXHRシムでは動かないので使わないこと)をロードする。
     * ロード後、htmx:beforeSwap/afterSwapをdocument.bodyで購読し、
     * HtmxSeqOptimizerに繋ぐbootstrapスクリプトを自動で仕込む。
     *
     * @param htmxSource htmx.js(非圧縮/圧縮どちらでも可)のソース文字列
     */
    fun loadHtmx(htmxSource: String) {
        evaluate(htmxSource, sourceName = "htmx.js")

        val ctx = Context.enter()
        try {
            val htmx = ScriptableObject.getProperty(globalScope, "htmx")
            if (htmx is Scriptable) {
                htmxObject = htmx
            } else {
                window.console.error("htmx.js を評価しても htmx グローバルが見つかりませんでした")
                return
            }
        } finally {
            Context.exit()
        }

        // beforeSwap/afterSwapをKotlin側のHtmxSeqOptimizerに橋渡しする最小限のglueコード。
        // document.bodyへのイベント委譲を使うことで、swap対象がどの要素であっても拾える。
        evaluate(
            """
            document.body.addEventListener('htmx:beforeSwap', function(evt) {
                __seqOptimizer.captureBeforeSwap(evt.target);
            });
            document.body.addEventListener('htmx:afterSwap', function(evt) {
                __seqOptimizer.applyAfterSwap(evt.target);
            });
            """.trimIndent(),
            sourceName = "htmx-seq-optimizer-glue",
        )
    }

    /** htmx.jsがロード済みなら`htmx.process(element)`を呼ぶ。未ロードなら何もしない。 */
    private fun notifyHtmxProcess(element: Element) {
        val htmx = htmxObject ?: return
        val ctx = Context.enter()
        try {
            val jsElement = registry.wrap(element)
            ScriptableObject.callMethod(ctx, htmx, "process", arrayOf(jsElement))
        } catch (e: Exception) {
            window.console.error("htmx.process() failed: ${e.message}")
        } finally {
            Context.exit()
        }
    }

    /**
     * device側(ShortcutApi/DeviceScriptEngine)からのみ呼ぶことを想定した注入口。
     * DeviceToContentBridge経由で、既にサニタイズ済みの値のみがここに渡ってくる前提。
     * page由来のcontent JS(信頼できない)からは、このメソッド自体に到達する経路が無い
     * (ScriptableObjectとしてscopeへ公開していないため、あくまでKotlin側からのみ呼べる)。
     */
    fun injectGlobal(name: String, value: Any?) {
        val ctx = Context.enter()
        try {
            ScriptableObject.putProperty(globalScope, name, Context.javaToJS(value, globalScope))
        } finally {
            Context.exit()
        }
    }

    /** 任意のJSコード文字列を実行する */
    fun evaluate(script: String, sourceName: String = "inline") {
        com.B.b.Renderer.debug.BehaviorAuditLog.record(
            com.B.b.Renderer.debug.BehaviorAuditLog.Category.JS_EVAL,
            "eval start: $sourceName (${script.length} chars)",
        )
        val ctx = Context.enter()
        try {
            ctx.optimizationLevel = -1
            val actualScript = if (es6Enabled) {
                try {
                    Es6RhinoRunner.transpileToES5(ctx, script)
                } catch (e: Exception) {
                    // 変換失敗時はES5前提で素のまま実行を試みる(既にES5なら問題なく通る)
                    script
                }
            } else {
                script
            }
            ctx.evaluateString(globalScope, actualScript, sourceName, 1, null)
        } catch (e: Exception) {
            window.console.error("JS error in $sourceName: ${e.message}")
        } finally {
            Context.exit()
        }
    }

    /**
     * root配下の<script>タグ(src属性なし、インラインのみ)を上から順に実行する。
     * 外部script(src指定)の取得はEngine側のHTTPクライアントに委譲する必要があるため、
     * ここでは意図的に対象外にしている。
     */
    fun runInlineScripts(root: Element) {
        val scripts = root.findAll { it.tag == "script" && !it.attributes.containsKey("src") }
        scripts.forEachIndexed { index, scriptElement ->
            val code = scriptElement.children.filterIsInstance<TextNode>().joinToString("") { it.data }
            if (code.isNotBlank()) {
                evaluate(code, sourceName = "inline-script-$index")
            }
        }
    }

    fun dispose() {
        window.cancelAll()
    }
}
