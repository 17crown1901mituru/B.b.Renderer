package com.B.b.Renderer.device

import com.B.b.Renderer.core.Element
import com.B.b.Renderer.js.JsDomContext
import com.B.b.Renderer.js.JsElement
import com.B.b.Renderer.js.JsElementRegistry

/**
 * device側(アプリ内ショートカット)スクリプトから呼べる操作の一覧。
 *
 * 旧bsh/ShortcutApi.ktからの移植(DECISION_device_engine_rhino.md参照)。
 * 設計方針は変わらない: `ctx`(Activityそのもの)のような無制限アクセスは公開せず、
 * ここで定義したメソッドだけがショートカットスクリプトから触れる唯一の窓口になる。
 * bsh時代と同様、Rhino版でも生のOkHttpClient/Reflection等は一切公開しない。
 *
 * DeviceScriptEngineがRhinoの`Context.javaToJS(this, scope)`経由でこのインスタンスを
 * 単一のグローバル(`shortcuts`)として注入する。LiveConnectはpublicメンバをそのまま
 * 公開する方式なので、ここに書いたメソッド以外はJS側から一切呼び出せない。
 */
class ShortcutApi(
    private val rootProvider: () -> Element,
    private val domContextProvider: () -> JsDomContext,
    private val registryProvider: () -> JsElementRegistry,
    private val onNavigate: (url: String) -> Unit,
    private val onBookmark: (title: String, url: String) -> Unit,
    private val currentUrlProvider: () -> String,
) {
    // --- ページ遷移 ---

    fun navigateTo(url: String) {
        onNavigate(url)
    }

    fun currentUrl(): String = currentUrlProvider()

    // --- 要素操作 ---

    fun exists(selector: String): Boolean = rootProvider().querySelector(selector) != null

    fun tap(selector: String): Boolean {
        val element = rootProvider().querySelector(selector) ?: return false
        com.B.b.Renderer.debug.BehaviorAuditLog.record(
            com.B.b.Renderer.debug.BehaviorAuditLog.Category.DEVICE_SHORTCUT,
            "tap: $selector -> <${element.tag}>",
        )
        val domContext = domContextProvider()
        com.B.b.Renderer.input.dispatchClick(element, domContext.onHtmxTrigger)
        domContext.requestRedraw()
        return true
    }

    fun getText(selector: String): String? =
        rootProvider().querySelector(selector)?.collectVisibleText()?.trim()

    fun getAttribute(selector: String, name: String): String? =
        rootProvider().querySelector(selector)?.attributes?.get(name)

    fun fillField(selector: String, value: String): Boolean {
        val element = rootProvider().querySelector(selector) ?: return false
        element.attributes["value"] = value
        domContextProvider().requestRedraw()
        return true
    }

    fun isChecked(selector: String): Boolean =
        rootProvider().querySelector(selector)?.elementState?.checked ?: false

    fun isDisabled(selector: String): Boolean =
        rootProvider().querySelector(selector)?.elementState?.disabled ?: true

    fun count(selector: String): Int = rootProvider().querySelectorAll(selector).size

    /** JS層と同じJsElementラッパーを返す。高度な操作が必要な場合の逃げ道。 */
    fun element(selector: String): JsElement? =
        rootProvider().querySelector(selector)?.let { registryProvider().wrap(it) }

    // --- ブックマーク ---

    fun bookmarkCurrentPage(title: String) {
        onBookmark(title, currentUrlProvider())
    }

    // --- 待機(簡易・同期的なポーリング) ---

    /**
     * selectorが出現するまで最大timeoutMsだけポーリングする。
     * ショートカットは同期実行前提のスクリプトなので、ここではスレッドをブロックする
     * シンプルな実装にしてある。UIスレッドから直接呼ぶと固まるため、
     * ショートカット実行自体は必ずバックグラウンドスレッドで行うこと
     * (DeviceScriptEngine側で担保)。
     */
    fun waitForElement(selector: String, timeoutMs: Long = 5000, pollIntervalMs: Long = 100): Boolean {
        val deadline = System.currentTimeMillis() + timeoutMs
        while (System.currentTimeMillis() < deadline) {
            if (exists(selector)) return true
            Thread.sleep(pollIntervalMs)
        }
        return false
    }

    fun log(message: Any?) {
        android.util.Log.d("ShortcutScript", message.toString())
    }
}
