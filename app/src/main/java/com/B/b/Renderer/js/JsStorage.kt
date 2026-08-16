package com.B.b.Renderer.js

import android.content.Context

/**
 * localStorage/sessionStorage相当のWeb Storage API。
 *
 * 実際の仕様では任意プロパティへの代入(`localStorage.foo = 'bar'`)もStorageに反映されるが、
 * それをRhino側で再現するには独自Scriptable実装(get/put/hasのオーバーライド)が要る。
 * JsStyle(インラインstyle代入)と同じ方針で、このエンジンでは「頻出のAPIだけ支える」ことにし、
 * getItem/setItem/removeItem/clear/key/lengthのメソッドAPIのみをサポートする
 * (実サイトのコードもほぼこちらの形で書かれている。dot記法の代入は未対応)。
 */
class JsStorage(private val backend: StorageBackend) {

    val length: Int
        get() = backend.keys().size

    fun getItem(key: String): String? = backend.get(key)

    fun setItem(key: String, value: String) {
        backend.set(key, value)
    }

    fun removeItem(key: String) {
        backend.remove(key)
    }

    fun clear() {
        backend.clear()
    }

    /** 挿入順の保証はしない(実際の仕様上も必須ではなく、バックエンド実装依存)。 */
    fun key(index: Int): String? = backend.keys().toList().getOrNull(index)
}

interface StorageBackend {
    fun get(key: String): String?
    fun set(key: String, value: String)
    fun remove(key: String)
    fun clear()
    fun keys(): Set<String>
}

/**
 * sessionStorage用。タブ(JsEngineインスタンス)が生きている間だけのメモリ保持で十分なため、
 * ディスクには一切書かない。JsEngine破棄時も特別な後始末は不要(GC任せ)。
 */
class InMemoryStorageBackend : StorageBackend {
    private val map = mutableMapOf<String, String>()
    override fun get(key: String): String? = map[key]
    override fun set(key: String, value: String) { map[key] = value }
    override fun remove(key: String) { map.remove(key) }
    override fun clear() = map.clear()
    override fun keys(): Set<String> = map.keys.toSet()
}

/**
 * localStorage用。オリジン(scheme://host[:port])単位でSharedPreferencesファイルを分けて
 * 永続化する。GlobalAppSettings/SitePermissions同様、Room等は導入せずSharedPreferencesのみで賄う。
 *
 * オリジンはJsEngine構築時点では確定していない(window.location.hrefが後から設定される)ため、
 * コンストラクタでは受け取らずoriginProviderで都度解決する。オリジン未確定(空文字)の間は
 * 全操作を安全にno-op化する。
 */
class SharedPrefsStorageBackend(
    private val context: Context,
    private val originProvider: () -> String,
) : StorageBackend {

    private var cachedOrigin: String? = null
    private var cachedPrefs: android.content.SharedPreferences? = null

    private fun prefsOrNull(): android.content.SharedPreferences? {
        val origin = originProvider()
        if (origin.isBlank()) return null
        if (origin != cachedOrigin) {
            cachedOrigin = origin
            cachedPrefs = context.applicationContext.getSharedPreferences(
                "local_storage_${origin.hashCode()}",
                Context.MODE_PRIVATE,
            )
        }
        return cachedPrefs
    }

    override fun get(key: String): String? = prefsOrNull()?.getString(key, null)

    override fun set(key: String, value: String) {
        prefsOrNull()?.edit()?.putString(key, value)?.apply()
    }

    override fun remove(key: String) {
        prefsOrNull()?.edit()?.remove(key)?.apply()
    }

    override fun clear() {
        prefsOrNull()?.edit()?.clear()?.apply()
    }

    override fun keys(): Set<String> = prefsOrNull()?.all?.keys ?: emptySet()
}

/** bridge/context未提供時の安全なフォールバック(何も保存できないが、クラッシュもしない)。 */
class NoopStorageBackend : StorageBackend {
    override fun get(key: String): String? = null
    override fun set(key: String, value: String) { /* no-op */ }
    override fun remove(key: String) { /* no-op */ }
    override fun clear() { /* no-op */ }
    override fun keys(): Set<String> = emptySet()
}
