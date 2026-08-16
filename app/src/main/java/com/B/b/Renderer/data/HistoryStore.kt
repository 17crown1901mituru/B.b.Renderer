package com.B.b.Renderer.data

import android.content.ContentValues
import android.content.Context

data class HistoryEntry(
    val id: Long,
    val url: String,
    val title: String,
    val visitedAt: Long,
)

/**
 * 閲覧履歴の永続化。実ブラウザ同様、履歴は「同じURLでも訪問のたびに1行積む」ログ形式。
 * ただし同一URLへのreload/再描画で無限に積み上がらないよう、直前のエントリと
 * URLが同じ場合は新規行を足さず時刻だけ更新する(録りすぎ防止)。
 *
 * シークレットタブ等、履歴を残したくない呼び出し元は単純にrecordVisitを呼ばなければよい
 * (EngineActivity側でタブごとに判定する)。
 */
class HistoryStore(context: Context) {

    private val dbHelper = BrowserDatabase(context)

    fun recordVisit(url: String, title: String) {
        if (url.isBlank()) return
        val db = dbHelper.writableDatabase
        val now = System.currentTimeMillis()

        val last = db.rawQuery(
            "SELECT id, url FROM ${BrowserDatabase.TABLE_HISTORY} ORDER BY visited_at DESC LIMIT 1",
            null,
        ).use { cursor ->
            if (cursor.moveToFirst()) {
                cursor.getLong(0) to cursor.getString(1)
            } else {
                null
            }
        }

        if (last != null && last.second == url) {
            db.update(
                BrowserDatabase.TABLE_HISTORY,
                ContentValues().apply { put("visited_at", now) },
                "id = ?",
                arrayOf(last.first.toString()),
            )
            return
        }

        db.insert(
            BrowserDatabase.TABLE_HISTORY,
            null,
            ContentValues().apply {
                put("url", url)
                put("title", title.ifBlank { url })
                put("visited_at", now)
            },
        )
    }

    fun recent(limit: Int = 200): List<HistoryEntry> {
        val db = dbHelper.readableDatabase
        db.rawQuery(
            "SELECT id, url, title, visited_at FROM ${BrowserDatabase.TABLE_HISTORY} " +
                "ORDER BY visited_at DESC LIMIT ?",
            arrayOf(limit.toString()),
        ).use { cursor ->
            val results = mutableListOf<HistoryEntry>()
            while (cursor.moveToNext()) {
                results.add(
                    HistoryEntry(
                        id = cursor.getLong(0),
                        url = cursor.getString(1),
                        title = cursor.getString(2),
                        visitedAt = cursor.getLong(3),
                    ),
                )
            }
            return results
        }
    }

    /** タイトルまたはURLに部分一致するものを新しい順に返す。 */
    fun search(query: String, limit: Int = 100): List<HistoryEntry> {
        if (query.isBlank()) return recent(limit)
        val db = dbHelper.readableDatabase
        val like = "%$query%"
        db.rawQuery(
            "SELECT id, url, title, visited_at FROM ${BrowserDatabase.TABLE_HISTORY} " +
                "WHERE url LIKE ? OR title LIKE ? ORDER BY visited_at DESC LIMIT ?",
            arrayOf(like, like, limit.toString()),
        ).use { cursor ->
            val results = mutableListOf<HistoryEntry>()
            while (cursor.moveToNext()) {
                results.add(
                    HistoryEntry(
                        id = cursor.getLong(0),
                        url = cursor.getString(1),
                        title = cursor.getString(2),
                        visitedAt = cursor.getLong(3),
                    ),
                )
            }
            return results
        }
    }

    fun delete(id: Long) {
        dbHelper.writableDatabase.delete(BrowserDatabase.TABLE_HISTORY, "id = ?", arrayOf(id.toString()))
    }

    fun clearAll() {
        dbHelper.writableDatabase.delete(BrowserDatabase.TABLE_HISTORY, null, null)
    }
}
