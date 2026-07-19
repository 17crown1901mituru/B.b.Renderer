package com.B.b.Renderer.js

import android.os.Handler
import android.os.Looper
import com.B.b.Renderer.permissions.BrowserCapabilityBridge
import org.mozilla.javascript.Context
import org.mozilla.javascript.Function
import org.mozilla.javascript.ScriptableObject

/**
 * window相当。タイマー系APIに加え、htmx.jsが参照するhistory/location/rAFの
 * 最小限のスタブを持つ。実際のページ遷移(hx-boost)にはAndroidの戻る操作との
 * 統合が別途必要で、ここではクラッシュしない程度の空実装に留めている。
 *
 * capabilityBridgeがnullの場合(bridge未提供)、navigator/screenの機能系メソッドは
 * 全て安全側にno-op/falseを返す(クラッシュはしないが何も起きない)。
 */
class JsWindow(private val capabilityBridge: BrowserCapabilityBridge? = null) {
    private val mainHandler = Handler(Looper.getMainLooper())
    private val pendingRunnables = mutableMapOf<Int, Runnable>()
    private var nextId = 1

    val console = JsConsole()
    val history = JsHistoryStub()
    var location = JsLocationStub()
    val navigator = JsNavigator(capabilityBridge) { location.href }
    val screen = JsScreen(capabilityBridge) { location.href }

    fun setTimeout(callback: Function, delayMs: Double): Int {
        val id = nextId++
        val runnable = Runnable { invoke(callback) }
        pendingRunnables[id] = runnable
        mainHandler.postDelayed(runnable, delayMs.toLong().coerceAtLeast(0))
        return id
    }

    fun clearTimeout(id: Int) {
        pendingRunnables.remove(id)?.let { mainHandler.removeCallbacks(it) }
    }

    fun setInterval(callback: Function, delayMs: Double): Int {
        val id = nextId++
        val interval = delayMs.toLong().coerceAtLeast(1)
        lateinit var runnable: Runnable
        runnable = Runnable {
            invoke(callback)
            mainHandler.postDelayed(runnable, interval)
        }
        pendingRunnables[id] = runnable
        mainHandler.postDelayed(runnable, interval)
        return id
    }

    fun clearInterval(id: Int) = clearTimeout(id)

    /** 16ms後にコールバックする簡易実装(実際のVSyncには同期しない) */
    fun requestAnimationFrame(callback: Function): Int = setTimeout(callback, 16.0)

    fun cancelAnimationFrame(id: Int) = clearTimeout(id)

    /** Activity破棄時などに未実行のタイマーを一掃する */
    fun cancelAll() {
        pendingRunnables.values.forEach { mainHandler.removeCallbacks(it) }
        pendingRunnables.clear()
    }

    private fun invoke(callback: Function) {
        val ctx = Context.enter()
        try {
            val scope = ScriptableObject.getTopLevelScope(callback)
            callback.call(ctx, scope, scope, emptyArray())
        } finally {
            Context.exit()
        }
    }
}

/**
 * hx-boost等が参照するhistory APIの最小スタブ。
 * 実際のAndroidバックスタック統合は別途必要(TODO)。
 * 呼んでもクラッシュしない、という以上のことは今はしない。
 */
class JsHistoryStub {
    fun pushState(state: Any?, title: String?, url: String?) { /* no-op */ }
    fun replaceState(state: Any?, title: String?, url: String?) { /* no-op */ }
    fun back() { /* no-op */ }
}

class JsLocationStub(var href: String = "")

/**
 * Vibration API(navigator.vibrate)相当。実ブラウザのAPIに寄せて、
 * 単一値(ms)またはパターン配列のどちらも受け付ける。
 * 許可されていないドメインの場合は何もせずfalseを返す(例外にしない、実ブラウザ同様)。
 */
class JsNavigator(
    private val bridge: BrowserCapabilityBridge?,
    private val currentUrl: () -> String,
) {
    fun vibrate(pattern: Any?): Boolean {
        val b = bridge ?: return false
        val domain = currentUrl().toHttpDomainOrEmpty()
        val ms: LongArray = when (pattern) {
            is Double -> longArrayOf(pattern.toLong())
            is Int -> longArrayOf(pattern.toLong())
            is org.mozilla.javascript.NativeArray -> LongArray(pattern.size) { i ->
                (pattern.get(i, pattern) as? Number)?.toLong() ?: 0L
            }
            else -> return false
        }
        return b.vibrate(domain, ms)
    }
}

class JsScreen(
    private val bridge: BrowserCapabilityBridge?,
    private val currentUrl: () -> String,
) {
    val orientation = JsScreenOrientation(bridge, currentUrl)
    val wakeLock = JsWakeLock(bridge, currentUrl)
}

class JsScreenOrientation(
    private val bridge: BrowserCapabilityBridge?,
    private val currentUrl: () -> String,
) {
    fun lock(type: String): Boolean = bridge?.lockOrientation(domainOf(currentUrl()), type) ?: false
    fun unlock() { bridge?.unlockOrientation() }
    private fun domainOf(url: String) = url.toHttpDomainOrEmpty()
}

class JsWakeLock(
    private val bridge: BrowserCapabilityBridge?,
    private val currentUrl: () -> String,
) {
    /** 簡略化のためPromiseは返さず真偽値を返す(実APIは`navigator.wakeLock.request('screen')`がPromiseを返す) */
    fun request(type: String = "screen"): Boolean = bridge?.requestWakeLock(domainOf(currentUrl())) ?: false
    fun release() { bridge?.releaseWakeLock() }
    private fun domainOf(url: String) = url.toHttpDomainOrEmpty()
}

private fun String.toHttpDomainOrEmpty(): String =
    runCatching { java.net.URI(this).host ?: "" }.getOrDefault("")
