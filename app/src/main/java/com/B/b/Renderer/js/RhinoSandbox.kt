package com.B.b.Renderer.js

import org.mozilla.javascript.ClassShutter
import org.mozilla.javascript.Context
import org.mozilla.javascript.ContextFactory
import org.mozilla.javascript.Scriptable
import org.mozilla.javascript.ScriptableObject

/**
 * Rhinoの「素の状態」が持つ、Java全体への裏口を塞ぐための共有ユーティリティ。
 *
 * DECISION_shortcut_api_boundary.md / SAFETY_AND_SCOPE_BOUNDARIES.mdで定めた
 * 「危険なことができるAPIはそもそも公開しない(実行時に動的に広がらない、
 * 静的な絞り込み)」という原則は、`ShortcutApi`(限定APIのみ公開)自体は満たしていたが、
 * Rhinoの`Context.initStandardObjects()`が標準で追加する以下のグローバルが
 * そのまま残っていたため、原則が実際には成立していなかった(2026-08、デプロイ時の
 * 安全性チェックで指摘・却下された):
 *
 *   - `Packages` / `java` / `javax` / `org` / `com` / `edu` / `net`
 *     → ページ由来の任意のJS(JsEngine側)や、開発者が用意した`.rjs`スクリプト
 *       (DeviceScriptEngine側)から、`new java.io.File(...)`のように任意のJavaクラスへ
 *       直接到達できてしまう
 *   - 既に注入済みのJavaオブジェクト(`document`/`window`/`shortcuts`等)からの
 *     `obj.getClass().forName("java.io.File")`のような、リフレクション経由の到達
 *     → グローバルを消すだけでは塞げない別経路
 *
 * このユーティリティは上記2経路をまとめて塞ぐ:
 *   1. [stripJavaGlobals] … initStandardObjects()直後のscopeから明示的に削除する
 *   2. [ensureClassShutterInstalled] … 全クラスを既定拒否するClassShutterを、
 *      プロセス全体のContextFactoryとしてインストールする(Rhino自身の公式な
 *      セキュリティ機構で、リフレクション経由の到達も含めて塞げる)
 *
 * このアプリでは、Java機能は必ずJsWindow/JsNavigator/ShortcutApi等、事前に
 * Kotlin側で吟味・注入したオブジェクト経由でのみ公開する設計のため、スクリプト側が
 * 任意のJavaクラス名を解決できる必要はどこにも無い(=全クラス拒否で問題が起きない)。
 */
object RhinoSandbox {

    @Volatile
    private var classShutterInstalled = false

    /**
     * 全クラスを拒否するClassShutterを、プロセス全体のContextFactoryとしてインストールする。
     * `ContextFactory.initGlobal()`はプロセス内で「まだ1つもContextが作られていない」
     * 最初のタイミングでしか呼べない(2回目の呼び出しはIllegalStateException)ため、
     * 複数タブ=複数JsEngine、および別途DeviceScriptEngineからも呼ばれうる本メソッドは
     * 必ず`Context.enter()`より前に呼び、かつ多重初期化しないようガードする。
     */
    @Synchronized
    fun ensureClassShutterInstalled() {
        if (classShutterInstalled) return
        ContextFactory.initGlobal(
            object : ContextFactory() {
                override fun makeContext(): Context {
                    val ctx = super.makeContext()
                    ctx.setClassShutter(DenyAllClassShutter)
                    return ctx
                }
            },
        )
        classShutterInstalled = true
    }

    private object DenyAllClassShutter : ClassShutter {
        override fun visibleToScripts(fullClassName: String): Boolean = false
    }

    /**
     * initStandardObjects()が標準で追加するグローバルの「裏口」を削除する。
     * ClassShutterだけでも新規のクラス解決は防げるが、こちらも合わせて消しておくことで
     * 「そもそもscript側から見えない」状態にする(意図が読み取りやすくなる、という
     * 副次効果もある)。
     */
    fun stripJavaGlobals(scope: Scriptable) {
        JAVA_GLOBAL_NAMES.forEach { name ->
            try {
                ScriptableObject.deleteProperty(scope, name)
            } catch (_: Exception) {
                // 環境(Rhinoバージョン)によって元々存在しない/削除不可な場合があるため無視してよい
            }
        }
    }

    private val JAVA_GLOBAL_NAMES = listOf(
        "Packages", "java", "javax", "org", "com", "edu", "net",
        "JavaAdapter", "JavaImporter", "importClass", "importPackage",
    )
}
