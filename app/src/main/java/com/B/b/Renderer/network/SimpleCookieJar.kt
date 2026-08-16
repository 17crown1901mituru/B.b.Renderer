package com.B.b.Renderer.network

import android.content.Context
import com.B.b.Renderer.permissions.GlobalAppSettings
import com.B.b.Renderer.permissions.SitePermissions
import okhttp3.Cookie
import okhttp3.CookieJar
import okhttp3.HttpUrl

/**
 * OkHttpのCookieJar実装。今までCookieJarが未設定だったため、実質Cookieが
 * 一切保持されない状態だった(ログインが必要なサイトが動かない)。
 *
 * サードパーティCookie(現在表示中のページのドメインと異なるドメイン宛のCookie)は、
 * GlobalAppSettings.blockThirdPartyCookiesが有効な間、既定でブロックする。
 * SitePermissionsにドメイン単位の例外(THIRD_PARTY_COOKIES許可)を登録すれば、
 * そのドメインへの送信元がそのドメインでなくても送受信を許可する
 * (実ブラウザの「サードパーティCookieをブロック + サイトごとの例外」に相当)。
 *
 * 永続化は簡易的にプロセス内メモリのみ(アプリ再起動でクリアされる)。
 * ディスク永続化が要る場合は別途persistCookiesJar等に差し替える。
 */
class SimpleCookieJar(
    context: Context,
    private val globalSettings: GlobalAppSettings,
    private val sitePermissions: SitePermissions,
    private val currentPageDomainProvider: () -> String,
) : CookieJar {

    private val store = mutableMapOf<String, MutableList<Cookie>>()

    override fun saveFromResponse(url: HttpUrl, cookies: List<Cookie>) {
        val requestDomain = url.host
        if (isThirdParty(requestDomain) && !isExempted(requestDomain)) return
        val bucket = store.getOrPut(requestDomain) { mutableListOf() }
        cookies.forEach { newCookie ->
            bucket.removeAll { it.name == newCookie.name && it.path == newCookie.path }
            bucket.add(newCookie)
        }
    }

    override fun loadForRequest(url: HttpUrl): List<Cookie> {
        val requestDomain = url.host
        if (isThirdParty(requestDomain) && !isExempted(requestDomain)) return emptyList()
        val now = System.currentTimeMillis()
        val bucket = store[requestDomain] ?: return emptyList()
        bucket.removeAll { it.expiresAt < now }
        return bucket.filter { it.matches(url) }
    }

    private fun isThirdParty(requestDomain: String): Boolean {
        if (!globalSettings.blockThirdPartyCookies) return false
        val pageDomain = currentPageDomainProvider()
        if (pageDomain.isBlank()) return false // ページ未確定時は判定しない(初回fetch等)
        return !sameSite(requestDomain, pageDomain)
    }

    private fun isExempted(requestDomain: String): Boolean =
        sitePermissions.isAllowed(requestDomain, SitePermissions.Capability.THIRD_PARTY_COOKIES)

    /** サブドメイン違いは同一サイト扱いにする簡易判定(例: api.example.com と example.com) */
    private fun sameSite(a: String, b: String): Boolean {
        if (a == b) return true
        val regA = registrableDomain(a)
        val regB = registrableDomain(b)
        return regA.isNotBlank() && regA == regB
    }

    private fun registrableDomain(host: String): String {
        val parts = host.split(".")
        return if (parts.size >= 2) parts.takeLast(2).joinToString(".") else host
    }

    fun clearAll() = store.clear()
}
