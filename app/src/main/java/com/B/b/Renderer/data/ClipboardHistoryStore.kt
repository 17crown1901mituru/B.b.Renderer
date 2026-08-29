package com.B.b.Renderer.data

import android.content.ContentValues
import android.content.Context

data class ClipboardHistoryEntry(
    val id: Long,
    val text: String,
    val copiedAt: Long,
)

/**
 * 画面長押しテキスト選択→コピーの履歴の永続化。ドロワー内に一覧表示し、そこから
 * 過去にコピーしたものを改めてAndroidクリップボードへ戻せるようにするためのストア。
 *
 * ソフトキーボード側の入力候補履歴(概ね30件程度が目安)より余裕を持たせ、
 * 最大保存件数はMAX_ENTRIES(100件)としている。追加のたびに超過分を
 * 古い順に自動削除するため、呼び出し側で件数を意識する必要はない。
 *
 * HistoryStore(閲覧履歴)の「直前の訪問と同じURLなら新規行を積まず時刻だけ更新する」
 * という重複防止と同じ考え方で、直前のコピーと全く同じ文字列の場合も新規行を足さず
 * 時刻だけ更新する(長押しコピーの連打で同じ内容が何行も積み上がるのを防ぐ)。
 */
class ClipboardHistoryStore(context: Context) {

    companion object {
        const val MAX_ENTRIES = 100
    }

    private val dbHelper = BrowserDatabase(context)

    fun add(text: String) {
        if (text.isBlank()) return
        val db = dbHelper.writableDatabase
        val now = System.currentTimeMillis()

        val last = db.rawQuery(
            "SELECT id, text FROM ${BrowserDatabase.TABLE_CLIPBOARD_HISTORY} ORDER BY copied_at DESC LIMIT 1",
            null,
        ).use { cursor ->
            if (cursor.moveToFirst()) cursor.getLong(0) to cursor.getString(1) else null
        }

        if (last != null && last.second == text) {
            db.update(
                BrowserDatabase.TABLE_CLIPBOARD_HISTORY,
                ContentValues().apply { put("copied_at", now) },
                "id = ?",
                arrayOf(last.first.toString()),
            )
            return
        }

        db.insert(
            BrowserDatabase.TABLE_CLIPBOARD_HISTORY,
            null,
            ContentValues().apply {
                put("text", text)
                put("copied_at", now)
            },
        )
        trimToMax(db)
    }

    /** MAX_ENTRIESを超えた分を古い順に削除する。 */
    private fun trimToMax(db: android.database.sqlite.SQLiteDatabase) {
        db.execSQL(
            "DELETE FROM ${BrowserDatabase.TABLE_CLIPBOARD_HISTORY} WHERE id NOT IN (" +
                "SELECT id FROM ${BrowserDatabase.TABLE_CLIPBOARD_HISTORY} " +
                "ORDER BY copied_at DESC LIMIT $MAX_ENTRIES)",
        )
    }

    fun recent(limit: Int = MAX_ENTRIES): List<ClipboardHistoryEntry> {
        dbHelper.readableDatabase.rawQuery(
            "SELECT id, text, copied_at FROM ${BrowserDatabase.TABLE_CLIPBOARD_HISTORY} " +
                "ORDER BY copied_at DESC LIMIT ?",
            arrayOf(limit.toString()),
        ).use { cursor ->
            val results = mutableListOf<ClipboardHistoryEntry>()
            while (cursor.moveToNext()) {
                results.add(
                    ClipboardHistoryEntry(
                        id = cursor.getLong(0),
                        text = cursor.getString(1),
                        copiedAt = cursor.getLong(2),
                    ),
                )
            }
            return results
        }
    }

    fun delete(id: Long) {
        dbHelper.writableDatabase.delete(BrowserDatabase.TABLE_CLIPBOARD_HISTORY, "id = ?", arrayOf(id.toString()))
    }

    fun clearAll() {
        dbHelper.writableDatabase.delete(BrowserDatabase.TABLE_CLIPBOARD_HISTORY, null, null)
    }
}
