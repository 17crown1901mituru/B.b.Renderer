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
