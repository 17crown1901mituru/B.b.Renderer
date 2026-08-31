package com.B.b.Renderer.permissions

import android.content.Context
import java.net.URI

/**
 * ページ側JS(navigator.vibrate等)がドメインごとに使える「ブラウザ機能」の許可状態を持つ。
 *
 * 方針(2026-07議論分): Web側からの要求を反映するかどうかは設定で決められるようにし、
 * デフォルトはユーザー側からのみ要求できる(ページ側からの要求は既定で無効)。
 * 設定はドメイン単位で分ける。
 *
 * 「危険だから塞ぐ」のではなく、実ブラウザの権限モデル(サイトごとの許可設定、
 * 既定は保守的)に倣ったもの。ユーザーが個別に許可すれば、そのドメインの
 * ページJSから直接呼べるようになる。
 */
class SitePermissions(context: Context) {

    // 2026-08、navigator.clipboard.writeText()対応でCLIPBOARD_WRITEを追加。ページが
    // ユーザーの気付かないうちにクリップボードを書き換えられてしまう(=別の場所に
    // 貼り付けた際に意図しない内容が入る)リスクがあるvibrate/popups等と同種の機能のため、
    // 同じ「既定不許可・ドメイン単位」のモデルに乗せる。ユーザー自身の長押し選択
    // →コピー操作(EngineActivity.onCopyTapped)はこの許可設定と無関係に常に使える
    // (あくまでページ側JSからの自動書き込みだけを許可制にしている)。
    // 2026-08、navigator.clipboard.readText()対応でCLIPBOARD_READを追加。読み取りは
    // 書き込み(CLIPBOARD_WRITE)より機微度が高い(他アプリでコピーしたパスワードや
    // ワンタイムコードがOSクリップボードに残っている可能性があり、ページが気付かれず
    // それを読み取れてしまう)ため、あえて別のCapabilityとして分けている
    // (WRITEだけ許可してREADは許可しない、という細かい制御を可能にするため)。
    enum class Capability { VIBRATE, WAKE_LOCK, ORIENTATION_LOCK, THIRD_PARTY_COOKIES, GEOLOCATION, POPUPS, CLIPBOARD_WRITE, CLIPBOARD_READ }

    private val prefs = context.getSharedPreferences("site_permissions", Context.MODE_PRIVATE)

    private fun key(domain: String, capability: Capability) = "$domain|${capability.name}"

    /** hrefからホスト名(ドメイン)を取り出す。パース不能な場合は空文字(=常に未許可扱い)。 */
    fun domainOf(url: String): String =
        runCatching { URI(url).host ?: "" }.getOrDefault("")

    fun isAllowed(domain: String, capability: Capability): Boolean {
        if (domain.isBlank()) return false
        return prefs.getBoolean(key(domain, capability), false) // 既定は不許可
    }

    fun setAllowed(domain: String, capability: Capability, allowed: Boolean) {
        if (domain.isBlank()) return
        prefs.edit().putBoolean(key(domain, capability), allowed).apply()
    }

    /** 現在許可済みの(ドメイン, 機能)一覧。設定UI表示用。 */
    fun listGranted(): List<Pair<String, Capability>> =
        prefs.all.entries
            .filter { it.value == true }
            .mapNotNull { entry ->
                val parts = entry.key.split("|", limit = 2)
                if (parts.size != 2) return@mapNotNull null
                val cap = runCatching { Capability.valueOf(parts[1]) }.getOrNull() ?: return@mapNotNull null
                parts[0] to cap
            }
}
