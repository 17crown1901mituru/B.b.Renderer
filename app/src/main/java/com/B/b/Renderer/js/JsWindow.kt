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

    /**
     * オリジン単位で永続化(SharedPreferences)。capabilityBridge(≒Context)が無い場合は
     * NoopStorageBackendで安全にno-op化する。
     */
    val localStorage = JsStorage(
        capabilityBridge?.let { bridge ->
            SharedPrefsStorageBackend(bridge.context) { location.href.toOriginOrEmpty() }
        } ?: NoopStorageBackend(),
    )

    /** タブが生きている間だけのメモリ保持(ディスクには書かない)。 */
    val sessionStorage = JsStorage(InMemoryStorageBackend())

    /**
     * window.open()で実際に新しいタブを開く処理はEngine側(TabManager操作、UIスレッド限定)に
     * 委譲する必要があるため、コールバックとして注入してもらう形にする(EngineActivity側で
     * buildSession()時にセットする想定。未セットの間はopen()は許可判定にすら進まず何もしない)。
     */
    var onOpenPopup: ((String) -> Unit)? = null

    /**
     * window.open(url, target, features)相当。実仕様と異なりWindowオブジェクトは
     * 返さない(このエンジンには複数タブそれぞれを指すJS側の参照を作る仕組みが無いため)。
     * ポップアップブロックの既定はブロック側(SitePermissions.POPUPS、既定オフ)。
     * ドメイン単位で許可すればJSから新規タブを開けるようになる。
     */
    fun open(url: String? = null, target: String? = null, features: String? = null): Any? {
        if (url.isNullOrBlank()) return null
        val absoluteUrl = resolveUrl(url)
        val bridge = capabilityBridge
        val domain = location.href.toHttpDomainOrEmpty()
        val allowed = bridge?.isPopupAllowed(domain) ?: false
        if (!allowed) {
            com.B.b.Renderer.debug.BehaviorAuditLog.record(
                com.B.b.Renderer.debug.BehaviorAuditLog.Category.JS_EVAL,
                "popup blocked: $absoluteUrl (domain=$domain)",
            )
            return null
        }
        onOpenPopup?.invoke(absoluteUrl)
        return null
    }

    /** location.hrefを基点に相対URLを解決する。解決できない場合は素通しする。 */
    private fun resolveUrl(url: String): String =
        runCatching { java.net.URI(location.href).resolve(url).toString() }.getOrDefault(url)

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
    val geolocation = JsGeolocation(bridge, currentUrl)
    val clipboard = JsClipboard(bridge, currentUrl)

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

/**
 * navigator.clipboard.writeText()相当。実ページでよく見る
 * `navigator.clipboard.writeText(text).then(() => {...})` という書き方がそのまま
 * 動くように、戻り値は真偽値ではなくJsThenable(簡易Promise代替、下記参照)にしている。
 *
 * サイト単位の許可(SitePermissions.CLIPBOARD_WRITE、既定不許可)は
 * BrowserCapabilityBridge側で見ており、未許可の場合はJsThenableのreject相当
 * (.then()の第2引数、または.catch())が呼ばれる。
 */
class JsClipboard(
    private val bridge: BrowserCapabilityBridge?,
    private val currentUrl: () -> String,
) {
    fun writeText(text: String?): JsThenable {
        val b = bridge ?: return JsThenable(succeeded = false, errorMessage = "clipboard unavailable")
        val domain = currentUrl().toHttpDomainOrEmpty()
        val wrote = b.writeClipboardText(domain, text ?: "")
        return if (wrote) {
            JsThenable(succeeded = true)
        } else {
            JsThenable(succeeded = false, errorMessage = "clipboard permission denied (site setting)")
        }
    }
}

/**
 * 簡易Promise代替。
 *
 * 【意図的な割り切り】真のPromise(マイクロタスクキュー、複数then()チェーンの非同期解決、
 * Promise.all等)は実装しない(README「引き算の設計」方針、かつRhinoのバージョンにより
 * ネイティブPromiseの有無が変わるリスクを避けるため)。navigator.clipboard.writeText()の
 * ように「結果は同期的にすぐ確定しており、ページ側は.then()/.catch()を1回チェーンする
 * だけ」という頻出パターンのためだけの最小限の実装であり、resolve/rejectは
 * コンストラクタ時点で既に確定している(then()を呼ぶまで実処理を遅延させたりはしない)。
 * 他のAPIへ流用する場合はこの前提(同期的に結果が決まっている)を満たすことを確認すること。
 */
