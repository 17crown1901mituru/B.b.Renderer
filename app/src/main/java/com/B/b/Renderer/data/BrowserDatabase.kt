package com.B.b.Renderer.data

import android.content.Context
import android.database.sqlite.SQLiteDatabase
import android.database.sqlite.SQLiteOpenHelper

/**
 * 履歴・ブックマーク・コピー履歴の永続化先。SimpleCookieJar同様、この規模ではRoom等を
 * 足すより android.database.sqlite(SDK標準、NDK不要)をそのまま使う方針に合わせている。
 *
 * history/bookmarks/clipboard_historyは別テーブルだが、どれも「起動のたびに作り直せば
 * 十分」なデータ量・重要度ではない(特にbookmarks・clipboard_historyはユーザーが
 * 明示的に残す情報)ため、通常のディスクDBとして永続化する
 * (SimpleCookieJarのようなプロセス内メモリのみ、ではない)。
 *
 * onUpgradeについて(2026-08訂正): 以前はversionが上がるたびdrop&recreateする簡易実装
 * だった(TODOコメント参照)が、それだと「クリップボード履歴テーブルを追加しただけ」の
 * 今回のような変更でも、既存ユーザーの閲覧履歴・ブックマークが起動時に消えてしまう。
 * 実害が大きいため、version 2以降は「無いテーブルだけ追加する」形の素朴なマイグレーションに
 * 変更した。スキーマの列変更(ALTER TABLE)が必要になった場合は、この分岐をさらに
 * 増やしていく想定(全面drop&recreateに戻すのはユーザーデータ保護の観点から避けること)。
 */
class BrowserDatabase(context: Context) : SQLiteOpenHelper(context, DB_NAME, null, DB_VERSION) {

    companion object {
        private const val DB_NAME = "bb_renderer_browser.db"
        private const val DB_VERSION = 2

        const val TABLE_HISTORY = "history"
        const val TABLE_BOOKMARKS = "bookmarks"
        const val TABLE_CLIPBOARD_HISTORY = "clipboard_history"
    }

    override fun onCreate(db: SQLiteDatabase) {
        db.execSQL(
            """
            CREATE TABLE $TABLE_HISTORY (
                id INTEGER PRIMARY KEY AUTOINCREMENT,
                url TEXT NOT NULL,
                title TEXT NOT NULL,
                visited_at INTEGER NOT NULL
            )
            """.trimIndent(),
        )
        db.execSQL("CREATE INDEX idx_history_visited_at ON $TABLE_HISTORY(visited_at DESC)")

        db.execSQL(
            """
            CREATE TABLE $TABLE_BOOKMARKS (
                id INTEGER PRIMARY KEY AUTOINCREMENT,
                url TEXT NOT NULL UNIQUE,
                title TEXT NOT NULL,
                created_at INTEGER NOT NULL
            )
            """.trimIndent(),
        )

        createClipboardHistoryTable(db)
    }

    override fun onUpgrade(db: SQLiteDatabase, oldVersion: Int, newVersion: Int) {
        if (oldVersion < 2) {
            createClipboardHistoryTable(db)
        }
    }

    private fun createClipboardHistoryTable(db: SQLiteDatabase) {
        db.execSQL(
            """
            CREATE TABLE IF NOT EXISTS $TABLE_CLIPBOARD_HISTORY (
                id INTEGER PRIMARY KEY AUTOINCREMENT,
                text TEXT NOT NULL,
                copied_at INTEGER NOT NULL
            )
            """.trimIndent(),
        )
        db.execSQL("CREATE INDEX IF NOT EXISTS idx_clipboard_copied_at ON $TABLE_CLIPBOARD_HISTORY(copied_at DESC)")
    }
}
