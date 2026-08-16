package com.B.b.Renderer.data

import android.content.Context
import android.database.sqlite.SQLiteDatabase
import android.database.sqlite.SQLiteOpenHelper

/**
 * 履歴・ブックマークの永続化先。SimpleCookieJar同様、この規模ではRoom等を足すより
 * android.database.sqlite(SDK標準、NDK不要)をそのまま使う方針に合わせている。
 *
 * historyとbookmarksは別テーブルだが、どちらも「起動のたびに作り直せば十分」な
 * データ量・重要度ではない(特にbookmarksはユーザーが明示的に残す情報)ため、
 * 通常のディスクDBとして永続化する(SimpleCookieJarのようなプロセス内メモリのみ、ではない)。
 *
 * onUpgradeは現状drop&recreateの簡易実装。スキーマが増えてきたらALTER TABLEでの
 * マイグレーションに切り替えること(TODO)。
 */
class BrowserDatabase(context: Context) : SQLiteOpenHelper(context, DB_NAME, null, DB_VERSION) {

    companion object {
        private const val DB_NAME = "bb_renderer_browser.db"
        private const val DB_VERSION = 1

        const val TABLE_HISTORY = "history"
        const val TABLE_BOOKMARKS = "bookmarks"
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
    }

    override fun onUpgrade(db: SQLiteDatabase, oldVersion: Int, newVersion: Int) {
        db.execSQL("DROP TABLE IF EXISTS $TABLE_HISTORY")
        db.execSQL("DROP TABLE IF EXISTS $TABLE_BOOKMARKS")
        onCreate(db)
    }
}
