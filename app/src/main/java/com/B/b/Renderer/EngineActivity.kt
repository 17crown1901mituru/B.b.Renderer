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
import com.B.b.Renderer.data.BookmarkStore
import com.B.b.Renderer.data.HistoryStore
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
    private val historyStore by lazy { HistoryStore(this) }
    private val bookmarkStore by lazy { BookmarkStore(this) }
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
    private lateinit var loadingIndicator: View

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
        loadingIndicator = View(this).apply {
            setBackgroundColor(android.graphics.Color.parseColor("#2196F3"))
            visibility = View.GONE
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
                loadingIndicator,
                FrameLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, dp(3)).apply {
                    gravity = Gravity.TOP
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
            onTabSelected = { id -> switchToTab(id) }
            onNewTabRequested = { openNewTab(DEFAULT_URL) }
            onPinToggleRequested = { id, pin -> tabManager.setPinned(id, pin); tabBarView.refresh(); syncKeepAliveService() }
            onPipToggleRequested = { id, show -> tabManager.setShowAsPip(id, show); refreshPipOverlays(); tabBarView.refresh() }
            onCloseRequested = { id -> closeTab(id) }
        }

        val debugDrawer = DebugDrawerView(
            context = this,
            sitePermissions = sitePermissions,
            globalSettings = globalSettings,
            historyStore = historyStore,
            bookmarkStore = bookmarkStore,
            currentDomainProvider = { sitePermissions.domainOf(currentPageUrl) },
            onGlobalSettingsChanged = {
                if (globalSettings.userKeepScreenOn) {
                    capabilityBridge.requestWakeLock("", fromUser = true)
                } else {
                    capabilityBridge.releaseWakeLock()
                }
            },
            onNavigateRequested = { url -> navigateForegroundTo(url) },
            currentUrlProvider = { currentPageUrl },
            currentTitleProvider = { tabManager.foregroundSession()?.title ?: currentPageUrl },
            tabBarView = tabBarView,
        ).apply {
            onBackRequested = { goBack() }
            onForwardRequested = { goForward() }
            onReloadRequested = { reloadCurrentTab() }
            onStopRequested = { stopLoading() }
        }
        val drawerParams = DrawerLayout.LayoutParams(ViewGroup.LayoutParams.WRAP_CONTENT, ViewGroup.LayoutParams.MATCH_PARENT)
        drawerParams.gravity = Gravity.END
        drawerLayout.addView(debugDrawer, drawerParams)
        drawerLayout.addDrawerListener(object : DrawerLayout.SimpleDrawerListener() {
            override fun onDrawerOpened(drawerView: View) = debugDrawer.setAutoRefresh(true)
            override fun onDrawerClosed(drawerView: View) = debugDrawer.setAutoRefresh(false)
        })
        this.debugDrawerLayout = drawerLayout

        setContentView(drawerLayout)

        // targetSdk 35はedge-to-edgeが既定(システムバーの裏まで描画される)。
        // 今まで一切insetsを処理していなかったため、ドロワーの上端がステータスバーに、
        // 下端がナビゲーションバーに被って操作しづらくなっていた。ドロワー自体に
        // システムバー分のパディングを入れる(コンテンツ側のengineViewRootは
        // ページ自体をシステムバーの裏まで見せたい場合もあるため、あえて触らない)。
        androidx.core.view.ViewCompat.setOnApplyWindowInsetsListener(debugDrawer) { view, insets ->
            val bars = insets.getInsets(androidx.core.view.WindowInsetsCompat.Type.systemBars())
            view.setPadding(view.paddingLeft, bars.top, view.paddingRight, bars.bottom)
            insets
        }
        // 右下トグルボタンもナビゲーションバーに埋もれないよう、下マージンをインセット分だけ足す
        androidx.core.view.ViewCompat.setOnApplyWindowInsetsListener(drawerToggleButton) { view, insets ->
            val bars = insets.getInsets(androidx.core.view.WindowInsetsCompat.Type.systemBars())
            val params = view.layoutParams as FrameLayout.LayoutParams
            params.bottomMargin = dp(12) + bars.bottom
            params.rightMargin = dp(12) + bars.right
            view.layoutParams = params
            insets
        }

        androidx.core.view.ViewCompat.setOnApplyWindowInsetsListener(loadingIndicator) { view, insets ->
            val bars = insets.getInsets(androidx.core.view.WindowInsetsCompat.Type.systemBars())
            val params = view.layoutParams as FrameLayout.LayoutParams
            params.topMargin = bars.top
            view.layoutParams = params
            insets
        }

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

        openNewTab(resolveInitialUrl(intent))

        onBackPressedDispatcher.addCallback(this, object : androidx.activity.OnBackPressedCallback(true) {
            override fun handleOnBackPressed() {
                when {
                    ::debugDrawerLayout.isInitialized && debugDrawerLayout.isDrawerOpen(Gravity.END) ->
                        debugDrawerLayout.closeDrawer(Gravity.END)
                    tabManager.canGoBack() -> goBack()
                    else -> {
                        isEnabled = false
                        onBackPressedDispatcher.onBackPressed()
                        isEnabled = true
                    }
                }
            }
        })
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
        navigateForegroundTo(resolveInitialUrl(intent))
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
    // 以下は全て「1つの読み込みジョブ」に統一する: 新しい操作が来たら前のジョブは
    // キャンセルし(=読み込み中止に相当)、読み込み中はloadingIndicatorを表示する。

    private var loadingJob: kotlinx.coroutines.Job? = null

    private fun setLoading(loading: Boolean) {
        if (::loadingIndicator.isInitialized) {
            loadingIndicator.visibility = if (loading) View.VISIBLE else View.GONE
        }
    }

    private fun runNavigation(block: suspend () -> TabSession?) {
        loadingJob?.cancel()
        setLoading(true)
        loadingJob = CoroutineScope(Dispatchers.Main).launch {
            try {
                val session = block()
                if (session != null) {
                    applyForeground(session)
                    tabBarView.refresh()
                    refreshPipOverlays()
                    syncKeepAliveService()
                }
            } catch (e: kotlinx.coroutines.CancellationException) {
                throw e // 中止(cancel)は正常系。ここで握りつぶすとjoinやfinallyの扱いが崩れる
            } finally {
                setLoading(false)
            }
        }
    }

    /** 読み込み中止。ユーザーが「×」を押した場合に呼ぶ。 */
    private fun stopLoading() {
        loadingJob?.cancel()
        setLoading(false)
    }

    private fun goBack() {
        if (!tabManager.canGoBack()) return
        runNavigation { tabManager.goBack() }
    }

    private fun goForward() {
        if (!tabManager.canGoForward()) return
        runNavigation { tabManager.goForward() }
    }

    private fun reloadCurrentTab() {
        runNavigation { tabManager.reloadForeground() }
    }

    /** 同じタブ内でのページ遷移(リンクを踏む、device shortcutsのnavigateTo等)。 */
    private fun navigateForegroundTo(url: String) {
        capabilityBridge.releaseWakeLock()
        capabilityBridge.unlockOrientation()
        runNavigation { tabManager.navigateForeground(url) }
    }

    /** 新しいタブを開いてフォアグラウンドにする(+ボタン、初回起動)。 */
    private fun openNewTab(url: String) {
        capabilityBridge.releaseWakeLock()
        capabilityBridge.unlockOrientation()
        runNavigation { tabManager.openNewForeground(url) }
    }

    /** 既存タブ(pinnedで生きている、または休止中)をフォアグラウンドに切り替える。 */
    private fun switchToTab(id: Long) {
        if (id == tabManager.foregroundId) return
        runNavigation { tabManager.switchForeground(id) }
    }

    private fun closeTab(id: Long) {
        val wasForeground = id == tabManager.foregroundId
        tabManager.closeTab(id)
        refreshPipOverlays()
        tabBarView.refresh()
        syncKeepAliveService()
        if (wasForeground) {
            val fallback = tabManager.allTabIds().firstOrNull()
            if (fallback != null) switchToTab(fallback) else openNewTab(DEFAULT_URL)
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
        recordHistoryVisit(session.url, session.title)
    }

    /**
     * 履歴への記録。SQLite書き込みはメインスレッドから外す。
     * シークレットタブ運用を入れる場合はここでtabManager側のフラグを見て早期returnすればよい(TODO)。
     */
    private fun recordHistoryVisit(url: String, title: String) {
        if (url.isBlank()) return
        CoroutineScope(Dispatchers.IO).launch { historyStore.recordVisit(url, title) }
    }

    private fun buildShortcutApi(): ShortcutApi = ShortcutApi(
        rootProvider = { tabManager.foregroundSession()?.layoutEngine?.root ?: error("No foreground tab") },
        domContextProvider = { tabManager.foregroundSession()?.jsDomContext ?: error("No foreground tab") },
        registryProvider = { tabManager.foregroundSession()?.jsEngine?.registry ?: error("No foreground tab") },
        onNavigate = { navUrl -> runOnUiThread { navigateForegroundTo(navUrl) } },
        onBookmark = { title, url ->
            CoroutineScope(Dispatchers.IO).launch { bookmarkStore.add(url, title) }
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
            ).apply {
                pageTitle = runCatching { htmlParser.extractTitle(html) }.getOrNull()
            }
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
