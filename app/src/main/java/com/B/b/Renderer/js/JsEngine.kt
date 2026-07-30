
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
            ScriptableObject.putProperty(scope, "location", Context.javaToJS(window.location, scope))
            ScriptableObject.putProperty(scope, "localStorage", Context.javaToJS(window.localStorage, scope))
            ScriptableObject.putProperty(scope, "sessionStorage", Context.javaToJS(window.sessionStorage, scope))
            ScriptableObject.putProperty(scope, "__seqOptimizer", Context.javaToJS(seqOptimizer, scope))
            ScriptableObject.putProperty(scope, "__hxOnScanner", Context.javaToJS(hxOnScanner, scope))

            JsXMLHttpRequest.install(scope, okHttpClient)
            JsCustomEventHost.install(scope)

            globalScope = scope
        } finally {
            Context.exit()
        }

        domContext.onDomMutated = { mutatedElement -> notifyHtmxProcess(mutatedElement) }
    }

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
     * htmx.js(2.x系)をロードする。
     *
     * 2026-07、起動速度調査の結果、ページ遷移・新規タブのたびに`JsEngine`が作り直され、
     * その都度このメソッドがhtmx.js(50000文字超)のES2020/スプレッド構文パッチ・
     * const/letサニタイズ(文字単位トークナイザ)・Rhinoでのパース&コンパイルを
     * フルでやり直していたことが、体感の起動遅延の主因と判明した。
     * htmxSourceはassetsにバンドルされた固定内容(通常はページごとに変わらない)なので、
     * パッチ済みソースをRhinoでコンパイルした`Script`オブジェクトをプロセス全体で
     * キャッシュし、2回目以降はパッチ処理・コンパイルを丸ごとスキップしてexec()のみ行う
     * (`Script`はscopeに紐付かないため、任意のJsEngineインスタンスのglobalScopeに対して
     * 使い回せる)。キャッシュキーは元のhtmxSource文字列のhashCodeなので、万一assets側の
     * htmx.jsが差し替わった場合は自動的にキャッシュミスして再コンパイルされる。
     *
     * 冪等: 同じJsEngineインスタンスに対して複数回呼ばれても、2回目以降は
     * 何もしない(htmx.jsはトップレベルでconst/letを使うため、同じscopeに
     * 二度execすると"redeclaration"エラーになるのを防ぐため)。
     *
     * @param htmxSource htmx.js(非圧縮/圧縮どちらでも可)のソース文字列
     */
    fun loadHtmx(htmxSource: String) {
        if (htmxLoaded) {
            window.console.warn("loadHtmx() は既に読み込み済みのため無視されました(二重ロード防止)")
            return
        }

        evaluateRawCached(XPATH_POLYFILL_SOURCE, sourceName = "xpath-evaluator-polyfill")

        val compiledHtmx = compileHtmxCached(htmxSource)
        execCompiled(compiledHtmx, sourceName = "htmx.js", sourceLength = htmxSource.length)

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

        evaluateRawCached(SEQ_OPTIMIZER_GLUE_SOURCE, sourceName = "htmx-seq-optimizer-glue")

        htmxLoaded = true
    }

    /**
     * パッチ済みhtmx.jsのコンパイル結果をプロセス全体で共有するキャッシュから取得する。
     * キャッシュミス時のみ、ES2020/スプレッド構文パッチ・const/letサニタイズ・
     * Rhinoコンパイルをこの場で行い、結果を格納する。
     */
    private fun compileHtmxCached(htmxSource: String): org.mozilla.javascript.Script {
        val rawHash = htmxSource.hashCode()
        cachedHtmxCompile?.let { (hash, script) ->
            if (hash == rawHash) return script
        }
        synchronized(htmxCompileLock) {
            cachedHtmxCompile?.let { (hash, script) ->
                if (hash == rawHash) return script
            }
            val patched = sanitizeConstLetForRhino(
                patchSpreadSyntaxForRhino(patchEs2020SyntaxForRhino(htmxSource)),
            )
            val ctx = Context.enter()
            val script = try {
                ctx.optimizationLevel = -1
                ctx.compileString(patched, "htmx.js", 1, null)
            } finally {
                Context.exit()
            }
            cachedHtmxCompile = rawHash to script
            return script
        }
    }

    private fun patchEs2020SyntaxForRhino(source: String): String {
        var result = source
        result = result.replace("p?.swapDelay", "(p&&p.swapDelay)")
        result = result.replace("s.push??\"true\"", "(s.push!=null?s.push:\"true\")")
        return result
    }

    private fun patchSpreadSyntaxForRhino(source: String): String {
        var result = source
        result = result.replace(
            "i.push(...F(u.querySelectorAll(e)))",
            "i.push.apply(i,F(u.querySelectorAll(e)))",
        )
        result = result.replace(
            "r.push(...ve(i,n))",
            "r.push.apply(r,ve(i,n))",
        )
        result = result.replace(
            "for(const t of[...e.children]){",
            "for(const t of Array.prototype.slice.call(e.children)){",
        )
        return result
    }

    private fun sanitizeConstLetForRhino(source: String): String {
        val sb = StringBuilder(source.length)
        val n = source.length
        var i = 0
        var regexAllowed = true
        val identBuffer = StringBuilder()

        fun flushIdent() {
            if (identBuffer.isEmpty()) return
            val word = identBuffer.toString()
            when (word) {
                "const", "let" -> sb.append("var")
                else -> sb.append(word)
            }
            regexAllowed = word in REGEX_ALLOWED_AFTER_WORD
            identBuffer.setLength(0)
        }

        while (i < n) {
            val c = source[i]

            if (isIdentifierChar(c)) {
                identBuffer.append(c)
                i++
                continue
            }
            flushIdent()

            when {
                c == '/' && i + 1 < n && source[i + 1] == '/' -> {
                    val end = source.indexOf('\n', i).let { if (it == -1) n else it }
                    sb.append(source, i, end)
                    i = end
                }
                c == '/' && i + 1 < n && source[i + 1] == '*' -> {
                    var end = i + 2
                    var closed = false
                    while (end < n - 1) {
                        if (source[end] == '*' && source[end + 1] == '/') {
                            end += 2
                            closed = true
                            break
                        }
                        end++
                    }
                    if (!closed) end = n
                    sb.append(source, i, end)
                    i = end
                }
                c == '/' && regexAllowed -> {
                    val start = i
                    i++
                    var inCharClass = false
                    while (i < n) {
                        val rc = source[i]
                        if (rc == '\\' && i + 1 < n) {
                            i += 2
                            continue
                        }
                        if (rc == '\n') break
                        if (rc == '[') inCharClass = true
                        if (rc == ']') inCharClass = false
                        i++
                        if (rc == '/' && !inCharClass) break
                    }
                    while (i < n && source[i].isLetter()) i++
                    sb.append(source, start, i)
                    regexAllowed = false
                }
                c == '\'' || c == '"' || c == '`' -> {
                    val quote = c
                    val start = i
                    i++
                    while (i < n) {
                        val sc = source[i]
                        if (sc == '\\' && i + 1 < n) {
                            i += 2
                            continue
                        }
                        i++
                        if (sc == quote) break
                    }
                    sb.append(source, start, i)
                    regexAllowed = false
                }
                c.isWhitespace() -> {
                    sb.append(c)
                    i++
                }
                c == ')' || c == ']' -> {
                    sb.append(c)
                    regexAllowed = false
                    i++
                }
                else -> {
                    sb.append(c)
                    regexAllowed = true
                    i++
                }
            }
        }
        flushIdent()
        return sb.toString()
    }

    private fun isIdentifierChar(c: Char): Boolean = c.isLetterOrDigit() || c == '_' || c == '$'

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

    fun injectGlobal(name: String, value: Any?) {
        val ctx = Context.enter()
        try {
            ScriptableObject.putProperty(globalScope, name, Context.javaToJS(value, globalScope))
        } finally {
            Context.exit()
        }
    }

    /**
     * 任意のJSコード文字列を実行する。es6Enabled時はBabel経由でES5へ変換してから実行する
     * (ページ側の<script>は任意のES6+構文を含みうるため、こちらはコンパイルキャッシュ対象外。
     * ページごとに内容が異なるインラインスクリプトを毎回キャッシュしても再利用機会が薄く、
     * かつBabel変換結果もページ内容依存のため、キャッシュの効果が薄い)。
     */
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
     * 固定リテラルのグルーコード(xpathポリフィル・seqOptimizer glue)用。
     * ソース内容が変わらない前提でコンパイル結果をプロセス全体でキャッシュし、
     * 2回目以降はコンパイルをスキップしてexec()のみ行う。
     */
    private fun evaluateRawCached(script: String, sourceName: String) {
        val compiled = compileLiteralCached(script, sourceName)
        execCompiled(compiled, sourceName, script.length)
    }

    private fun compileLiteralCached(script: String, sourceName: String): org.mozilla.javascript.Script {
        val hash = script.hashCode()
        literalScriptCache[sourceName]?.let { (cachedHash, cachedScript) ->
            if (cachedHash == hash) return cachedScript
        }
        val ctx = Context.enter()
        val compiled = try {
            ctx.optimizationLevel = -1
            ctx.compileString(script, sourceName, 1, null)
        } finally {
            Context.exit()
        }
        literalScriptCache[sourceName] = hash to compiled
        return compiled
    }

    private fun execCompiled(compiled: org.mozilla.javascript.Script, sourceName: String, sourceLength: Int) {
        com.B.b.Renderer.debug.BehaviorAuditLog.record(
            com.B.b.Renderer.debug.BehaviorAuditLog.Category.JS_EVAL,
            "eval start: $sourceName ($sourceLength chars, compiled-cache)",
        )
        val ctx = Context.enter()
        try {
            ctx.optimizationLevel = -1
            compiled.exec(ctx, globalScope)
        } catch (e: Exception) {
            window.console.error("JS error in $sourceName: ${e.message}")
        } finally {
            Context.exit()
        }
    }

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

    companion object {
        private val REGEX_ALLOWED_AFTER_WORD = setOf(
            "return", "typeof", "instanceof", "in", "of", "new", "delete", "void",
            "throw", "case", "do", "else", "yield", "await",
            "const", "let", "var", "function", "if", "while", "for", "switch",
        )

        /**
         * htmx.js固有のコンパイル済みキャッシュ(パッチ処理込みで1本化)。
         * synchronized化しているのは、複数タブを並行して開いた際に同時にloadHtmx()が
         * 呼ばれるケース(EngineActivity.openNewTab等)での二重コンパイルを防ぐため。
         */
        @Volatile
        private var cachedHtmxCompile: Pair<Int, org.mozilla.javascript.Script>? = null
        private val htmxCompileLock = Any()

        /**
         * xpathポリフィル・seqOptimizer glue等、本ファイル内のリテラル文字列用の
         * コンパイル済みキャッシュ。sourceNameをキーにする(ソース自体が定数リテラルなので
         * 事実上hashCodeチェックは保険程度)。
         */
        private val literalScriptCache = java.util.concurrent.ConcurrentHashMap<String, Pair<Int, org.mozilla.javascript.Script>>()

        private val XPATH_POLYFILL_SOURCE = """
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
        """.trimIndent()

        private val SEQ_OPTIMIZER_GLUE_SOURCE = """
            document.body.addEventListener('htmx:beforeSwap', function(evt) {
                __seqOptimizer.captureBeforeSwap(evt.target);
            });
            document.body.addEventListener('htmx:afterSwap', function(evt) {
                __seqOptimizer.applyAfterSwap(evt.target);
            });
        """.trimIndent()
    }
}