class JsThenable(private val succeeded: Boolean, private val errorMessage: String = "") {

    /** Promise.then(onFulfilled, onRejected)相当。戻り値のthisをそのまま返すのでcatch()をさらに繋げられる。 */
    fun then(onFulfilled: Function?, onRejected: Function? = null): JsThenable {
        val callback = if (succeeded) onFulfilled else onRejected
        callback ?: return this
        val ctx = Context.enter()
        try {
            val scope = ScriptableObject.getTopLevelScope(callback)
            if (succeeded) {
                callback.call(ctx, scope, scope, emptyArray())
            } else {
                val errorObject = ctx.newObject(scope)
                ScriptableObject.putProperty(errorObject, "message", errorMessage)
                callback.call(ctx, scope, scope, arrayOf(errorObject))
            }
        } finally {
            Context.exit()
        }
        return this
    }

    /** Promise.catch(onRejected)相当。then(null, onRejected)と同じ経路を通すだけ。 */
    fun `catch`(onRejected: Function?): JsThenable = then(null, onRejected)
}

private fun String.toHttpDomainOrEmpty(): String =
    runCatching { java.net.URI(this).host ?: "" }.getOrDefault("")

private fun String.toOriginOrEmpty(): String =
    runCatching {
        val uri = java.net.URI(this)
        val host = uri.host ?: return ""
        val scheme = uri.scheme ?: "https"
        if (uri.port != -1) "$scheme://$host:${uri.port}" else "$scheme://$host"
    }.getOrDefault("")

/**
 * navigator.geolocation相当。実APIの`getCurrentPosition(success, error, options)`に合わせ、
 * successには`{coords: {latitude, longitude, accuracy}, timestamp}`形の簡易オブジェクトを渡す
 * (実仕様のGeolocationPositionは他にもプロパティを持つが、頻出のもののみ実装)。
 * errorには`{code, message}`を渡す(実仕様のPositionErrorに近い最小限の形)。
 *
 * サイト単位の許可(SitePermissions.GEOLOCATION)とOS権限(ACCESS_FINE/COARSE_LOCATION)の
 * 両方が必要で、実処理はBrowserCapabilityBridge側にある。
 */
class JsGeolocation(
    private val bridge: BrowserCapabilityBridge?,
    private val currentUrl: () -> String,
) {
    fun getCurrentPosition(success: Function, error: Function? = null) {
        val b = bridge
        if (b == null) {
            invokeError(error, "geolocation unavailable")
            return
        }
        val domain = currentUrl().toHttpDomainOrEmpty()
        b.getCurrentLocation(
            domain,
            onSuccess = { lat, lon, accuracy -> invokeSuccess(success, lat, lon, accuracy) },
            onError = { message -> invokeError(error, message) },
        )
    }

    private fun invokeSuccess(callback: Function, lat: Double, lon: Double, accuracy: Float) {
        val ctx = Context.enter()
        try {
            val scope = ScriptableObject.getTopLevelScope(callback)
            val coords = ctx.newObject(scope)
            ScriptableObject.putProperty(coords, "latitude", lat)
            ScriptableObject.putProperty(coords, "longitude", lon)
            ScriptableObject.putProperty(coords, "accuracy", accuracy.toDouble())
            val position = ctx.newObject(scope)
            ScriptableObject.putProperty(position, "coords", coords)
            ScriptableObject.putProperty(position, "timestamp", System.currentTimeMillis().toDouble())
            callback.call(ctx, scope, scope, arrayOf(position))
        } finally {
            Context.exit()
        }
    }

    private fun invokeError(callback: Function?, message: String) {
        val cb = callback ?: return
        val ctx = Context.enter()
        try {
            val scope = ScriptableObject.getTopLevelScope(cb)
            val errorObject = ctx.newObject(scope)
            ScriptableObject.putProperty(errorObject, "code", 1.0)
            ScriptableObject.putProperty(errorObject, "message", message)
            cb.call(ctx, scope, scope, arrayOf(errorObject))
        } finally {
            Context.exit()
        }
    }
}
