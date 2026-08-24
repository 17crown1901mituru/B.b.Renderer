package com.B.b.Renderer.debug

import android.content.Context
import android.graphics.Color
import android.os.Handler
import android.os.Looper
import android.util.TypedValue
import android.view.Gravity
import android.view.ViewGroup
import android.widget.Button
import android.widget.CheckBox
import android.widget.EditText
import android.widget.HorizontalScrollView
import android.widget.LinearLayout
import android.widget.ScrollView
import android.widget.TextView
import com.B.b.Renderer.benchmark.RenderTierBenchmark
import com.B.b.Renderer.data.BookmarkStore
import com.B.b.Renderer.data.HistoryStore
import com.B.b.Renderer.permissions.GlobalAppSettings
import com.B.b.Renderer.permissions.SitePermissions
import com.B.b.Renderer.tabs.TabBarView
import com.B.b.Renderer.tabs.TabManager

/**
 * sp(=density×fontScale)ではなく、density基準の固定pxでテキストサイズを指定する。
 *
 * 2026-08、ひかるからの報告で発覚: このドロワーの権限チェックボックス・ログ表示等は
 * すべて`textSize = Nf`(暗黙にCOMPLEX_UNIT_SP=density×fontScaleで解決される)で
 * 組んでいたため、Android 14+の「アプリごとの表示サイズ/フォントサイズ」設定で
 * fontScaleだけが変わると、余白・ボタン枠(こちらは既存のdp()ヘルパーでdensity
 * のみを基準にしている)との比率が崩れ、WRAP_CONTENTなボタン・チェックボックスの
 * 枠自体が文字の膨張につられて肥大化し、画面バランスが大きく崩れていた。
 *
 * アドレスバー(AddressBarView)は元々固定高さのdpコンテナで作られており、
 * ステータスバーとの被り・視認性をその前提で調整済みだった。そちらを基準
 * (=拡大縮小設定の影響を受けないのが「あるべき100%」)とみなし、ここでも
 * fontScaleを無視してdensityだけを反映するよう統一する
 * (density自体の変化・端末の「拡大縮小」設定には引き続き追随する。無視するのは
 * fontScale分だけ)。
 */
private fun TextView.setFixedTextSize(spValue: Float) {
    setTextSize(TypedValue.COMPLEX_UNIT_PX, spValue * resources.displayMetrics.density)
}

/**
 * BehaviorAuditLogをその場で見るためのデバッグ用サイドパネル。
 * 加えて、タブ一覧・ドメイン単位のブラウザ機能許可・アプリ全体設定・履歴・ブックマークも
 * ここに集約する(2026-07議論分: ブラウザとしての機能・設定はドロワー側に寄せて、
 * ページ描画領域を画面いっぱいに使えるようにする方針)。
 *
 * 画面上にEngineView(ページ描画)・ソフトウェアキーボード・デバッグ表示が
 * 同時に重なるとごちゃつくため、常時表示ではなくDrawerLayoutで画面端に
 * 隠しておき、必要な時だけ引き出す形にしている(EngineActivity側で
 * DrawerLayoutのendドロワーとして配置する想定)。
 *
 * ここはあくまで「見る・エクスポートする・許可を切り替える・タブを操作する」ための
 * ビューで、ShortcutApiのような実行権限を持つAPIではない。
 */
