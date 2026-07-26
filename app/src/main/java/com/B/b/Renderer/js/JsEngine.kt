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
     * 重要: htmx.js本体はsanitizeConstLetForRhino()を通してから評価する。
     * Rhino(1.9.1)はlet/constのブロックスコープ実装が仕様通り完全ではなく、
     * 別々の関数/ブロックスコープにある同名のconst宣言(minify後のhtmx.jsに
     * 複数箇所ある短い変数名、例: i)を誤って「同一スコープでの再宣言」と
     * 判定し"redeclaration of const ..."エラーを起こすことを2026-07に確認した
     * (Babelを完全に無効化した状態でも再現したため、Babel起因ではなくRhino
     * 自体の既知の制限と判断)。htmx.jsは信頼できる単一のライブラリソースなので、
     * const/letをvarへ機械的に置換して回避する(任意サイトの未信頼スクリプトに
     * 対してはこの変換を行わない)。
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
        evaluateRaw(
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

        val patchedHtmxSource = patchSpreadSyntaxForRhino(patchEs2020SyntaxForRhino(htmxSource))
        val sanitizedHtmxSource = sanitizeConstLetForRhino(patchedHtmxSource)
        evaluateRaw(sanitizedHtmxSource, sourceName = "htmx.js")

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
        evaluateRaw(
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

    /**
     * htmx.js(2.0.10、vendored)がES2020構文(オプショナルチェイニング`?.`、
     * Null合体演算子`??`)を2箇所使用しており、Rhino(1.9.1)がこれらの構文を
     * パースできず"syntax error"になることを2026-07に確認した
     * (const/letのvar置換だけでは解決せず、この問題が真因だった)。
     *
     * 汎用的なES2020→ES5トランスパイルは行わず、htmx.js 2.0.10で実際に
     * 使われている2箇所をピンポイントで文字列置換するだけの限定パッチとする:
     * - `p?.swapDelay` → `(p&&p.swapDelay)` (pはオブジェクトかnull/undefinedの
     *   いずれかである前提のコードなので、`&&`による短絡評価で意味的に同等)
     * - `s.push??"true"` → `(s.push!=null?s.push:"true")` (三項演算子による
     *   Null合体演算子の展開)
     *
     * htmx.jsのバージョンを上げる場合、この2箇所の置換対象文字列が変わって
     * いないか、また新たに`?.`/`??`を使う箇所が増えていないか再確認すること。
     */
    private fun patchEs2020SyntaxForRhino(source: String): String {
        var result = source
        result = result.replace("p?.swapDelay", "(p&&p.swapDelay)")
        result = result.replace("s.push??\"true\"", "(s.push!=null?s.push:\"true\")")
        return result
    }

    /**
     * htmx.js(2.0.10、vendored)がES6のスプレッド構文(`...`)を3箇所使用しており、
     * Rhino(1.9.1)がパースできず"syntax error"になることを2026-07に確認した
     * (ES2020パッチ適用後もsyntax errorが続いたため、同じ手法で追加調査して発見)。
     * htmx.js 2.0.10で実際に使われている3箇所をピンポイントで置換する:
     * - `i.push(...F(...))` → `i.push.apply(i,F(...))` (関数呼び出し引数展開を
     *   Function.prototype.applyへ書き換え、意味的に同等)
     * - `r.push(...ve(i,n))` → 同上のパターン
     * - `for(const t of[...e.children])` → `for(const t of Array.prototype.slice.call(e.children))`
     *   (配列リテラル内でのイテラブル展開を、HTMLCollection相手のArray化に書き換え)
     *
     * htmx.jsのバージョンを上げる場合、この3箇所の置換対象文字列が変わっていないか、
     * また新たにスプレッド構文を使う箇所が増えていないか再確認すること。
     */
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

    /**
     * Rhino(1.9.1)のlet/constブロックスコープ実装の既知の制限を回避するため、
     * ソース中のトップレベルトークンとしての"const"/"let"を"var"へ機械的に
     * 置換する簡易JSトークナイザ。以下を正しく識別してスキップ(置換対象外に)する:
     *
     * - 行コメント(スラッシュ2つによる1行コメント)・ブロックコメント(アスタリスクで囲む複数行コメント)
     * - 文字列リテラル('...'/"..."/`...`、エスケープ考慮)
     * - 正規表現リテラル(/.../ )。直前の意味のあるトークンから「除算演算子」か
     *   「正規表現の開始」かを判定する(一般的なJSトークナイザの手法)。
     *   この判定が無いと、正規表現内の引用符(例: /['"]/ のような文字クラス)を
     *   文字列の開始と誤認し、以降のソース全体を読み違えて構文を破壊する
     *   (2026-07、最初のconst/let置換実装で実際に発生した不具合)。
     *
     * htmx.js専用の変換であり、ページ側の任意スクリプト(未信頼)には適用しない。
     */
    private fun sanitizeConstLetForRhino(source: String): String {
        val sb = StringBuilder(source.length)
        val n = source.length
        var i = 0
        // 直前の意味のあるトークンの直後に'/'が来た場合、正規表現の開始として
        // 解釈してよいかどうか。false の場合は除算演算子とみなす。
        var regexAllowed = true
        val identBuffer = StringBuilder()

        fun flushIdent() {
            if (identBuffer.isEmpty()) return
            val word = identBuffer.toString()
            when (word) {
                "const", "let" -> sb.append("var")
                else -> sb.append(word)
            }
            // 識別子/キーワードの直後は基本的に除算(division)の文脈だが、
            // return/typeof/instanceof等のキーワードの後は式が続くため正規表現を許可する。
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
                    // コメントは透過的(regexAllowedは変更しない)
                }
                c == '/' && i + 1 < n && source[i + 1] == '*' -> {
                    // "*" + "/" という2文字の並びを検索する。文字列リテラルとして
                    // 直接書くと(コピー時にツールがコメント終端と誤認して破損する
                    // 事例が過去にあったため)、1文字ずつの比較で判定する。
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
                        if (rc == '\n') break // 未終端の異常系。安全側に打ち切る
                        if (rc == '[') inCharClass = true
                        if (rc == ']') inCharClass = false
                        i++
                        if (rc == '/' && !inCharClass) break
                    }
                    // 正規表現直後のフラグ(g/i/m/s/u/y等)も読み飛ばす
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
                    // 空白はトークン境界に影響しない(regexAllowedは維持)
                }
                c == ')' || c == ']' -> {
                    sb.append(c)
                    regexAllowed = false
                    i++
                }
                else -> {
                    // その他の記号(演算子・カンマ・波括弧等)の後は式の開始として
                    // 正規表現を許可しておく(過剰許可側に倒すが、除算誤判定より安全)。
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

    /**
     * 任意のJSコード文字列を実行する。es6Enabled時はBabel経由でES5へ変換してから実行する
     * (ページ側の<script>は任意のES6+構文を含みうるため)。
     */
    fun evaluate(script: String, sourceName: String = "inline") {
        evaluateInternal(script, sourceName, allowTranspile = true)
    }

    /**
     * Babelトランスパイルを経由せず、常にRhinoへ生のまま渡す版。
     * htmx.js本体、および本ファイル内で生成する小さなグルーコード用
     * (詳細はloadHtmx()のコメント参照)。
     */
    private fun evaluateRaw(script: String, sourceName: String) {
        evaluateInternal(script, sourceName, allowTranspile = false)
    }

    private fun evaluateInternal(script: String, sourceName: String, allowTranspile: Boolean) {
        com.B.b.Renderer.debug.BehaviorAuditLog.record(
            com.B.b.Renderer.debug.BehaviorAuditLog.Category.JS_EVAL,
            "eval start: $sourceName (${script.length} chars)",
        )
        val ctx = Context.enter()
        try {
            ctx.optimizationLevel = -1
            val actualScript = if (allowTranspile && es6Enabled) {
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

    companion object {
        /** これらの単語の直後の'/'は正規表現の開始として扱う(除算ではない)。 */
        private val REGEX_ALLOWED_AFTER_WORD = setOf(
            "return", "typeof", "instanceof", "in", "of", "new", "delete", "void",
            "throw", "case", "do", "else", "yield", "await",
            "const", "let", "var", "function", "if", "while", "for", "switch",
        )
    }
}
