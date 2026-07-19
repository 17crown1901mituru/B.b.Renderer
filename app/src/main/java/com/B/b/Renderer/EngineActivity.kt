package com.B.b.Renderer

import android.content.Intent
import android.net.Uri
import android.os.Bundle
import android.view.Gravity
import android.view.View
import android.view.ViewGroup
import android.widget.FrameLayout
import android.widget.LinearLayout
import androidx.appcompat.app.AppCompatActivity
import androidx.drawerlayout.widget.DrawerLayout
import com.B.b.Renderer.core.Element
import com.B.b.Renderer.core.FormControlElement
import com.B.b.Renderer.core.HtmlFragmentParser
import com.B.b.Renderer.debug.BehaviorAuditLog
import com.B.b.Renderer.debug.DebugDrawerView
import com.B.b.Renderer.device.DeviceScriptEngine
import com.B.b.Renderer.device.RjsShortcutScanner
import com.B.b.Renderer.device.ShortcutApi
import com.B.b.Renderer.htmx.HtmxRenderEngine
import com.B.b.Renderer.js.JsDomContext
import com.B.b.Renderer.js.JsEngine
import com.B.b.Renderer.layout.LayoutEngine
import com.B.b.Renderer.permissions.BrowserCapabilityBridge
import com.B.b.Renderer.permissions.GlobalAppSettings
import com.B.b.Renderer.permissions.SitePermissions
import com.B.b.Renderer.network.SimpleCookieJar
import com.B.b.Renderer.render.EngineHostView
import com.B.b.Renderer.render.RendererFactory
import com.B.b.Renderer.style.CssParser
import com.B.b.Renderer.style.StyleResolver
import com.B.b.Renderer.tabs.TabBarView
import com.B.b.Renderer.tabs.TabManager
import com.B.b.Renderer.tabs.TabSession
import com.B.b.Renderer.thermal.ThermalGuard
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.async
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import okhttp3.OkHttpClient
import okhttp3.Request

/**
 * Google WebViewに依存しないエンジンのホストActivity。
 * 起動時に指定URLをfetchし、HTMLパース→スタイル解決→レイアウト計算→描画までを
 * 自前のパイプラインで行う。描画バックエンドはGPU端末性能に応じてRendererFactoryが選択する。
 *
 * マルチタブ対応(2026-07議論分):
 *   - フォアグラウンド1タブ以外は既定で完全休止(TabManager)
 *   - pinnedタブはJS/メディアを裏で動かし続ける(エンジンを破棄しないだけで実現)
 *   - showAsPipタブは、さらに小窓(CPU/Canvas固定、発熱対策)として画面に表示する
 *   - ThermalGuardが端末の温度悪化を検知したら、pinned/PiPを強制的に減らす
 */
class EngineActivity : AppCompatActivity() {

    companion object {
        const val EXTRA_URL = "com.B.b.Renderer.EXTRA_URL"
        private const val PREFS_NAME = "engine_settings"
        private const val PREF_KEY_HOME_URL = "home_url"
        private const val DEFAULT_URL = "https://example.com/"
    }

    private val sitePermissions by lazy { SitePermissions(this) }
    private val globalSettings by lazy { GlobalAppSettings(this) }
    private val capabilityBridge by lazy { BrowserCapabilityBridge(this, sitePermissions, globalSettings) }
    private val thermalGuard by lazy { ThermalGuard(this) }
    private var currentPageUrl: String = ""

