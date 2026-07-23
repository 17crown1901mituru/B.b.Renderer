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
import android.widget.LinearLayout
import android.widget.ScrollView
import android.widget.TextView
import com.B.b.Renderer.benchmark.RenderTierBenchmark
import com.B.b.Renderer.permissions.GlobalAppSettings
import com.B.b.Renderer.permissions.SitePermissions
import com.B.b.Renderer.tabs.TabBarView
import com.B.b.Renderer.tabs.TabManager

/**
 * BehaviorAuditLogをその場で見るためのデバッグ用サイドパネル。
 * 加えて、タブ一覧・ドメイン単位のブラウザ機能許可・アプリ全体設定もここに集約する
 * (2026-07議論分: ブラウザとしての機能・設定はドロワー側に寄せて、ページ描画領域を
 * 画面いっぱいに使えるようにする方針)。
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
    private val currentDomainProvider: (() -> String)? = null,
    private val onGlobalSettingsChanged: (() -> Unit)? = null,
    private val onNavigateRequested: ((String) -> Unit)? = null,
    private val currentUrlProvider: (() -> String)? = null,
    val tabBarView: TabBarView? = null,
) : LinearLayout(context) {

    private val addressBarInput = android.widget.EditText(context).apply {
        hint = "URLまたは検索語句"
        setTextColor(Color.WHITE)
        setHintTextColor(Color.GRAY)
        textSize = 13f
        isSingleLine = true
        imeOptions = android.view.inputmethod.EditorInfo.IME_ACTION_GO
        setBackgroundColor(Color.parseColor("#333333"))
        setPadding(dp(10), dp(8), dp(10), dp(8))
    }

    private val logText = TextView(context).apply {
        setTextColor(Color.parseColor("#00FF66"))
        typeface = android.graphics.Typeface.MONOSPACE
        textSize = 11f
        setPadding(dp(12), dp(12), dp(12), dp(12))
    }

    private val permissionsPanel = LinearLayout(context).apply {
        orientation = VERTICAL
        setPadding(dp(12), dp(4), dp(12), dp(4))
    }

    private val benchmarkStatusText = TextView(context).apply {
        setTextColor(Color.LTGRAY)
        textSize = 11f
    }

    private val refreshHandler = Handler(Looper.getMainLooper())
    private var autoRefresh = false

    init {
        orientation = VERTICAL
        setBackgroundColor(Color.parseColor("#EE111111"))
        layoutParams = ViewGroup.LayoutParams(dp(320), ViewGroup.LayoutParams.MATCH_PARENT)

        addView(buildAddressBar())
        tabBarView?.let {
            addView(buildTabsHeader())
            addView(it, ViewGroup.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT))
        }
        addView(buildToolbar())
        addView(buildGlobalSettingsPanel())
        addView(buildRenderBenchmarkPanel())
        addView(buildPermissionsHeader())
        addView(permissionsPanel)
        addView(
            ScrollView(context).apply {
                addView(logText)
            },
            LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, 0, 1f),
        )

        refresh()
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
                textSize = 12f
            },
        )
        panel.addView(
            CheckBox(context).apply {
                text = "常に画面をスリープさせない(ユーザー設定)"
                setTextColor(Color.WHITE)
                textSize = 11f
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
                textSize = 11f
                isChecked = settings.userVibrationEnabled
                setOnCheckedChangeListener { _, checked -> settings.userVibrationEnabled = checked }
            },
        )
        panel.addView(
            CheckBox(context).apply {
                text = "サードパーティCookieを既定でブロック"
                setTextColor(Color.WHITE)
                textSize = 11f
                isChecked = settings.blockThirdPartyCookies
                setOnCheckedChangeListener { _, checked -> settings.blockThirdPartyCookies = checked }
            },
        )
        panel.addView(
            TextView(context).apply {
                text = "User-Agent"
                setTextColor(Color.LTGRAY)
                textSize = 10f
                setPadding(0, dp(4), 0, 0)
            },
        )
        panel.addView(
            EditText(context).apply {
                setText(settings.userAgent)
                setTextColor(Color.WHITE)
                textSize = 10f
                setOnFocusChangeListener { _, hasFocus ->
                    if (!hasFocus) settings.userAgent = text.toString().ifBlank { GlobalAppSettings.DEFAULT_USER_AGENT }
                }
            },
        )
        return panel
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
                textSize = 12f
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

    private fun buildPermissionsHeader(): TextView =
        TextView(context).apply {
            text = "このドメインの許可設定"
            setTextColor(Color.LTGRAY)
            textSize = 12f
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
                    textSize = 11f
                },
            )
            return
        }
        permissionsPanel.addView(
            TextView(context).apply {
                text = domain
                setTextColor(Color.WHITE)
                textSize = 12f
                setPadding(0, 0, 0, dp(4))
            },
        )
        SitePermissions.Capability.values().forEach { capability ->
            permissionsPanel.addView(
                CheckBox(context).apply {
                    text = capability.name
                    setTextColor(Color.WHITE)
                    textSize = 11f
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
        column.addView(navRow)

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
            textSize = 12f
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
                textSize = 14f
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
            textSize = 10f
            setPadding(dp(4), 0, dp(4), 0)
            setOnClickListener { onClick() }
        }

    fun refresh() {
        val text = BehaviorAuditLog.dumpAsText()
        logText.text = text.ifBlank { "(記録なし)" }
        refreshPermissions()
        refreshBenchmarkStatus()
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
