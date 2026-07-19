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
import com.B.b.Renderer.permissions.GlobalAppSettings
import com.B.b.Renderer.permissions.SitePermissions

/**
 * BehaviorAuditLogをその場で見るためのデバッグ用サイドパネル。
 * 加えて、ドメイン単位のブラウザ機能許可(Vibrate/WakeLock/OrientationLock)を
 * その場でON/OFFできる簡易設定もここに置く(専用の設定画面ができるまでの暫定)。
 *
 * 画面上にEngineView(ページ描画)・ソフトウェアキーボード・デバッグ表示が
 * 同時に重なるとごちゃつくため、常時表示ではなくDrawerLayoutで画面端に
 * 隠しておき、必要な時だけ引き出す形にしている(EngineActivity側で
 * DrawerLayoutのendドロワーとして配置する想定)。
 *
 * ここはあくまで「見る・エクスポートする・許可を切り替える」ためのビューで、
 * ShortcutApiのような実行権限を持つAPIではない。
 */
class DebugDrawerView(
    context: Context,
    private val sitePermissions: SitePermissions? = null,
    private val globalSettings: GlobalAppSettings? = null,
    private val currentDomainProvider: (() -> String)? = null,
    private val onGlobalSettingsChanged: (() -> Unit)? = null,
) : LinearLayout(context) {

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

    private val refreshHandler = Handler(Looper.getMainLooper())
    private var autoRefresh = false

    init {
        orientation = VERTICAL
        setBackgroundColor(Color.parseColor("#EE111111"))
        layoutParams = ViewGroup.LayoutParams(dp(320), ViewGroup.LayoutParams.MATCH_PARENT)

        addView(buildToolbar())
        addView(buildGlobalSettingsPanel())
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
    }

    /** ドロワーが開いている間だけ1秒間隔で自動更新する */
    fun setAutoRefresh(enabled: Boolean) {
        autoRefresh = enabled
        if (enabled) scheduleTick()
    }

    private fun scheduleTick() {
        if (!autoRefresh) return
        refresh()
        refreshHandler.postDelayed({ scheduleTick() }, 1000)
    }

    private fun dp(value: Int): Int =
        TypedValue.applyDimension(TypedValue.COMPLEX_UNIT_DIP, value.toFloat(), resources.displayMetrics).toInt()
}
