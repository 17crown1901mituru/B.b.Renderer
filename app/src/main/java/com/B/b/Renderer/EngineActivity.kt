package com.B.b.Renderer

import android.content.Intent
import android.net.Uri
import android.os.Bundle
import android.view.Gravity
import android.view.View
import androidx.appcompat.app.AppCompatActivity
import androidx.core.view.ViewCompat
import androidx.core.view.WindowInsetsCompat
import com.B.b.Renderer.core.DirtyLevel
import com.B.b.Renderer.core.Element
import com.B.b.Renderer.core.FormControlElement
import com.B.b.Renderer.core.HtmlFragmentParser
import com.B.b.Renderer.core.ImageElement
import com.B.b.Renderer.core.ImageLoadState
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
import com.B.b.Renderer.permissions.LocalFilePicker
import com.B.b.Renderer.permissions.RuntimePermissionManager
import com.B.b.Renderer.permissions.SitePermissions
import com.B.b.Renderer.network.SimpleCookieJar
import com.B.b.Renderer.render.EngineFrameLayout
import com.B.b.Renderer.render.EngineHostView
import com.B.b.Renderer.render.FindInPageController
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
 * 画面の組み立て(mainContainer/DrawerLayout/insets)自体はrender/EngineFrameLayout.ktに
 * 委譲している(2026-07、Activity肥大化への対応として切り出し)。このActivityが持つのは
 * 「どのURLを開くか」「どのタブに切り替えるか」といったナビゲーションロジックのみ。
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

        // 2026-07、起動時のオープニング画面用プレースホルダーHTML。
        // ここに意味のある内容は無く、GLSurfaceViewのsetRenderer()/サーフェス生成を
        // 実ページ読み込みより先に開始させるためだけの空ページ(下記attachOpeningScreen参照)。
        private const val OPENING_SCREEN_HTML =
            "<html><body style=\"background-color:#111111\"></body></html>"
    }

    private val sitePermissions by lazy { SitePermissions(this) }
    private val globalSettings by lazy { GlobalAppSettings(this) }
    private val capabilityBridge by lazy { BrowserCapabilityBridge(this, sitePermissions, globalSettings) }
    private val thermalGuard by lazy { ThermalGuard(this) }
    private val historyStore by lazy { HistoryStore(this) }
    private val bookmarkStore by lazy { BookmarkStore(this) }
    // registerForActivityResult()はSTARTEDになる前に呼ぶ必要があるため、他のフィールドと違い
    // by lazyにはしない(初回参照タイミングが遅れて登録できなくなる可能性があるため)。
    private val permissionManager = RuntimePermissionManager(this)
    // 2026-08、ドロワーの「ファイルを開く」ボタン用。RuntimePermissionManagerと同じ理由で
    // by lazyにしない。ピック後はcontent:// URIをそのままnavigateForegroundTo()へ渡す
    // (fetchHtml/loadImage側でcontent://を読める経路を別途用意してある)。
    private val localFilePicker = LocalFilePicker(this) { uri -> navigateForegroundTo(uri.toString()) }
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
    private lateinit var engineFrame: EngineFrameLayout
    private lateinit var tabManager: TabManager
    private lateinit var tabBarView: TabBarView

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        permissionManager.requestAllIfNeeded()

        engineViewRoot = RendererFactory.create(this)
        engineHost = engineViewRoot as EngineHostView

        tabManager = TabManager(sessionFactory = { url -> buildSession(url) })

        // device shortcuts(.rjs)は常にその時点のフォアグラウンドタブに対して働く。
        // providerで動的にtabManager.foregroundSession()を参照するため、1回作るだけでよい
        // (bshEngineの旧設計と違い、タブ切替のたびに作り直す必要がない)。
        deviceEngine = DeviceScriptEngine(this, buildShortcutApi())
        deviceEngine.registerAll(RjsShortcutScanner.scan(assets))

        // 画面の組み立て(mainContainer/アドレスバー/PiP枠/ローディング表示/ドロワー枠/
        // insets)は全てEngineFrameLayoutに委譲する。このActivityはエンジンViewを渡すだけ。
        engineFrame = EngineFrameLayout(this, engineViewRoot)
        engineFrame.addressBarView.onSubmit = { url -> navigateForegroundTo(url) }

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
            onOpenLocalFileRequested = { localFilePicker.launch() },
            currentUrlProvider = { currentPageUrl },
            currentTitleProvider = { tabManager.foregroundSession()?.title ?: currentPageUrl },
            onFindInPage = { query -> findInPage?.search(query) },
            onFindNext = { findInPage?.next() },
            onFindPrevious = { findInPage?.previous() },
            onFindClear = { findInPage?.clear() },
            findStatusProvider = {
                val controller = findInPage
                if (controller == null || controller.query.isBlank()) {
                    ""
                } else if (controller.matchCount == 0) {
                    "該当なし"
                } else {
                    "${controller.currentMatchNumber}/${controller.matchCount}件"
                }
            },
            onZoomDelta = { delta ->
                tabManager.foregroundSession()?.layoutEngine?.let { engine ->
                    engine.setZoom(engine.zoomScale + delta)
                    engineHost.requestLayoutPass()
                }
            },
            onZoomReset = {
                tabManager.foregroundSession()?.layoutEngine?.let { engine ->
                    engine.resetZoom()
                    engineHost.requestLayoutPass()
                }
            },
            zoomPercentProvider = {
                ((tabManager.foregroundSession()?.layoutEngine?.zoomScale ?: 1f) * 100).toInt()
            },
            tabBarView = tabBarView,
        ).apply {
            onBackRequested = { goBack() }
            onForwardRequested = { goForward() }
            onReloadRequested = { reloadCurrentTab() }
            onStopRequested = { stopLoading() }
        }

        engineFrame.attachEndDrawer(
            drawerView = debugDrawer,
            onOpened = { debugDrawer.setAutoRefresh(true) },
            onClosed = { debugDrawer.setAutoRefresh(false) },
        )

        setContentView(engineFrame)

        // 2026-07、GLSurfaceViewの初回サーフェス生成がウィンドウフォーカス遷移待ちで
        // 15秒以上かかることがある不具合(RENDER_DIAGログで確認済み、詳細はGLEngineView.kt
        // 側のnudgeSurfaceCreation()コメント参照)への対策。実ページの読み込み完了
        // (fetch+パース+JS評価で数秒~十数秒かかる)を待ってからattach()するのではなく、
        // 空のプレースホルダーで先にattach()し、GLSurfaceView側の重い登録処理を
        // できるだけ早いタイミングで開始させる。実ページ読み込み完了時のattach()は
        // 既存レンダラーがある2回目以降の経路(updateLayoutEngine)を通るだけなので、
        // setRenderer()の「1インスタンス1回」制限には抵触しない。
        attachOpeningScreen()

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
                    ::engineFrame.isInitialized && engineFrame.isDrawerOpen(Gravity.END) ->
                        engineFrame.closeDrawer(Gravity.END)
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
        if (::engineFrame.isInitialized) {
            engineFrame.toggleEndDrawer()
        }
    }

    override fun onNewIntent(intent: Intent) {
        super.onNewIntent(intent)
        setIntent(intent)
        navigateForegroundTo(resolveInitialUrl(intent))
    }

    /** Geolocation等、OSのランタイム権限ダイアログの結果をBrowserCapabilityBridgeへ橋渡しする。 */
    override fun onRequestPermissionsResult(
        requestCode: Int,
        permissions: Array<out String>,
        grantResults: IntArray,
    ) {
        super.onRequestPermissionsResult(requestCode, permissions, grantResults)
        capabilityBridge.onLocationPermissionResult(requestCode, grantResults)
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
        if (::engineFrame.isInitialized) {
            engineFrame.loadingIndicator.visibility = if (loading) View.VISIBLE else View.GONE
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
        // 2026-08、<a>タグのタップ遷移対応。EngineHostView(GLEngineView/EngineView)側は
        // 「どのhrefがタップされたか」しか知らないため、相対URL解決(resolveUrl、baseUrlは
        // このタブの現在のページURL)と実際のタブ内遷移(navigateForegroundTo、履歴記録・
        // WakeLock解放等を含む一連の処理)はここでまとめて行う。session.urlをクロージャで
        // 捕捉しているため、このタブが別ページへ遷移してapplyForeground()が再度呼ばれれば
        // (=このラムダごと新しいsession.urlの値で差し替えられるため)、常にそのタブの
        // 「今開いているページ」を基準にhrefが解決される。
        engineHost.onNavigate = { href -> navigateForegroundTo(resolveUrl(session.url, href)) }
        currentPageUrl = session.url
        engineFrame.addressBarView.setUrl(session.url)
        recordHistoryVisit(session.url, session.title)
        // タブ切替のたびに作り直す(実ブラウザ同様、ページ内検索の状態はタブをまたいで
        // 引き継がない。旧コントローラのハイライトは古いroot/layoutEngineを参照した
        // ままなので、明示的にclear()してから捨てる)。
        findInPage?.clear()
        findInPage = FindInPageController(session.root, session.layoutEngine) { engineHost.requestLayoutPass() }
    }

    private var findInPage: FindInPageController? = null

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
        val pipContainer = engineFrame.pipContainer
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
                android.widget.LinearLayout.LayoutParams(pxSize, (pxSize * 0.65f).toInt()).apply {
                    setMargins(0, dp(4), dp(4), 0)
                },
            )
        }
    }

    private fun dp(value: Int): Int = (value * resources.displayMetrics.density).toInt()

    /**
     * オープニング画面(空のプレースホルダー)をengineHostへ先行attachする。
     * 目的はGLSurfaceView側のサーフェス生成処理をできるだけ早く開始させることだけであり、
     * このLayoutEngine自体はTabSessionにもtabManagerにも登録しない(実ページ読み込み完了時、
     * applyForeground()が本物のLayoutEngineでengineHost.attach()を呼び直して置き換える)。
     */
    private fun attachOpeningScreen() {
        // 2026-08、attachOpeningScreen()自体が呼ばれているか/内部で例外が起きて
        // 握りつぶされていないかを確定させるための診断ログ。原因特定まで残すこと。
        com.B.b.Renderer.debug.BehaviorAuditLog.record(
            com.B.b.Renderer.debug.BehaviorAuditLog.Category.RENDER_DIAG,
            "attachOpeningScreen() entered",
        )
        try {
            val displayMetrics = resources.displayMetrics
            val openingRoot = htmlParser.parseDocument(OPENING_SCREEN_HTML)
            val openingStylesheet = CssParser().parse("")
            StyleResolver(openingStylesheet, density = displayMetrics.density).resolveTree(openingRoot)
            val openingEngine = LayoutEngine(
                root = openingRoot,
                viewportWidth = displayMetrics.widthPixels.toFloat(),
                viewportHeight = usableContentHeightPx(),
            )
            openingEngine.runLayoutPass()
            com.B.b.Renderer.debug.BehaviorAuditLog.record(
                com.B.b.Renderer.debug.BehaviorAuditLog.Category.RENDER_DIAG,
                "attachOpeningScreen() built LayoutEngine, calling engineHost.attach()",
            )
            engineHost.attach(openingEngine)
        } catch (e: Exception) {
            com.B.b.Renderer.debug.BehaviorAuditLog.record(
                com.B.b.Renderer.debug.BehaviorAuditLog.Category.RENDER_DIAG,
                "attachOpeningScreen() FAILED: ${e.javaClass.simpleName}: ${e.message}",
            )
        }
    }

    /** URL取得・パース・スタイル解決・レイアウト・JS/HTMXエンジン一式を新規に組み立てる。タブ1つ分の構築。 */
    private suspend fun buildSession(url: String): TabSession {
        // View由来の値なので、withContextでメインスレッドを離れる前にここで確定させる
        // (詳細はusableContentHeightPx()のコメント参照)。
        val usableHeightPx = usableContentHeightPx()

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
            // @media (min-width/max-width) はCSS px(=物理px÷density)基準で判定する必要がある
            // (2026-08訂正)。以前は物理pxをそのまま渡していたため、例えば`@media (min-width:
            // 768px)`のような一般的なブレークポイントが、物理解像度の大きいスマホでは
            // 「実際にはCSS px換算で768pxも無い狭い画面」なのに条件成立してしまっていた
            // (=タブレット/PC向けCSSがスマホでも誤って適用される不具合)。
            // displayMetricsはこの後のLayoutEngine構築でも使うため、ここに繰り上げてある。
            val displayMetrics = resources.displayMetrics
            val cssPxViewportWidth = displayMetrics.widthPixels / displayMetrics.density
            // vw/vh解決用。高さはusableHeightPx(アドレスバー+システムバー分を除いた
            // 実描画高さ、物理px)をCSS px化したもの。widthPixelsをそのまま使うwidthと
            // 違い、高さは生のdisplayMetrics.heightPixelsではなくこちらを使う必要がある
            // (詳細はusableContentHeightPx()のコメント参照。同じ理由でLayoutEngineの
            // viewportHeightもusableHeightPxを使っている)。
            val cssPxViewportHeight = usableHeightPx / displayMetrics.density
            val stylesheet = CssParser().parse(css, viewportWidth = cssPxViewportWidth)
            val styleResolver = StyleResolver(
                stylesheet,
                density = displayMetrics.density,
                viewportWidthCssPx = cssPxViewportWidth,
                viewportHeightCssPx = cssPxViewportHeight,
            )
            styleResolver.resolveTree(root)

            // 2026-08、診断用。CSS取得〜解決の経路のどこで想定と食い違っているか
            // (fetchStylesheets自体が空を返している/パース結果のルール数がおかしい等)を
            // 切り分けるためのログ。margin:auto中央寄せがexample.comで反映されない
            // 事象の調査用に追加。
            com.B.b.Renderer.debug.BehaviorAuditLog.record(
                com.B.b.Renderer.debug.BehaviorAuditLog.Category.RENDER_DIAG,
                "css fetched: ${css.length} chars, rules parsed: ${stylesheet.rules.size}, " +
                    "preview=${css.take(120).replace("\n", " ")}",
            )

            // onImageNeededの中でlayoutEngine自身(scheduleLayoutPass呼び出し用)を参照する
            // 必要があるが、コンストラクタ引数の時点ではまだ変数が存在しないため、
            // 既存のjsEngineRefと同じ「lateinit var+後から代入」パターンで自己参照する。
            lateinit var layoutEngineRef: LayoutEngine
            val layoutEngine = LayoutEngine(
                root = root,
                viewportWidth = displayMetrics.widthPixels.toFloat(),
                viewportHeight = usableHeightPx,
                onImageNeeded = { imgElement -> loadImage(imgElement, layoutEngineRef, url) },
            )
            layoutEngineRef = layoutEngine
            layoutEngine.currentPath = url
            // LayoutEngineは生成しただけでは座標を計算しない(scheduleLayoutPass/runLayoutPassを
            // 呼んで初めてcomputedRectが埋まる)。ここを呼び忘れると全要素がLayoutRect(0,0,0,0)の
            // ままになり、GPU/Canvasどちらの描画パスでも「サイズ0の矩形」しか描かれず、
            // 画面が白い(あるいは背景色のまま)になる(2026-07白画面調査で発覚。別セッション由来の修正)。
            layoutEngine.runLayoutPass()

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
            jsEngine.window.onOpenPopup = { popupUrl ->
                CoroutineScope(Dispatchers.Main).launch { openNewTab(popupUrl) }
            }
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
        // 2026-08、file://対応。毎回Termux等でローカルサーバーを立てないと動作確認できない
        // 開発上の不便さの解消のため(実装作業自体のテストに使うページを直接file://で
        // 開けるようにする)。OkHttpClientはhttp(s)専用なので、file://だけ別経路に分ける。
        if (url.startsWith("file://", ignoreCase = true)) {
            return readLocalFile(url)
        }
        // 2026-08、content://対応(LocalFilePicker経由、ドロワーの「ファイルを開く」ボタン)。
        // SAFのファイルピッカーが返すURIはfile://ではなくcontent://なので別経路が要る
        // (file://と違い、パスがプロバイダ固有の不透明なIDのためjava.io.Fileでは開けない。
        // ContentResolver経由で読む)。
        if (url.startsWith("content://", ignoreCase = true)) {
            return readContentUri(url)
        }
        val response = okHttpClient.newCall(Request.Builder().url(url).build()).execute()
        return response.body?.string() ?: ""
    }

    /**
     * content://スキームの読み込み(2026-08、LocalFilePicker(SAF)対応で追加)。
     *
     * 既知の制約: content:// URIは`java.net.URI.resolve()`によるfile://同様の相対パス解決が
     * 効かない(パス部分がプロバイダ固有の不透明なIDで、ファイルシステムのパスではないため)。
     * つまりcontent://経由で開いたHTML内の相対パス画像参照(`<img src="images/x.png">`等)は
     * 解決に失敗する。絶対URL(https://...)の画像参照か、file://で自己完結したページなら
     * この制約を受けない。将来的にSAFのDocumentsContract経由で兄弟ドキュメントを解決する
     * 対応も考えられるが、今回は「ファイルを開くボタン」の主目的(単体HTMLの動作確認)を
     * 満たせば十分としてスコープ外にした。
     */
    private fun readContentUri(contentUrl: String): String {
        return runCatching {
            contentResolver.openInputStream(Uri.parse(contentUrl))?.bufferedReader()?.readText() ?: ""
        }.getOrElse { e ->
            BehaviorAuditLog.record(
                BehaviorAuditLog.Category.RENDER_DIAG,
                "content:// read failed: $contentUrl (${e.javaClass.simpleName}: ${e.message})",
            )
            ""
        }
    }

    /**
     * file://スキームのローカルHTML読み込み(2026-08対応)。
     *
     * 対応範囲・既知の制約:
     *   - Android 10(API 29)以降はスコープドストレージにより、アプリ専用ディレクトリ
     *     (`getExternalFilesDir(null)`。権限無しでアクセス可)以外の公開ストレージ
     *     (`/storage/emulated/0/Download`等)への直接File I/Oは、OS・端末の設定次第で
     *     失敗し得る(`android:requestLegacyExternalStorage`はtargetSdk 30以降では
     *     効果が無いため、この設定では救えない)。**確実に読めるのはアプリ専用
     *     ディレクトリ配下のファイルのみ**という前提で、README.mdに配置場所の案内を
     *     追記してある。
     *   - この制約を受けずに公開ストレージのどこにあるファイルでも開きたい場合は、
     *     ドロワーの「📁開く」ボタン(LocalFilePicker、SAF経由でcontent:// URIを取得)を
     *     使うこと。こちらはfile://直指定と違い権限制約を受けない
     *     (readContentUri()参照。ただし相対パスでの画像参照解決はfile://の方が確実、
     *     という逆方向の制約がある)。
     *   - 読み込み失敗時は空文字列を返す(呼び出し元のfetchHtml→errorPageHtmlの
     *     フローに自然に乗り、クラッシュはしない)。
     */
    private fun readLocalFile(fileUrl: String): String {
        return runCatching {
            val path = Uri.parse(fileUrl).path ?: return@runCatching ""
            java.io.File(path).readText()
        }.getOrElse { e ->
            BehaviorAuditLog.record(
                BehaviorAuditLog.Category.RENDER_DIAG,
                "local file read failed: $fileUrl (${e.javaClass.simpleName}: ${e.message})",
            )
            ""
        }
    }

    /**
     * ページ本文(LayoutEngine)が実際に使える描画領域の高さをpx単位で返す。
     *
     * 2026-08、ひかるからの指摘で発覚: LayoutEngineのviewportHeightはこれまで
     * resources.displayMetrics.heightPixels(=端末の生の画面の高さ)をそのまま渡して
     * いたが、実際にページが描画されるcontentView(GLSurfaceView/CanvasView)は
     * EngineFrameLayout.applyChromeInsets()でシステムバー+アドレスバー分の
     * topMarginを取られており、その分だけ実際の描画面積より高いキャンバスを
     * 前提にレイアウト計算していたことになる。position:fixedの要素の位置や、
     * ページ末尾の余白量が実際の表示領域とズレる原因になっていた
     * (「Webページの描写位置がズレてる」という指摘の実体はこれ)。
     *
     * widthについては、EngineFrameLayoutがcontentViewに左右のmarginを一切
     * 付けていない(topMarginのみ)ため、displayMetrics.widthPixelsをそのまま
     * 使う既存の実装で正しい。ここでheight側だけ補正しているのはそのため。
     *
     * engineFrameがまだwindowにattachされておらずinsetsを取得できない場合
     * (理論上起こりにくいが安全側として)は、システムバー分を0として扱う
     * (=アドレスバー分だけ差し引いた値になる)。
     *
     * View由来の値を読むため、呼び出しは(withContext等でメインスレッドを
     * 離れる前の)メインスレッド上で行うこと。
     */
    private fun usableContentHeightPx(): Float {
        val topInset = ViewCompat.getRootWindowInsets(engineFrame)
            ?.getInsets(WindowInsetsCompat.Type.systemBars())?.top ?: 0
        val addressBarHeightPx = EngineFrameLayout.ADDRESS_BAR_HEIGHT_DP * resources.displayMetrics.density
        return resources.displayMetrics.heightPixels - topInset - addressBarHeightPx
    }

    /**
     * <img src="..."> のネットワーク取得+デコード(2026-08対応)。LayoutEngine.layoutImage()が
     * PENDING状態のImageElementを見つけるたびonImageNeeded経由でここが1回だけ呼ばれる
     * (呼び出し側でLOADINGへ遷移済みなので二重fetchはされない)。
     *
     * IOスレッドでfetch+decodeを行い、結果をメインスレッドへ戻してから
     *   1. naturalWidth/naturalHeight/decodedImage/loadStateを更新
     *   2. element.markDirty(LAYOUT) — 親方向へLAYOUT dirtyを伝播させる。これが無いと
     *      layoutBlock()側のdirty==CLEANショートサーキットに阻まれ、再計算が
     *      このimg要素まで届かない。
     *   3. layoutEngine.scheduleLayoutPass() — 実際の再計算(座標確定)
     *   4. engineHost.requestLayoutPass() — GL側の再描画(GLEngineViewのrequestLayoutPassは
     *      redrawのみでrelayoutはしないため、3と4は両方必要。詳細はGLEngineView.kt参照)
     * の順に行う。
     */
    private fun loadImage(element: ImageElement, layoutEngine: LayoutEngine, baseUrl: String) {
        val src = element.attributes["src"]
        if (src.isNullOrBlank()) {
            element.loadState = ImageLoadState.FAILED
            return
        }
        val absoluteUrl = resolveUrl(baseUrl, src)

        CoroutineScope(Dispatchers.IO).launch {
            val bitmap = runCatching {
                // 2026-08、file://対応。fetchHtml()と同じ理由でOkHttpClientの経路とは
                // 分ける(local HTMLから相対パスで参照される画像もfile://で解決される
                // ため、こちらにも対応が必要)。
                if (absoluteUrl.startsWith("file://", ignoreCase = true)) {
                    val path = Uri.parse(absoluteUrl).path
                    path?.let { android.graphics.BitmapFactory.decodeFile(it) }
                } else {
                    val response = okHttpClient.newCall(Request.Builder().url(absoluteUrl).build()).execute()
                    response.body?.bytes()?.let { bytes ->
                        android.graphics.BitmapFactory.decodeByteArray(bytes, 0, bytes.size)
                    }
                }
            }.getOrNull()

            withContext(Dispatchers.Main) {
                if (bitmap != null) {
                    // 画像の自然サイズ(Bitmapのピクセル数)はCSS仕様上「1画像px = 1CSS px」として
                    // 扱われるものであり、物理pxとは別概念(2026-08対応)。LayoutEngine以降は
                    // 物理px基準のワールド座標で統一しているため、ここでdensityを掛けて
                    // 変換しておく(CSS側の長さ解決(StyleResolver)と同じ考え方)。
                    val density = resources.displayMetrics.density
                    element.naturalWidth = (bitmap.width * density).toInt()
                    element.naturalHeight = (bitmap.height * density).toInt()
                    element.decodedImage = bitmap
                    element.loadState = ImageLoadState.LOADED
                } else {
                    element.loadState = ImageLoadState.FAILED
                    BehaviorAuditLog.record(
                        BehaviorAuditLog.Category.RENDER_DIAG,
                        "image fetch/decode failed: $absoluteUrl",
                    )
                }
                element.markDirty(DirtyLevel.LAYOUT)
                layoutEngine.scheduleLayoutPass()
                engineHost.requestLayoutPass()
            }
        }
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

                // media="print" 等、画面表示に使わないシートは取得自体をスキップする
                // (2026-08、CssParserの@media対応と合わせて確認。ここは<link>属性レベルの
                // 条件で、CSS内の@mediaブロックとは別の話。"screen and (...)"のような複合式は
                // 今回非対応で、単純な"print"一致のみ見る簡易実装)。
                val media = Regex("media\\s*=\\s*[\"']([^\"']+)[\"']", RegexOption.IGNORE_CASE)
                    .find(tag)?.groupValues?.get(1)?.trim()?.lowercase()
                if (media == "print") return@forEach

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
