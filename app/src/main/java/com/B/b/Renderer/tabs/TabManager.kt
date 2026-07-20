package com.B.b.Renderer.tabs

import com.B.b.Renderer.debug.BehaviorAuditLog

/**
 * 開いているタブの集合を管理する。
 *
 * 設計方針(2026-07議論分):
 *   - フォアグラウンド以外のタブは既定で完全休止(URLのみ保持、エンジンは破棄)
 *   - pinned指定したタブだけは破棄せず、裏でJS/メディアを動かし続ける
 *   - showAsPip指定したタブ(pinnedが前提)は、さらに小窓として画面に表示する
 *   - pinned/showAsPipの上限は、メモリ以上に発熱が本質的な制約になるため
 *     意図的に小さく抑えている(ThermalGuardが悪化を検知したら強制的に減らす)
 */
class TabManager(
    private val sessionFactory: suspend (url: String) -> TabSession,
) {
    companion object {
        const val MAX_PINNED = 4
        const val MAX_SHOWN_AS_PIP = 2
    }

    private val dormantUrls = mutableMapOf<Long, String>() // 休止中タブ: id -> url(再開時に使う)
    private val sessions = mutableMapOf<Long, TabSession>() // 現在エンジンを持っている(pinned or foreground)タブ
    private val historyBack = mutableMapOf<Long, MutableList<String>>()
    private val historyForward = mutableMapOf<Long, MutableList<String>>()
    private var nextId = 1L
    var foregroundId: Long = -1L
        private set

    fun allTabIds(): List<Long> = (dormantUrls.keys + sessions.keys).distinct()

    fun titleOf(id: Long): String = sessions[id]?.title ?: dormantUrls[id]?.let {
        runCatching { java.net.URI(it).host }.getOrNull() ?: it
    } ?: "?"

    fun isPinned(id: Long): Boolean = sessions[id]?.pinned ?: false
    fun isShownAsPip(id: Long): Boolean = sessions[id]?.showAsPip ?: false
    fun pinnedSessions(): List<TabSession> =
        sessions.filterKeys { it != foregroundId }.values.filter { it.pinned }
    fun pipSessions(): List<TabSession> =
        sessions.filterKeys { it != foregroundId }.values.filter { it.showAsPip }
    fun foregroundSession(): TabSession? = sessions[foregroundId]

    fun canGoBack(): Boolean = historyBack[foregroundId]?.isNotEmpty() == true
    fun canGoForward(): Boolean = historyForward[foregroundId]?.isNotEmpty() == true

    /** 同じタブ内でのページ遷移(リンクを踏む等)。タブは増えず、中身だけ差し替わる。 */
    suspend fun navigateForeground(url: String): TabSession {
        val id = if (foregroundId == -1L) nextId++ else foregroundId
        sessions[id]?.let { current ->
            historyBack.getOrPut(id) { mutableListOf() }.add(current.url)
            historyForward[id]?.clear() // 新規遷移でforward履歴は破棄(実ブラウザと同じ)
        }
        sessions[id]?.dispose()
        val session = sessionFactory(url)
        sessions[id] = session
        foregroundId = id
        return session
    }

    /** 現在のURLのまま作り直す(履歴には触らない)。 */
    suspend fun reloadForeground(): TabSession? {
        val id = foregroundId
        val url = sessions[id]?.url ?: return null
        sessions[id]?.dispose()
        val session = sessionFactory(url)
        sessions[id] = session
        return session
    }

    suspend fun goBack(): TabSession? {
        val id = foregroundId
        val back = historyBack[id]
        if (back.isNullOrEmpty()) return null
        val currentUrl = sessions[id]?.url ?: return null
        val previousUrl = back.removeAt(back.size - 1)
        historyForward.getOrPut(id) { mutableListOf() }.add(currentUrl)
        sessions[id]?.dispose()
        val session = sessionFactory(previousUrl)
        sessions[id] = session
        return session
    }

    suspend fun goForward(): TabSession? {
        val id = foregroundId
        val forward = historyForward[id]
        if (forward.isNullOrEmpty()) return null
        val currentUrl = sessions[id]?.url ?: return null
        val nextUrl = forward.removeAt(forward.size - 1)
        historyBack.getOrPut(id) { mutableListOf() }.add(currentUrl)
        sessions[id]?.dispose()
        val session = sessionFactory(nextUrl)
        sessions[id] = session
        return session
    }

    /** 新規タブを開き、フォアグラウンドにする。直前のタブはpinnedでなければ休止する。 */
    suspend fun openNewForeground(url: String): TabSession {
        val previousId = foregroundId
        val previous = sessions[previousId]
        if (previous != null && !previous.pinned) {
            dormantUrls[previousId] = previous.url
            previous.dispose()
            sessions.remove(previousId)
        }
        val session = sessionFactory(url)
        val id = nextId++
        sessions[id] = session
        foregroundId = id
        return session
    }

    /**
     * 既存タブをフォアグラウンドに切り替える。
     * pinnedで生きていればそのまま使う。休止中ならURLから作り直す。
     */
    suspend fun switchForeground(id: Long): TabSession {
        val previousForegroundId = foregroundId
        val previous = sessions[previousForegroundId]
        // 直前のフォアグラウンドは、pinnedでなければ休止させる(エンジンを破棄)
        if (previous != null && previousForegroundId != id && !previous.pinned) {
            dormantUrls[previousForegroundId] = previous.url
            previous.dispose()
            sessions.remove(previousForegroundId)
        }

        val existing = sessions[id]
        if (existing != null) {
            foregroundId = id
            return existing
        }

        val url = dormantUrls.remove(id) ?: error("Unknown tab id: $id")
        val session = sessionFactory(url)
        sessions[id] = session
        foregroundId = id
        return session
    }

    /** pinned指定。JS/メディアを裏で動かし続ける対象にする。上限超過時は最も古いpinnedを解除する。 */
    fun setPinned(id: Long, pinned: Boolean) {
        val session = sessions[id] ?: return
        if (pinned && pinnedSessions().size >= MAX_PINNED) {
            // mutableMapOf()はLinkedHashMap(挿入順保持)なので、entries先頭が最も古い
            val oldestId = sessions.entries.firstOrNull { it.value.pinned && it.key != foregroundId }?.key
            if (oldestId != null) setPinned(oldestId, false)
        }
        session.pinned = pinned
        if (!pinned) session.showAsPip = false // pinned解除したらPiP表示も解除
        BehaviorAuditLog.record(BehaviorAuditLog.Category.JS_EVAL, "tab $id pinned=$pinned")
    }

    fun setShowAsPip(id: Long, show: Boolean) {
        val session = sessions[id] ?: return
        if (!session.pinned) return // pinned前提
        if (show && pipSessions().size >= MAX_SHOWN_AS_PIP) return // 上限、静かに無視
        session.showAsPip = show
    }

    /** ThermalGuardから呼ばれる。発熱時はPiP表示・pinned双方を強制的に減らす。 */
    fun throttleForThermal() {
        sessions.entries.filter { it.value.showAsPip }.map { it.key }.forEach { setShowAsPip(it, false) }
        sessions.entries.filter { it.value.pinned }.map { it.key }.forEach { setPinned(it, false) }
        BehaviorAuditLog.record(BehaviorAuditLog.Category.JS_EVAL, "thermal throttle: all pinned/PiP tabs suspended")
    }

    fun closeTab(id: Long) {
        sessions.remove(id)?.dispose()
        dormantUrls.remove(id)
        historyBack.remove(id)
        historyForward.remove(id)
        if (foregroundId == id) foregroundId = -1L
    }
}