class DebugDrawerView(
    context: Context,
    private val sitePermissions: SitePermissions? = null,
    private val globalSettings: GlobalAppSettings? = null,
    private val historyStore: HistoryStore? = null,
    private val bookmarkStore: BookmarkStore? = null,
    private val currentDomainProvider: (() -> String)? = null,
    private val onGlobalSettingsChanged: (() -> Unit)? = null,
    private val onNavigateRequested: ((String) -> Unit)? = null,
    // 2026-08、「file://を手打ちしてアプリ専用ディレクトリに手動配置する」運用が面倒だという
    // 指摘への対応。SAF(システム標準のファイルピッカー)を呼び出すのはActivity側の責務
    // (LocalFilePicker、EngineActivity参照)なので、ここでは単純に「押された」ことだけを
    // 伝えるコールバックにしてある。
    private val onOpenLocalFileRequested: (() -> Unit)? = null,
    private val currentUrlProvider: (() -> String)? = null,
    private val currentTitleProvider: (() -> String)? = null,
    private val onFindInPage: ((String) -> Unit)? = null,
    private val onFindNext: (() -> Unit)? = null,
    private val onFindPrevious: (() -> Unit)? = null,
    private val onFindClear: (() -> Unit)? = null,
    private val findStatusProvider: (() -> String)? = null,
    private val onZoomDelta: ((Float) -> Unit)? = null,
    private val onZoomReset: (() -> Unit)? = null,
    private val zoomPercentProvider: (() -> Int)? = null,
    val tabBarView: TabBarView? = null,
) : LinearLayout(context) {

    private val addressBarInput = android.widget.EditText(context).apply {
        hint = "URLまたは検索語句"
        setTextColor(Color.WHITE)
        setHintTextColor(Color.GRAY)
        setFixedTextSize(13f)
        isSingleLine = true
        imeOptions = android.view.inputmethod.EditorInfo.IME_ACTION_GO
        setBackgroundColor(Color.parseColor("#333333"))
        setPadding(dp(10), dp(8), dp(10), dp(8))
    }

    private val bookmarkStarButton = Button(context).apply {
        text = "☆"
        setFixedTextSize(14f)
        setPadding(dp(6), 0, dp(6), 0)
    }

    private val historyPanel = LinearLayout(context).apply {
        orientation = VERTICAL
        setPadding(dp(12), dp(4), dp(12), dp(4))
        visibility = GONE
    }

    private val bookmarksPanel = LinearLayout(context).apply {
        orientation = VERTICAL
        setPadding(dp(12), dp(4), dp(12), dp(4))
        visibility = GONE
    }

    private val findQueryInput = EditText(context).apply {
        hint = "ページ内検索"
        setTextColor(Color.WHITE)
        setHintTextColor(Color.GRAY)
        setFixedTextSize(12f)
        isSingleLine = true
        imeOptions = android.view.inputmethod.EditorInfo.IME_ACTION_SEARCH
        setBackgroundColor(Color.parseColor("#333333"))
        setPadding(dp(8), dp(6), dp(8), dp(6))
    }

    private val findStatusText = TextView(context).apply {
        setTextColor(Color.LTGRAY)
        setFixedTextSize(11f)
        setPadding(dp(6), 0, dp(6), 0)
    }

    private val zoomPercentText = TextView(context).apply {
        setTextColor(Color.LTGRAY)
        setFixedTextSize(11f)
        setPadding(dp(6), 0, dp(6), 0)
    }

    private val logText = TextView(context).apply {
        setTextColor(Color.parseColor("#00FF66"))
        typeface = android.graphics.Typeface.MONOSPACE
        setFixedTextSize(11f)
        setPadding(dp(12), dp(12), dp(12), dp(12))
    }

    private val permissionsPanel = LinearLayout(context).apply {
        orientation = VERTICAL
        setPadding(dp(12), dp(4), dp(12), dp(4))
    }

    private val benchmarkStatusText = TextView(context).apply {
        setTextColor(Color.LTGRAY)
        setFixedTextSize(11f)
    }

    private val refreshHandler = Handler(Looper.getMainLooper())
    private var autoRefresh = false

    // 2026-08、ドロワーのタブ化対応。以前は全パネルを1本のScrollViewに縦に並べていたため、
    // 項目が増えるたびに目的の設定へたどり着くまでのスクロール量が増え続けていた
    // (履歴・ブックマーク・診断ログ・アプリ設定等、性質の異なるものが全部同じ縦一列に
    // 並んでいた)。「ナビ」「検索/表示」「ログ」「設定」の4カテゴリのタブに分け、
    // 1画面あたりの情報量を減らす。各タブの中身は既存のbuildXxx()をそのまま
    // 詰め替えただけで、個々のパネルのロジック自体は変えていない。
    private val navTabContent = LinearLayout(context).apply { orientation = VERTICAL }
    private val viewTabContent = LinearLayout(context).apply { orientation = VERTICAL }
    private val logTabContent = LinearLayout(context).apply { orientation = VERTICAL }
    private val settingsTabContent = LinearLayout(context).apply { orientation = VERTICAL }
    private val tabContents get() = listOf(navTabContent, viewTabContent, logTabContent, settingsTabContent)
    private val tabButtons = mutableListOf<Button>()

    init {
        orientation = VERTICAL
        setBackgroundColor(Color.parseColor("#EE111111"))
        layoutParams = ViewGroup.LayoutParams(dp(320), ViewGroup.LayoutParams.MATCH_PARENT)

        // 常時表示(タブ切替の影響を受けない)固定ヘッダー部分。
        val header = LinearLayout(context).apply { orientation = VERTICAL }
        // 2026-08、「ファイルを差し替えても実機の挙動が変わらない」という報告への
        // 切り分け用の一時的な目印。これが表示されていれば「このコード変更を含む
        // ビルドが実機で動いている」ことの動かぬ証拠になる。役目を終えたら削除してよい。
        header.addView(
            TextView(context).apply {
                text = "🆕 BUILD MARKER 2026-08-23-2350 🆕"
                setTextColor(Color.parseColor("#FF00FF"))
                setBackgroundColor(Color.parseColor("#FFFFFF00"))
                setFixedTextSize(16f)
                gravity = Gravity.CENTER
                setPadding(dp(4), dp(8), dp(4), dp(8))
            },
        )
        header.addView(buildAddressBar())
        header.addView(buildTabStrip())
        addView(header)

        // ナビ: タブ一覧・履歴・ブックマーク(ページ遷移に関わるもの)
        tabBarView?.let {
            navTabContent.addView(buildTabsHeader())
            navTabContent.addView(it, ViewGroup.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT))
        }
        navTabContent.addView(buildHistoryHeader())
        navTabContent.addView(historyPanel)
        navTabContent.addView(buildBookmarksHeader())
        navTabContent.addView(bookmarksPanel)

        // 検索/表示: ページ内検索・ズーム(今見ているページの見え方に関わるもの)
        viewTabContent.addView(buildFindInPagePanel())
        viewTabContent.addView(buildZoomPanel())

        // ログ: Behavior Audit Log・描画Tierベンチマーク(診断用)
        logTabContent.addView(buildToolbar())
        logTabContent.addView(logText)
        logTabContent.addView(buildRenderBenchmarkPanel())

        // 設定: アプリ全体設定・ドメイン単位の許可設定
        settingsTabContent.addView(buildGlobalSettingsPanel())
        settingsTabContent.addView(buildPermissionsHeader())
        settingsTabContent.addView(permissionsPanel)

        val scrollBody = LinearLayout(context).apply { orientation = VERTICAL }
        tabContents.forEach { scrollBody.addView(it) }
        addView(
            ScrollView(context).apply { addView(scrollBody) },
            LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, 0, 1f),
        )

        selectTab(0)
        refresh()
    }

    /**
     * タブ選択バー。選択中のタブだけ背景色を変えて示す(アイコン等は使わず、
     * 既存のsmallButton()と同じ最小限のButtonベースで統一感を保つ)。
     */
    private fun buildTabStrip(): LinearLayout {
        val strip = LinearLayout(context).apply {
            orientation = HORIZONTAL
            setPadding(dp(4), dp(4), dp(4), dp(4))
        }
        listOf("ナビ", "検索/表示", "ログ", "設定").forEachIndexed { index, label ->
            val button = Button(context).apply {
                text = label
                setFixedTextSize(11f)
                setPadding(dp(2), 0, dp(2), 0)
                layoutParams = LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f)
                setOnClickListener { selectTab(index) }
            }
            tabButtons.add(button)
            strip.addView(button)
        }
        return strip
    }

    private fun selectTab(index: Int) {
        tabContents.forEachIndexed { i, panel -> panel.visibility = if (i == index) VISIBLE else GONE }
        tabButtons.forEachIndexed { i, button ->
            button.setBackgroundColor(if (i == index) Color.parseColor("#4A86E8") else Color.parseColor("#333333"))
            button.setTextColor(Color.WHITE)
        }
    }

    /** ドメインに依存しない、アプリ全体の設定(ユーザー起因の要求・UA・サードパーティCookie既定) */
    private fun buildGlobalSettingsPanel(): LinearLayout {
        val panel = LinearLayout(context).apply {
            orientation = VERTICAL
            setPadding(dp(12), dp(4), dp(12), dp(8))
        }
        val settings = globalSettings ?: return panel

        panel.addView(
            TextView(context).apply {
                text = "アプリ全体の設定"
                setTextColor(Color.LTGRAY)
                setFixedTextSize(12f)
            },
        )
        panel.addView(
            CheckBox(context).apply {
                text = "常に画面をスリープさせない(ユーザー設定)"
                setTextColor(Color.WHITE)
                setFixedTextSize(11f)
                isChecked = settings.userKeepScreenOn
                setOnCheckedChangeListener { _, checked ->
                    settings.userKeepScreenOn = checked
                    onGlobalSettingsChanged?.invoke()
                }
            },
        )
        panel.addView(
            CheckBox(context).apply {
                text = "振動を許可する(ユーザー設定)"
                setTextColor(Color.WHITE)
                setFixedTextSize(11f)
                isChecked = settings.userVibrationEnabled
                setOnCheckedChangeListener { _, checked -> settings.userVibrationEnabled = checked }
            },
        )
        panel.addView(
            CheckBox(context).apply {
                text = "サードパーティCookieを既定でブロック"
                setTextColor(Color.WHITE)
                setFixedTextSize(11f)
                isChecked = settings.blockThirdPartyCookies
                setOnCheckedChangeListener { _, checked -> settings.blockThirdPartyCookies = checked }
            },
        )
        panel.addView(
            TextView(context).apply {
                text = "User-Agent"
                setTextColor(Color.LTGRAY)
                setFixedTextSize(10f)
                setPadding(0, dp(4), 0, 0)
            },
        )
        panel.addView(
            EditText(context).apply {
                setText(settings.userAgent)
                setTextColor(Color.WHITE)
                setFixedTextSize(10f)
                setOnFocusChangeListener { _, hasFocus ->
                    if (!hasFocus) settings.userAgent = text.toString().ifBlank { GlobalAppSettings.DEFAULT_USER_AGENT }
                }
            },
        )
        return panel
    }

    /** ピンチ操作の補助・アクセシビリティ向けに、ボタンでもズームできるようにする。 */
    private fun buildZoomPanel(): LinearLayout {
        val row = LinearLayout(context).apply {
            orientation = HORIZONTAL
            gravity = Gravity.CENTER_VERTICAL
            setPadding(dp(12), dp(4), dp(12), dp(4))
        }
        row.addView(
            TextView(context).apply {
                text = "ズーム"
                setTextColor(Color.LTGRAY)
                setFixedTextSize(12f)
            },
        )
        row.addView(smallButton("－") { onZoomDelta?.invoke(-0.1f); refreshZoomStatus() })
        row.addView(zoomPercentText)
        row.addView(smallButton("＋") { onZoomDelta?.invoke(0.1f); refreshZoomStatus() })
        row.addView(smallButton("リセット") { onZoomReset?.invoke(); refreshZoomStatus() })
        refreshZoomStatus()
        return row
    }

    private fun refreshZoomStatus() {
        val percent = zoomPercentProvider?.invoke() ?: 100
        zoomPercentText.text = "$percent%"
    }

    /** ページ内検索。要素単位のハイライト(FindInPageController)をこちらから叩くだけの薄いUI。 */
    private fun buildFindInPagePanel(): LinearLayout {
        val column = LinearLayout(context).apply {
            orientation = VERTICAL
            setPadding(dp(12), dp(4), dp(12), dp(4))
        }
        column.addView(
            TextView(context).apply {
                text = "ページ内検索"
                setTextColor(Color.LTGRAY)
                setFixedTextSize(12f)
            },
        )
        val row = LinearLayout(context).apply {
            orientation = HORIZONTAL
            gravity = Gravity.CENTER_VERTICAL
        }
        row.addView(findQueryInput, LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f))
        findQueryInput.setOnEditorActionListener { _, actionId, _ ->
            if (actionId == android.view.inputmethod.EditorInfo.IME_ACTION_SEARCH) {
                onFindInPage?.invoke(findQueryInput.text.toString())
                refreshFindStatus()
                true
            } else {
                false
            }
        }
        row.addView(smallButton("前へ") { onFindPrevious?.invoke(); refreshFindStatus() })
        row.addView(smallButton("次へ") { onFindNext?.invoke(); refreshFindStatus() })
        row.addView(
            smallButton("×") {
                findQueryInput.setText("")
                onFindClear?.invoke()
                refreshFindStatus()
            },
        )
        column.addView(row)
        column.addView(findStatusText)
        refreshFindStatus()
        return column
    }

    private fun refreshFindStatus() {
        findStatusText.text = findStatusProvider?.invoke() ?: ""
    }

    /** GPUかCanvasかの起動時判定(RenderTierBenchmark)の状態表示・手動リセット */
    private fun buildRenderBenchmarkPanel(): LinearLayout {
        val panel = LinearLayout(context).apply {
            orientation = VERTICAL
            setPadding(dp(12), dp(4), dp(12), dp(8))
        }
        panel.addView(
            TextView(context).apply {
                text = "描画Tierベンチマーク"
                setTextColor(Color.LTGRAY)
                setFixedTextSize(12f)
            },
        )
        panel.addView(benchmarkStatusText)
        panel.addView(
            smallButton("リセットして再計測") {
                RenderTierBenchmark.reset(context)
                refreshBenchmarkStatus()
            },
        )
        refreshBenchmarkStatus()
        return panel
    }

    private fun refreshBenchmarkStatus() {
        // たまたま1回だけ重かった/軽かったが結果を左右しないよう複数セッションの多数決で確定する
        // 設計になっているため、確定前はPENDING扱いであることが分かるよう明示する。
        benchmarkStatusText.text = when (RenderTierBenchmark.currentVerdict(context)) {
            RenderTierBenchmark.Verdict.UNKNOWN -> "判定中(複数回の起動で確定します)"
            RenderTierBenchmark.Verdict.GPU_OK -> "GPU描画で確定済み"
            RenderTierBenchmark.Verdict.GPU_SLOW -> "この端末には重いためCanvas描画に固定済み"
        }
    }

    /**
     * ボタンタップ時に同期でSQLiteへ書く。1行のinsert/delete程度の軽い処理であり、
     * GlobalAppSettings(SharedPreferences)への同期書き込みと同様の粒度なのでUIスレッドで許容する。
     * 履歴の記録(recordHistoryVisit)はページ遷移のたびに走るため、そちらはEngineActivity側で
     * IOディスパッチャに逃がしている。
     */
    private fun toggleBookmarkForCurrentPage() {
        val store = bookmarkStore ?: return
        val url = currentUrlProvider?.invoke().orEmpty()
        if (url.isBlank()) return
        val title = currentTitleProvider?.invoke() ?: url
        val nowBookmarked = store.toggle(url, title)
        bookmarkStarButton.text = if (nowBookmarked) "★" else "☆"
        refreshBookmarks()
    }

    private fun refreshBookmarkStar() {
        val store = bookmarkStore ?: return
        val url = currentUrlProvider?.invoke().orEmpty()
        bookmarkStarButton.text = if (url.isNotBlank() && store.isBookmarked(url)) "★" else "☆"
    }

    private fun buildHistoryHeader(): LinearLayout {
        val row = LinearLayout(context).apply {
            orientation = HORIZONTAL
            gravity = Gravity.CENTER_VERTICAL
            setPadding(dp(12), dp(8), dp(12), dp(0))
        }
        row.addView(
            TextView(context).apply {
                text = "履歴"
                setTextColor(Color.LTGRAY)
                setFixedTextSize(12f)
                layoutParams = LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f)
            },
        )
        row.addView(
            smallButton("表示/非表示") {
                historyPanel.visibility = if (historyPanel.visibility == VISIBLE) GONE else VISIBLE
                if (historyPanel.visibility == VISIBLE) refreshHistory()
            },
        )
        row.addView(smallButton("全削除") { historyStore?.clearAll(); refreshHistory() })
        return row
    }

    private fun refreshHistory() {
        historyPanel.removeAllViews()
        val store = historyStore ?: return
        val entries = store.recent(100)
        if (entries.isEmpty()) {
            historyPanel.addView(
                TextView(context).apply {
                    text = "(履歴なし)"
                    setTextColor(Color.GRAY)
                    setFixedTextSize(11f)
                },
            )
            return
        }
        entries.forEach { entry ->
            historyPanel.addView(
                buildListRow(
                    title = entry.title,
                    url = entry.url,
                    onTap = { onNavigateRequested?.invoke(entry.url) },
                    onDelete = { store.delete(entry.id); refreshHistory() },
                ),
            )
        }
    }

    private fun buildBookmarksHeader(): LinearLayout {
        val row = LinearLayout(context).apply {
            orientation = HORIZONTAL
            gravity = Gravity.CENTER_VERTICAL
            setPadding(dp(12), dp(8), dp(12), dp(0))
        }
        row.addView(
            TextView(context).apply {
                text = "ブックマーク"
                setTextColor(Color.LTGRAY)
                setFixedTextSize(12f)
                layoutParams = LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f)
            },
        )
        row.addView(
            smallButton("表示/非表示") {
                bookmarksPanel.visibility = if (bookmarksPanel.visibility == VISIBLE) GONE else VISIBLE
                if (bookmarksPanel.visibility == VISIBLE) refreshBookmarks()
            },
        )
        return row
    }

    private fun refreshBookmarks() {
        bookmarksPanel.removeAllViews()
        val store = bookmarkStore ?: return
        val entries = store.list()
        if (entries.isEmpty()) {
            bookmarksPanel.addView(
                TextView(context).apply {
                    text = "(ブックマークなし)"
                    setTextColor(Color.GRAY)
                    setFixedTextSize(11f)
                },
            )
            return
        }
        entries.forEach { entry ->
            bookmarksPanel.addView(
                buildListRow(
                    title = entry.title,
                    url = entry.url,
                    onTap = { onNavigateRequested?.invoke(entry.url) },
                    onDelete = { store.delete(entry.id); refreshBookmarks(); refreshBookmarkStar() },
                ),
            )
        }
    }

    /** 履歴・ブックマーク共通の1行分の見た目: タップで遷移、×で削除。 */
    private fun buildListRow(title: String, url: String, onTap: () -> Unit, onDelete: () -> Unit): LinearLayout {
        val row = LinearLayout(context).apply {
            orientation = HORIZONTAL
            gravity = Gravity.CENTER_VERTICAL
            setPadding(0, dp(4), 0, dp(4))
        }
        val textColumn = LinearLayout(context).apply {
            orientation = VERTICAL
            layoutParams = LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f)
            setOnClickListener { onTap() }
        }
        textColumn.addView(
            TextView(context).apply {
                text = title.ifBlank { url }
                setTextColor(Color.WHITE)
                setFixedTextSize(12f)
                maxLines = 1
            },
        )
        textColumn.addView(
            TextView(context).apply {
                text = url
                setTextColor(Color.GRAY)
                setFixedTextSize(10f)
                maxLines = 1
            },
        )
        row.addView(textColumn)
        row.addView(smallButton("×") { onDelete() })
        return row
    }

    private fun buildPermissionsHeader(): TextView =
        TextView(context).apply {
            text = "このドメインの許可設定"
            setTextColor(Color.LTGRAY)
            setFixedTextSize(12f)
            setPadding(dp(12), dp(8), dp(12), dp(0))
        }

    private fun refreshPermissions() {
        permissionsPanel.removeAllViews()
        val permissions = sitePermissions ?: return
        val domain = currentDomainProvider?.invoke().orEmpty()
        if (domain.isBlank()) {
            permissionsPanel.addView(
                TextView(context).apply {
                    text = "(ページ未読み込み)"
                    setTextColor(Color.GRAY)
                    setFixedTextSize(11f)
                },
            )
            return
        }
        permissionsPanel.addView(
            TextView(context).apply {
                text = domain
                setTextColor(Color.WHITE)
                setFixedTextSize(12f)
                setPadding(0, 0, 0, dp(4))
            },
        )
        SitePermissions.Capability.values().forEach { capability ->
            permissionsPanel.addView(
                CheckBox(context).apply {
                    text = capability.name
                    setTextColor(Color.WHITE)
                    setFixedTextSize(11f)
                    isChecked = permissions.isAllowed(domain, capability)
                    setOnCheckedChangeListener { _, checked ->
                        permissions.setAllowed(domain, capability, checked)
                    }
                },
            )
        }
    }

    private fun buildAddressBar(): LinearLayout {
        val column = LinearLayout(context).apply { orientation = VERTICAL }

        val navRow = LinearLayout(context).apply {
            orientation = HORIZONTAL
            gravity = Gravity.CENTER_VERTICAL
            setPadding(dp(8), dp(4), dp(8), 0)
        }
        navRow.addView(smallButton("←") { onBackRequested?.invoke() })
        navRow.addView(smallButton("→") { onForwardRequested?.invoke() })
        navRow.addView(smallButton("更新") { onReloadRequested?.invoke() })
        navRow.addView(smallButton("×中止") { onStopRequested?.invoke() })
        // 2026-08、ローカルHTMLの動作確認用。file://を手打ちする代わりに、システムの
        // ファイルピッカー(SAF)を呼び出す。実処理(ActivityResultContractsの起動)は
        // EngineActivity側のLocalFilePickerが担い、ここではボタンを押されたことだけを
        // 伝える(このViewはActivity参照を持たない設計のため)。
        navRow.addView(smallButton("📁開く") { onOpenLocalFileRequested?.invoke() })
        // 2026-08、ボタン最小幅を詰めても(smallButton()参照)、今後さらにボタンが
        // 増えた時に再び同じ問題(はみ出したボタンが完全に不可視になる)が起きないよう、
        // 念のためHorizontalScrollViewで包んでおく。はみ出した場合はクリップされず
        // 横スクロールで見えるようになる。
        column.addView(
            HorizontalScrollView(context).apply {
                isHorizontalScrollBarEnabled = false
                addView(navRow)
            },
        )

        val row = LinearLayout(context).apply {
            orientation = HORIZONTAL
            gravity = Gravity.CENTER_VERTICAL
            setPadding(dp(8), dp(8), dp(8), dp(4))
        }
        row.addView(
            addressBarInput,
            LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f),
        )
        addressBarInput.setOnEditorActionListener { _, actionId, _ ->
            if (actionId == android.view.inputmethod.EditorInfo.IME_ACTION_GO) {
                submitAddressBar()
                true
            } else {
                false
            }
        }
        row.addView(smallButton("移動") { submitAddressBar() })
        bookmarkStarButton.setOnClickListener { toggleBookmarkForCurrentPage() }
        row.addView(bookmarkStarButton)
        // 現在のURLをアドレスバーに反映しておく(タブ切替時等はrefresh()から呼ばれる)
        currentDomainProvider?.let { addressBarInput.hint = "URLまたは検索語句" }
        column.addView(row)
        return column
    }

    var onBackRequested: (() -> Unit)? = null
    var onForwardRequested: (() -> Unit)? = null
    var onReloadRequested: (() -> Unit)? = null
    var onStopRequested: (() -> Unit)? = null

    private fun submitAddressBar() {
        val input = addressBarInput.text.toString().trim()
        if (input.isBlank()) return
        onNavigateRequested?.invoke(resolveAddressBarInput(input))
    }

    /**
     * 入力がURLか検索語句かを簡易判定する。
     *   - http(s)://で始まる → そのまま
     *   - 空白を含まず、ドットを含む(example.com等) → https://を補ってURL扱い
     *   - それ以外 → 検索エンジンのテンプレートに埋め込む
     */
    private fun resolveAddressBarInput(input: String): String {
        if (input.startsWith("http://") || input.startsWith("https://")) return input
        val looksLikeDomain = !input.contains(" ") && input.contains(".")
        if (looksLikeDomain) return "https://$input"
        val template = globalSettings?.searchEngineUrlTemplate ?: GlobalAppSettings.DEFAULT_SEARCH_TEMPLATE
        val encoded = java.net.URLEncoder.encode(input, "UTF-8")
        return template.replace("%s", encoded)
    }

    private fun buildTabsHeader(): TextView =
        TextView(context).apply {
            text = "タブ"
            setTextColor(Color.LTGRAY)
            setFixedTextSize(12f)
            setPadding(dp(12), dp(8), dp(12), dp(0))
        }

    private fun buildToolbar(): LinearLayout {
        val toolbar = LinearLayout(context).apply {
            orientation = HORIZONTAL
            gravity = Gravity.CENTER_VERTICAL
            setPadding(dp(8), dp(8), dp(8), dp(8))
        }
        toolbar.addView(
            TextView(context).apply {
                text = "Behavior Audit Log"
                setTextColor(Color.WHITE)
                setFixedTextSize(14f)
                layoutParams = LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f)
            },
        )
        toolbar.addView(smallButton("更新") { refresh() })
        toolbar.addView(smallButton("クリア") { BehaviorAuditLog.clear(); refresh() })
        return toolbar
    }

    private fun smallButton(label: String, onClick: () -> Unit): Button =
        Button(context).apply {
            text = label
            setFixedTextSize(10f)
            setPadding(dp(4), 0, dp(4), 0)
            // 2026-08、ボタンが5個(←→更新×中止📁開く)に増えたところ、Androidの
            // Buttonデフォルトスタイルのminimum width(端末やテーマにもよるが概ね88dp前後)
            // のせいで5個合計がドロワー幅(320dp)を超え、5個目(📁開く)が画面外に
            // 押し出されて見えなくなっていた(navRow自体はHorizontalScrollViewでも
            // 包んでいなかったため、はみ出した分は完全に不可視になっていた)。
            // ボタン自体の最小幅を詰めて、必要な分だけの幅で並ぶようにする。
            minWidth = 0
            minimumWidth = 0
            setOnClickListener { onClick() }
        }

    fun refresh() {
        val text = BehaviorAuditLog.dumpAsText()
        logText.text = text.ifBlank { "(記録なし)" }
        refreshPermissions()
        refreshBenchmarkStatus()
        refreshBookmarkStar()
        refreshZoomStatus()
        refreshFindStatus()
        tabBarView?.refresh()
        if (!addressBarInput.isFocused) {
            currentUrlProvider?.invoke()?.let { addressBarInput.setText(it) }
        }
    }

    /** ドロワーが開いている間だけ1秒間隔で自動更新する */
    fun setAutoRefresh(enabled: Boolean) {
        autoRefresh = enabled
        if (enabled) {
            refresh() // 開いた瞬間は全体を最新化する
            scheduleLogTick()
        }
    }

    /**
     * ログ表示だけを1秒おきに更新する。タブバー・許可設定チェックボックスは
     * ここでは触らない(以前はrefresh()を丸ごと1秒おきに呼んでいたため、
     * ボタン/チェックボックスが毎秒作り直され、タップの瞬間に差し替わって
     * 反応しなくなることがあった)。
     */
    private fun scheduleLogTick() {
        if (!autoRefresh) return
        val text = BehaviorAuditLog.dumpAsText()
        logText.text = text.ifBlank { "(記録なし)" }
        refreshHandler.postDelayed({ scheduleLogTick() }, 1000)
    }

    private fun dp(value: Int): Int =
        TypedValue.applyDimension(TypedValue.COMPLEX_UNIT_DIP, value.toFloat(), resources.displayMetrics).toInt()
}
