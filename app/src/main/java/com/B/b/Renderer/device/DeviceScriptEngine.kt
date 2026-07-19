package com.B.b.Renderer.device

import android.app.Activity
import android.app.AlertDialog
import android.os.Handler
import android.os.Looper
import android.util.Log
import android.widget.Toast
import org.mozilla.javascript.BaseFunction
import org.mozilla.javascript.Context
import org.mozilla.javascript.ScriptableObject
import org.mozilla.javascript.Undefined
import java.util.concurrent.Executors

/**
 * ショートカット(マクロ)スクリプトの実行エンジン。
 *
 * DECISION_device_engine_rhino.mdにより、device側もcontent側(JsEngine)と同じRhinoに
 * 一本化した。旧BshShortcutEngine(bsh)を置き換える。
 *
 * 安全設計はbsh版から変更しない: `Context.javaToJS(activity, scope)`のような
 * Activity自体への無制限アクセスは絶対に公開しない。スコープに注入するのは
 * `shortcuts`(ShortcutApiインスタンス)と、下記の少数の安全な便利関数のみであり、
 * `Packages`(汎用Javaアクセス経路)や`java`/`ctx`等の汎用オブジェクトも
 * 一切スコープに置かない。ショートカットスクリプトから触れるのは
 * ここで明示的に定義した窓口だけになる。
 *
 * 便利関数(print/popup/alert/runOnUIThread)は、Engineセッション側が
 * JAScript方式(`ctx`フルアクセス)で実装していたDeviceStdlibの発想を踏襲しつつ、
 * SAFETY_AND_SCOPE_BOUNDARIES.mdの基準(限定APIのみ公開)に沿うよう書き直したもの。
 * Activity自体は各関数内のプライベートな実装詳細としてのみ使い、JS側に渡すのは
 * 関数呼び出しの結果だけで、Activity参照そのものは一切JS側へ渡らない。
 *
 * 実行は必ずバックグラウンドスレッドで行う。ShortcutApi.waitForElementのような
 * ブロッキング待機がUIスレッドを固めないようにするため(bsh版と同じ理由)。
 */
class DeviceScriptEngine(
    private val activity: Activity,
    private val shortcutApi: ShortcutApi,
) {
    private val executor = Executors.newSingleThreadExecutor()
    private val mainHandler = Handler(Looper.getMainLooper())
    private val savedShortcuts = mutableMapOf<String, String>()

    fun registerShortcut(name: String, script: String) {
        savedShortcuts[name] = script
    }

    fun removeShortcut(name: String) {
        savedShortcuts.remove(name)
    }

    fun listShortcuts(): Set<String> = savedShortcuts.keys

    /** RjsShortcutScannerが検出した.rjsファイル群をまとめて登録する。 */
    fun registerAll(shortcuts: Map<String, String>) {
        savedShortcuts.putAll(shortcuts)
    }

    fun runShortcut(
        name: String,
        onSuccess: (Any?) -> Unit = {},
        onError: (Throwable) -> Unit = {},
    ) {
        val script = savedShortcuts[name]
        if (script == null) {
            mainHandler.post { onError(IllegalArgumentException("Unknown shortcut: $name")) }
            return
        }
        runScript(script, onSuccess, onError)
    }

    /** 保存済みショートカットとして登録せず、その場のスクリプト文字列を直接実行する */
    fun runAdHoc(
        script: String,
        onSuccess: (Any?) -> Unit = {},
        onError: (Throwable) -> Unit = {},
    ) = runScript(script, onSuccess, onError)

    private fun runScript(
        script: String,
        onSuccess: (Any?) -> Unit,
        onError: (Throwable) -> Unit,
    ) {
        executor.execute {
            val ctx = Context.enter()
            try {
                ctx.optimizationLevel = -1 // Android(Dalvik/ART)ではJITコード生成非対応のため必須(JsEngineと同じ理由)
                // 標準オブジェクトのみのまっさらなscope。JsEngine側のdocument/window等は
                // 意図的に注入しない(device側はページDOMではなくShortcutApi経由でのみ操作する)。
                val scope = ctx.initStandardObjects()
                ScriptableObject.putProperty(scope, "shortcuts", Context.javaToJS(shortcutApi, scope))
                installConvenienceGlobals(scope)

                val result = ctx.evaluateString(scope, script, "device-shortcut", 1, null)
                val unwrapped = if (result is org.mozilla.javascript.Undefined) null else result
                mainHandler.post { onSuccess(unwrapped) }
            } catch (e: Exception) {
                mainHandler.post { onError(e) }
            } finally {
                Context.exit()
            }
        }
    }

    /**
     * print/popup/alert/runOnUIThreadのみを個別に登録する。`ctx`という単一の
     * 万能オブジェクトは決して置かない(限定APIの原則。SAFETY_AND_SCOPE_BOUNDARIES.md参照)。
     */
    private fun installConvenienceGlobals(scope: ScriptableObject) {
        fun define(name: String, fn: (Array<out Any?>) -> Any?) {
            val callable = object : BaseFunction() {
                override fun call(
                    cx: Context,
                    callScope: org.mozilla.javascript.Scriptable,
                    thisObj: org.mozilla.javascript.Scriptable,
                    args: Array<out Any?>,
                ): Any? = fn(args) ?: Undefined.instance
            }
            ScriptableObject.putProperty(scope, name, callable)
        }

        define("print") { args ->
            Log.d("ShortcutScript", args.joinToString(" "))
            null
        }

        define("popup") { args ->
            val message = args.getOrNull(0)?.toString() ?: ""
            activity.runOnUiThread { Toast.makeText(activity, message, Toast.LENGTH_SHORT).show() }
            null
        }

        define("alert") { args ->
            val message = args.getOrNull(0)?.toString() ?: ""
            val title = args.getOrNull(1)?.toString() ?: ""
            activity.runOnUiThread {
                AlertDialog.Builder(activity)
                    .setTitle(title)
                    .setMessage(message)
                    .setPositiveButton("OK", null)
                    .show()
            }
            null
        }

        // JS Functionをメインスレッドで呼び直す。deviceスレッド上でUI操作が必要な場合の窓口。
        define("runOnUIThread") { args ->
            val callback = args.getOrNull(0) as? org.mozilla.javascript.Function
            activity.runOnUiThread {
                val innerCx = Context.enter()
                try {
                    callback?.call(innerCx, scope, scope, emptyArray())
                } finally {
                    Context.exit()
                }
            }
            null
        }
    }

    fun shutdown() {
        executor.shutdownNow()
    }
}

