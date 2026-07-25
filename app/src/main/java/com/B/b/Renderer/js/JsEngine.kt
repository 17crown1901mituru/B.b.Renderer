
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
 *
 * 重要: 1つの`JsEngine`インスタンス = 1つの`globalScope`。ページ遷移のたびに
 * `JsEngine`を作り直さないのであれば、`loadHtmx()`は1回のみ呼ぶこと。
 * htmx.jsはトップレベルで`const`/`let`宣言を使っており、同じscopeに対して
 * 二重にevalすると"redeclaration of const ..."エラーになる(下記loadHtmx()の
 * 冪等ガードにより2回目以降は自動的にスキップされるが、根本的には呼び出し側が
 * ページ遷移ごとに新しいJsEngineを作るか、loadHtmx()を1回しか呼ばない設計に
 * すべき)。新しいDOM(ページ遷移後のroot)にhtmxを効かせたい場合は、
 * loadHtmx()の再呼び出しではなく、そのrootに対してhtmx.process()相当
 * (=notifyHtmxProcess経由、onDomMutated)を呼ぶこと。
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
    private var htmxLoaded = false
    private val seqOptimizer = HtmxSeqOptimizer()
    private val hxOnScanner = HxOnAttributeScanner(registry)

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
            // htmx.js等、ページ側JSは`window.location`ではなく素の`location`を直接参照することが
            // 多い(ブラウザのグローバルスコープでは`location`===`window.location`のため)。
            // console/navigator/screenと同様にここでも明示登録しないとReferenceErrorになる
            // (このバグは過去に一度直した経緯があるが、別セッションの変更が本ファイルには
            // 反映されていなかった。2026-07再発分)。
            ScriptableObject.putProperty(scope, "location", Context.javaToJS(window.location, scope))
            ScriptableObject.putProperty(scope, "localStorage", Context.javaToJS(window.localStorage, scope))
            ScriptableObject.putProperty(scope, "sessionStorage", Context.javaToJS(window.sessionStorage, scope))
            ScriptableObject.putProperty(scope, "__seqOptimizer", Context.javaToJS(seqOptimizer, scope))
            ScriptableObject.putProperty(scope, "__hxOnScanner", Context.javaToJS(hxOnScanner, scope))
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
     * ロード前にXPathEvaluatorの限定ポリフィルを注入し(htmx.jsがトップレベル評価時に
     * `new XPathEvaluator`を使うため、htmxSource本体より先に評価する必要がある)、
     * ロード後、htmx:beforeSwap/afterSwapをdocument.bodyで購読し、
     * HtmxSeqOptimizerに繋ぐbootstrapスクリプトを自動で仕込む。
     *
     * 冪等: 同じJsEngineインスタンスに対して複数回呼ばれても、2回目以降は
     * 何もしない(htmx.jsはトップレベルでconst/letを使うため、同じscopeに
     * 二度evalすると"redeclaration"エラーになるのを防ぐため)。呼び出し側が
     * ページ遷移ごとにJsEngineを作り直さない設計の場合、このガードにより
     * 実害は防げるが、新しいページのDOMにhtmxを効かせるには別途
     * htmx.process()相当(onDomMutated経由)を呼ぶ必要がある。
     *
     * @param htmxSource htmx.js(非圧縮/圧縮どちらでも可)のソース文字列
     */
    fun loadHtmx(htmxSource: String) {
        if (htmxLoaded) {
            window.console.warn("loadHtmx() は既に読み込み済みのため無視されました(二重ロード防止)")
            return
        }

        // htmx.jsは `hx-on:`/`data-hx-on:`/`hx-on-`/`data-hx-on-` 属性を持つ子孫要素の
        // 探索に(new XPathEvaluator).createExpression(...)を使う。このエンジンは
        // 汎用XPathを実装していないため、この1パターン専用の限定ポリフィルで代替する
        // (実体はHxOnAttributeScanner.kt、Kotlin側で子孫を辿って属性名プレフィックス
        // 一致を見るだけ)。htmx側が他のXPath式を使うようになった場合はここを拡張すること。
        evaluate(
            """
            function XPathEvaluator() {}
            XPathEvaluator.prototype.createExpression = function(exprString) {
                return {
                    evaluate: function(contextNode) {
                        var __results = __hxOnScanner.scan(contextNode);
                        var __idx = 0;
                        return {
                            iterateNext: function() {
                                if (__idx < __results.length) {
                                    return __results[__idx++];
                                }
                                return null;
                            }
                        };
                    }
                };
            };
            """.trimIndent(),
            sourceName = "xpath-evaluator-polyfill",
        )

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

        htmxLoaded = true
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