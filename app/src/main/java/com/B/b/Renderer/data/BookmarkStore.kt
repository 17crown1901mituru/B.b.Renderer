package com.B.b.Renderer.data

import android.content.ContentValues
import android.content.Context

data class BookmarkEntry(
    val id: Long,
    val url: String,
    val title: String,
    val createdAt: Long,
)

/**
 * ブックマークの永続化。URLはUNIQUE制約があるため、同じURLを再度addすると
 * タイトルと作成時刻が上書きされる(重複行にはならない)。
 */
class BookmarkStore(context: Context) {

    private val dbHelper = BrowserDatabase(context)

    fun add(url: String, title: String) {
        if (url.isBlank()) return
        dbHelper.writableDatabase.insertWithOnConflict(
            BrowserDatabase.TABLE_BOOKMARKS,
            null,
            ContentValues().apply {
                put("url", url)
                put("title", title.ifBlank { url })
                put("created_at", System.currentTimeMillis())
            },
            android.database.sqlite.SQLiteDatabase.CONFLICT_REPLACE,
        )
    }

    fun remove(url: String) {
        dbHelper.writableDatabase.delete(BrowserDatabase.TABLE_BOOKMARKS, "url = ?", arrayOf(url))
    }

    /** 星ボタン用: 未登録なら追加、登録済みなら削除して、結果の状態(true=登録された)を返す。 */
    fun toggle(url: String, title: String): Boolean {
        return if (isBookmarked(url)) {
            remove(url)
            false
        } else {
            add(url, title)
            true
        }
    }

    fun isBookmarked(url: String): Boolean {
        if (url.isBlank()) return false
        dbHelper.readableDatabase.rawQuery(
            "SELECT 1 FROM ${BrowserDatabase.TABLE_BOOKMARKS} WHERE url = ? LIMIT 1",
            arrayOf(url),
        ).use { return it.moveToFirst() }
    }

    fun list(): List<BookmarkEntry> {
        dbHelper.readableDatabase.rawQuery(
            "SELECT id, url, title, created_at FROM ${BrowserDatabase.TABLE_BOOKMARKS} ORDER BY created_at DESC",
            null,
        ).use { cursor ->
            val results = mutableListOf<BookmarkEntry>()
            while (cursor.moveToNext()) {
                results.add(
                    BookmarkEntry(
                        id = cursor.getLong(0),
                        url = cursor.getString(1),
                        title = cursor.getString(2),
                        createdAt = cursor.getLong(3),
                    ),
                )
            }
            return results
        }
    }

    fun delete(id: Long) {
        dbHelper.writableDatabase.delete(BrowserDatabase.TABLE_BOOKMARKS, "id = ?", arrayOf(id.toString()))
    }
}
