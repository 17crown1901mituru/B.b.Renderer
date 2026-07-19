package com.B.b.Renderer.permissions

import android.content.Context

/**
 * アプリ全体の設定。SitePermissions(ドメイン単位、ページ側の要求用)とは別軸で、
 * 「ユーザー自身がアプリ全体に対して望む挙動」をここに持つ。
 *
 * 例: ユーザーが「常に画面をスリープさせない」をONにした場合、これはどのドメインを
 * 見ているかに関わらず常に効く。一方、ページ側JSが`navigator.wakeLock.request()`を
 * 呼ぶ場合は、引き続きSitePermissions(ドメイン単位)の許可が必要。
 */
class GlobalAppSettings(context: Context) {

    private val prefs = context.getSharedPreferences("global_app_settings", Context.MODE_PRIVATE)

    companion object {
        private const val KEY_USER_AGENT = "user_agent"
        private const val KEY_BLOCK_THIRD_PARTY_COOKIES = "block_third_party_cookies"
        private const val KEY_USER_KEEP_SCREEN_ON = "user_keep_screen_on"
        private const val KEY_USER_VIBRATION_ENABLED = "user_vibration_enabled"
        private const val KEY_USER_ORIENTATION_LOCK = "user_orientation_lock" // "" = 自動(ロックなし)

        const val DEFAULT_USER_AGENT =
            "Mozilla/5.0 (Linux; Android 14) AppleWebKit/537.36 (KHTML, like Gecko) " +
                "Chrome/124.0.0.0 Mobile Safari/537.36 B.b.Renderer/1.0"

        // DuckDuckGoのHTML版(サーバーレンダリング、JS不要)。このエンジンはJS実行環境が
        // フルブラウザ相当ではないため、検索結果自体が重いJSに依存しないものを既定にする。
        const val DEFAULT_SEARCH_TEMPLATE = "https://html.duckduckgo.com/html/?q=%s"
    }

    var searchEngineUrlTemplate: String
        get() = prefs.getString("search_engine_url_template", DEFAULT_SEARCH_TEMPLATE) ?: DEFAULT_SEARCH_TEMPLATE
        set(value) = prefs.edit().putString("search_engine_url_template", value).apply()

    var userAgent: String
        get() = prefs.getString(KEY_USER_AGENT, DEFAULT_USER_AGENT) ?: DEFAULT_USER_AGENT
        set(value) = prefs.edit().putString(KEY_USER_AGENT, value).apply()

    /** サードパーティCookieを既定でブロックするか(実ブラウザの既定に合わせてtrue) */
    var blockThirdPartyCookies: Boolean
        get() = prefs.getBoolean(KEY_BLOCK_THIRD_PARTY_COOKIES, true)
        set(value) = prefs.edit().putBoolean(KEY_BLOCK_THIRD_PARTY_COOKIES, value).apply()

    /** ユーザー自身が望む「常時スリープ防止」(ページ側の要求とは独立) */
    var userKeepScreenOn: Boolean
        get() = prefs.getBoolean(KEY_USER_KEEP_SCREEN_ON, false)
        set(value) = prefs.edit().putBoolean(KEY_USER_KEEP_SCREEN_ON, value).apply()

    var userVibrationEnabled: Boolean
        get() = prefs.getBoolean(KEY_USER_VIBRATION_ENABLED, true)
        set(value) = prefs.edit().putBoolean(KEY_USER_VIBRATION_ENABLED, value).apply()

    /** "" = 自動(ロックなし)、それ以外は"portrait"/"landscape"等 */
    var userOrientationLock: String
        get() = prefs.getString(KEY_USER_ORIENTATION_LOCK, "") ?: ""
        set(value) = prefs.edit().putString(KEY_USER_ORIENTATION_LOCK, value).apply()
}