    private val okHttpClient by lazy {
        val cookieJar = SimpleCookieJar(
            context = this,
            globalSettings = globalSettings,
            sitePermissions = sitePermissions,
            currentPageDomainProvider = { sitePermissions.domainOf(currentPageUrl) },
        )
        OkHttpClient.Builder()
            .cookieJar(cookieJar)
            .addInterceptor { chain ->
                val request = chain.request().newBuilder()
                    .header("User-Agent", globalSettings.userAgent)
                    .build()
                chain.proceed(request)
            }
            .build()
    }
    private val htmlParser = HtmlFragmentParser()
    private lateinit var engineViewRoot: View
    private lateinit var engineHost: EngineHostView
    private lateinit var deviceEngine: DeviceScriptEngine
    private lateinit var debugDrawerLayout: DrawerLayout
    private lateinit var tabManager: TabManager
    private lateinit var tabBarView: TabBarView
    private lateinit var pipContainer: LinearLayout

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)

        engineViewRoot = RendererFactory.create(this)
        engineHost = engineViewRoot as EngineHostView

        tabManager = TabManager(sessionFactory = { url -> buildSession(url) })

        // device shortcuts(.rjs)は常にその時点のフォアグラウンドタブに対して働く。
        // providerで動的にtabManager.foregroundSession()を参照するため、1回作るだけでよい
        // (bshEngineの旧設計と違い、タブ切替のたびに作り直す必要がない)。
        deviceEngine = DeviceScriptEngine(this, buildShortcutApi())
        deviceEngine.registerAll(RjsShortcutScanner.scan(assets))

        pipContainer = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
        }

        // ブラウザとしての機能(タブ一覧)・設定はドロワー側に集約し、ページ描画領域
        // (engineViewRoot)は画面いっぱいに使う(2026-07議論分)。PiP小窓のみ上に重ねる。
        val drawerToggleButton = android.widget.TextView(this).apply {
            text = "☰"
            textSize = 16f
            setTextColor(android.graphics.Color.WHITE)
            setBackgroundColor(android.graphics.Color.parseColor("#88000000"))
            setPadding(dp(10), dp(6), dp(10), dp(6))
            setOnClickListener { toggleDebugDrawer() }
        }
        val mainContainer = FrameLayout(this).apply {
            addView(engineViewRoot, FrameLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.MATCH_PARENT))
            addView(
                pipContainer,
                FrameLayout.LayoutParams(ViewGroup.LayoutParams.WRAP_CONTENT, ViewGroup.LayoutParams.WRAP_CONTENT).apply {
                    gravity = Gravity.TOP or Gravity.END
                },
            )
            addView(
                drawerToggleButton,
                FrameLayout.LayoutParams(ViewGroup.LayoutParams.WRAP_CONTENT, ViewGroup.LayoutParams.WRAP_CONTENT).apply {
                    gravity = Gravity.BOTTOM or Gravity.END
                    setMargins(0, 0, dp(12), dp(12))
                },
            )
        }

        // EngineView(ページ描画)・ソフトウェアキーボード・デバッグ表示が同時に
        // 画面上へ重なるとごちゃつくため、デバッグパネルは常時表示にせず
        // DrawerLayoutのendドロワー(画面端からのスワイプ/ボタンで引き出す)に収める。
        val drawerLayout = DrawerLayout(this)
        drawerLayout.addView(
            mainContainer,
            DrawerLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.MATCH_PARENT),
        )

        tabBarView = TabBarView(this, tabManager, onTabChanged = {}).apply {
            onTabSelected = { id -> CoroutineScope(Dispatchers.Main).launch { switchToTab(id) } }
            onNewTabRequested = { CoroutineScope(Dispatchers.Main).launch { openNewTab(DEFAULT_URL) } }
            onPinToggleRequested = { id, pin -> tabManager.setPinned(id, pin); tabBarView.refresh(); syncKeepAliveService() }
            onPipToggleRequested = { id, show -> tabManager.setShowAsPip(id, show); refreshPipOverlays(); tabBarView.refresh() }
            onCloseRequested = { id -> closeTab(id) }
        }

        val debugDrawer = DebugDrawerView(
            context = this,
            sitePermissions = sitePermissions,
            globalSettings = globalSettings,
            currentDomainProvider = { sitePermissions.domainOf(currentPageUrl) },
            onGlobalSettingsChanged = {
                if (globalSettings.userKeepScreenOn) {
                    capabilityBridge.requestWakeLock("", fromUser = true)
                } else {
                    capabilityBridge.releaseWakeLock()
                }
            },
            tabBarView = tabBarView,
        )
        val drawerParams = DrawerLayout.LayoutParams(ViewGroup.LayoutParams.WRAP_CONTENT, ViewGroup.LayoutParams.MATCH_PARENT)
        drawerParams.gravity = Gravity.END
        drawerLayout.addView(debugDrawer, drawerParams)
        drawerLayout.addDrawerListener(object : DrawerLayout.SimpleDrawerListener() {
            override fun onDrawerOpened(drawerView: View) = debugDrawer.setAutoRefresh(true)
            override fun onDrawerClosed(drawerView: View) = debugDrawer.setAutoRefresh(false)
        })
        this.debugDrawerLayout = drawerLayout

        setContentView(drawerLayout)

        // アプリ全体設定(ユーザー起因)は起動時点で即反映する(トグル操作を待たない)
        if (globalSettings.userKeepScreenOn) capabilityBridge.requestWakeLock("", fromUser = true)
        if (globalSettings.userOrientationLock.isNotBlank()) {
            capabilityBridge.lockOrientation("", globalSettings.userOrientationLock, fromUser = true)
        }

        thermalGuard.startMonitoring {
            runOnUiThread {
                tabManager.throttleForThermal()
                refreshPipOverlays()
                tabBarView.refresh()
                syncKeepAliveService()
            }
        }

        CoroutineScope(Dispatchers.Main).launch {
            openNewTab(resolveInitialUrl(intent))
        }
    }

    fun toggleDebugDrawer() {
        if (::debugDrawerLayout.isInitialized) {
            if (debugDrawerLayout.isDrawerOpen(Gravity.END)) {
                debugDrawerLayout.closeDrawer(Gravity.END)
            } else {
                debugDrawerLayout.openDrawer(Gravity.END)
            }
        }
    }

    override fun onNewIntent(intent: Intent) {
        super.onNewIntent(intent)
        setIntent(intent)
        CoroutineScope(Dispatchers.Main).launch {
            navigateForegroundTo(resolveInitialUrl(intent))
        }
    }

    private fun resolveInitialUrl(intent: Intent): String {
        intent.getStringExtra(EXTRA_URL)?.let { return it }
        if (intent.action == Intent.ACTION_VIEW) {
            (intent.data as Uri?)?.toString()?.let { return it }
        }
        val prefs = getSharedPreferences(PREFS_NAME, MODE_PRIVATE)
        return prefs.getString(PREF_KEY_HOME_URL, null) ?: DEFAULT_URL
    }

    // --- タブ操作 ---

    /** 同じタブ内でのページ遷移(リンクを踏む、device shortcutsのnavigateTo等)。 */
    private suspend fun navigateForegroundTo(url: String) {
        capabilityBridge.releaseWakeLock()
        capabilityBridge.unlockOrientation()
        val session = tabManager.navigateForeground(url)
        applyForeground(session)
    }

    /** 新しいタブを開いてフォアグラウンドにする(+ボタン、初回起動)。 */
    private suspend fun openNewTab(url: String) {
        capabilityBridge.releaseWakeLock()
        capabilityBridge.unlockOrientation()
        val session = tabManager.openNewForeground(url)
        applyForeground(session)
        tabBarView.refresh()
    }

    /** 既存タブ(pinnedで生きている、または休止中)をフォアグラウンドに切り替える。 */
    private suspend fun switchToTab(id: Long) {
        if (id == tabManager.foregroundId) return
        val session = tabManager.switchForeground(id)
        applyForeground(session)
        tabBarView.refresh()
        refreshPipOverlays()
    }

    private fun closeTab(id: Long) {
        val wasForeground = id == tabManager.foregroundId
        tabManager.closeTab(id)
        refreshPipOverlays()
        tabBarView.refresh()
        syncKeepAliveService()
        if (wasForeground) {
            val fallback = tabManager.allTabIds().firstOrNull()
            CoroutineScope(Dispatchers.Main).launch {
                if (fallback != null) switchToTab(fallback) else openNewTab(DEFAULT_URL)
            }
        }
    }

    /** pinnedタブの有無に合わせてTabKeepAliveServiceを起動/停止する。 */
    private fun syncKeepAliveService() {
        val pinnedCount = tabManager.pinnedSessions().size
        if (pinnedCount > 0) {
            val intent = Intent(this, com.B.b.Renderer.tabs.TabKeepAliveService::class.java)
                .putExtra(com.B.b.Renderer.tabs.TabKeepAliveService.EXTRA_PINNED_COUNT, pinnedCount)
            startForegroundService(intent)
        } else {
            stopService(Intent(this, com.B.b.Renderer.tabs.TabKeepAliveService::class.java))
        }
    }

    /** フォアグラウンド表示・入力の参照先を、指定タブへ切り替える。 */
    private fun applyForeground(session: TabSession) {
        engineHost.attach(session.layoutEngine)
        engineHost.onHtmxTrigger = session.onHtmxTrigger
        currentPageUrl = session.url
    }

    private fun buildShortcutApi(): ShortcutApi = ShortcutApi(
        rootProvider = { tabManager.foregroundSession()?.layoutEngine?.root ?: error("No foreground tab") },
        domContextProvider = { tabManager.foregroundSession()?.jsDomContext ?: error("No foreground tab") },
        registryProvider = { tabManager.foregroundSession()?.jsEngine?.registry ?: error("No foreground tab") },
        onNavigate = { navUrl -> CoroutineScope(Dispatchers.Main).launch { navigateForegroundTo(navUrl) } },
        onBookmark = { _, _ ->
            // TODO: ブックマークストアは未実装。実装され次第ここから呼ぶ。
        },
        currentUrlProvider = { tabManager.foregroundSession()?.layoutEngine?.currentPath ?: "" },
    )

    /** pinned+showAsPipなタブそれぞれに、小さなCPU/Canvas描画Viewを割り当てて表示する。 */
    private fun refreshPipOverlays() {
        pipContainer.removeAllViews()
        val pxSize = (140 * resources.displayMetrics.density).toInt()
        tabManager.pipSessions().forEach { session ->
            // 発熱対策のため、PiP窓は常にCPU(Canvas)固定でGPUは使わない
            val pipView = RendererFactory.createForceCpu(this)
            val host = pipView as EngineHostView
            host.attach(session.layoutEngine)
            host.onHtmxTrigger = session.onHtmxTrigger
            session.pipHostView = host
            pipContainer.addView(
                pipView,
                LinearLayout.LayoutParams(pxSize, (pxSize * 0.65f).toInt()).apply {
                    setMargins(0, dp(4), dp(4), 0)
                },
            )
        }
    }

    private fun dp(value: Int): Int = (value * resources.displayMetrics.density).toInt()

    /** URL取得・パース・スタイル解決・レイアウト・JS/HTMXエンジン一式を新規に組み立てる。タブ1つ分の構築。 */
    private suspend fun buildSession(url: String): TabSession {
        val html = withContext(Dispatchers.IO) {
            try {
                fetchHtml(url)
            } catch (e: java.io.IOException) {
                BehaviorAuditLog.record(BehaviorAuditLog.Category.JS_EVAL, "fetch failed: $url (${e.message})")
                errorPageHtml(url, e.message ?: e.toString())
            }
        }
        val css = fetchStylesheets(html, url)

        // ANR対策: パース・スタイル解決・レイアウト計算・JSエンジン初期化(babel.min.jsの
        // 評価を含む、数MBのJSをRhinoインタプリタで評価するため重い)・ページ内<script>実行を
        // メインスレッドから退避する。この時点ではまだengineHostにattachしていないので、
        // ここでのDOM操作が描画スレッドと競合することもない。
        return withContext(Dispatchers.Default) {
            val root = htmlParser.parseDocument(html)
            val stylesheet = CssParser().parse(css)
            val styleResolver = StyleResolver(stylesheet)
            styleResolver.resolveTree(root)

            val displayMetrics = resources.displayMetrics
            val layoutEngine = LayoutEngine(
                root = root,
                viewportWidth = displayMetrics.widthPixels.toFloat(),
                viewportHeight = displayMetrics.heightPixels.toFloat(),
            )
            layoutEngine.currentPath = url

            val htmxEngine = HtmxRenderEngine(okHttpClient, htmlParser, layoutEngine)

            lateinit var jsEngineRef: JsEngine
            // ネイティブタップ・JS(element.click())・device shortcuts(shortcuts.tap)の3経路が
            // 全てここに合流する。分岐を増やさないための唯一の入り口。
            val sharedHtmxTrigger: (Element) -> Unit = { triggerElement ->
                BehaviorAuditLog.record(
                    BehaviorAuditLog.Category.HTMX_TRIGGER,
                    "<${triggerElement.tag}> hx-post=${triggerElement.attributes["hx-post"]} hx-get=${triggerElement.attributes["hx-get"]}",
                )
                CoroutineScope(Dispatchers.Main).launch {
                    val params = collectFormParams(triggerElement)
                    val resultElement = withContext(Dispatchers.IO) {
                        htmxEngine.handleAction(triggerElement, params)
                    }
                    engineHost.requestLayoutPass()
                    jsEngineRef.runInlineScripts(resultElement)
                }
            }

            val jsDomContext = JsDomContext(
                layoutEngine = layoutEngine,
                htmlParser = htmlParser,
                styleResolver = styleResolver,
                requestRedraw = { engineHost.requestLayoutPass() },
                onHtmxTrigger = sharedHtmxTrigger,
            )
            val jsEngine = JsEngine(root, jsDomContext, okHttpClient, capabilityBridge)
            jsEngineRef = jsEngine
            jsEngine.window.location.href = url
            tryEnableEs6Support(jsEngine)
            tryLoadHtmxFromAssets(jsEngine)

            jsEngine.runInlineScripts(root)

            TabSession(
                url = url,
                root = root,
                layoutEngine = layoutEngine,
                jsEngine = jsEngine,
                htmxEngine = htmxEngine,
                jsDomContext = jsDomContext,
                onHtmxTrigger = sharedHtmxTrigger,
            )
        }
    }

    override fun onDestroy() {
        // pinnedタブは破棄しない(Foreground Serviceが生かし続けている前提)。
        // 破棄するのはpinnedでないタブ(休止中含む)だけ。
        tabManager.allTabIds().filterNot { tabManager.isPinned(it) }.forEach { tabManager.closeTab(it) }
        if (::deviceEngine.isInitialized) deviceEngine.shutdown()
        thermalGuard.stopMonitoring()
        super.onDestroy()
    }

    private fun fetchHtml(url: String): String {
        val response = okHttpClient.newCall(Request.Builder().url(url).build()).execute()
        return response.body?.string() ?: ""
    }

    private fun errorPageHtml(url: String, message: String): String = """
        <html><body style="font-family:sans-serif;padding:24px;color:#333">
        <h2>ページを読み込めませんでした</h2>
        <p>$url</p>
        <p style="color:#900">$message</p>
        </body></html>
    """.trimIndent()

    /**
     * assets/libs/transform/babel.min.js があればES6+サポートを有効化する。
     */
    private fun tryEnableEs6Support(jsEngine: JsEngine) {
        try {
            assets.open("libs/transform/babel.min.js").use { stream ->
                jsEngine.enableEs6Support(stream)
            }
        } catch (e: java.io.IOException) {
            // 未配置は正常系。ES6構文を含むページJSは動かない可能性があるのみ。
        }
    }

    /**
     * assets/libs/htmx.min.js があれば読み込む。無ければ何もしない(必須ではない)。
     */
    private fun tryLoadHtmxFromAssets(jsEngine: JsEngine) {
        try {
            val source = assets.open("libs/htmx.min.js").bufferedReader().use { it.readText() }
            jsEngine.loadHtmx(source)
        } catch (e: java.io.IOException) {
            // 未配置は正常系。ネイティブのHtmxRenderEngineのみで動作する。
        }
    }

    /**
     * インライン<style>と外部<link rel="stylesheet" href="...">の両方を収集し、
     * 出現順を保ったまま結合する。外部シートは並行fetchする。
     */
    private suspend fun fetchStylesheets(html: String, baseUrl: String): String =
        withContext(Dispatchers.IO) {
            data class Source(val order: Int, val text: kotlinx.coroutines.Deferred<String>)

            var order = 0
            val sources = mutableListOf<Source>()

            Regex("<style[^>]*>([\\s\\S]*?)</style>").findAll(html).forEach { match ->
                val css = match.groupValues[1]
                sources.add(Source(order++, async { css }))
            }

            Regex("<link\\b[^>]*>", RegexOption.IGNORE_CASE).findAll(html).forEach { linkMatch ->
                val tag = linkMatch.value
                val rel = Regex("rel\\s*=\\s*[\"']([^\"']+)[\"']", RegexOption.IGNORE_CASE)
                    .find(tag)?.groupValues?.get(1)?.lowercase() ?: return@forEach
                if ("stylesheet" !in rel.split(Regex("\\s+"))) return@forEach

                val href = Regex("href\\s*=\\s*[\"']([^\"']+)[\"']", RegexOption.IGNORE_CASE)
                    .find(tag)?.groupValues?.get(1) ?: return@forEach

                val absoluteUrl = resolveUrl(baseUrl, href)
                sources.add(Source(order++, async { runCatching { fetchHtml(absoluteUrl) }.getOrDefault("") }))
            }

            sources.sortedBy { it.order }
                .map { it.text.await() }
                .joinToString("\n")
        }

    private fun resolveUrl(baseUrl: String, href: String): String =
        runCatching { java.net.URI(baseUrl).resolve(href).toString() }.getOrDefault(href)

    private fun collectFormParams(triggerElement: Element): Map<String, String> {
        val form = findEnclosingForm(triggerElement) ?: triggerElement
        val params = mutableMapOf<String, String>()
        form.findAll { it is FormControlElement }.forEach { field ->
            val control = field as FormControlElement
            val name = control.name ?: return@forEach
            params[name] = control.currentValue()
        }
        return params
    }

    private fun findEnclosingForm(element: Element): Element? {
        var current: Element? = element
        while (current != null) {
            if (current.tag == "form") return current
            current = current.parent
        }
        return null
    }
}
