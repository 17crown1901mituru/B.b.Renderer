package com.B.b.Renderer.debug

import android.util.Log
import java.text.SimpleDateFormat
import java.util.Locale

/**
 * ページ側JSの挙動監査・デバッグ用のログ。
 *
 * 「入力の監視・記録」という要望を、次の2点に絞って実装したもの:
 *   1. ページ側JS(content)の挙動監査・デバッグログ
 *   2. このアプリ内(B.b.Renderer)で発生したタップ/操作だけの記録
 *
 * OSレベルの全画面入力キャプチャや他アプリの監視は行わない
 * (android.permission.BIND_ACCESSIBILITY_SERVICE等は一切使わない、
 * このアプリのプロセス内で完結するインメモリのリングバッファのみ)。
 *
 * ShortcutApiと同じ「限定APIの原則」に沿い、記録できる項目もここで
 * 定義したカテゴリに限定している。汎用的な「なんでも記録する」フックにはしない。
 */
object BehaviorAuditLog {

    enum class Category {
        JS_CONSOLE,      // console.log/warn/error/info
        JS_EVAL,         // <script>実行の開始・失敗
        DOM_EVENT,       // dispatchEvent経由でリスナーに配送されたイベント
        NATIVE_TAP,      // 画面タップ→dispatchClickに到達した操作
        DEVICE_SHORTCUT, // .rjsショートカット経由の操作(ShortcutApi)
        A11Y_ACTION,     // アクセシビリティサービス経由のアクティベーション
        HTMX_TRIGGER,    // hx-*要素のトリガー発火
    }

    data class Entry(
        val timestampMs: Long,
        val category: Category,
        val detail: String,
    )

    private const val MAX_ENTRIES = 500
    private val buffer = ArrayDeque<Entry>()
    private val lock = Any()
    private val timeFormat = SimpleDateFormat("HH:mm:ss.SSS", Locale.US)

    var enabled: Boolean = true

    fun record(category: Category, detail: String) {
        if (!enabled) return
        val entry = Entry(System.currentTimeMillis(), category, detail)
        synchronized(lock) {
            buffer.addLast(entry)
            while (buffer.size > MAX_ENTRIES) buffer.removeFirst()
        }
        Log.d("BehaviorAudit", "[${category.name}] $detail")
    }

    /** 直近の記録をそのまま返す(新しい順) */
    fun snapshot(): List<Entry> = synchronized(lock) { buffer.toList().asReversed() }

    /** デバッグ表示・エクスポート用にテキスト化する */
    fun dumpAsText(): String = synchronized(lock) {
        buffer.asReversed().joinToString("\n") { entry ->
            "${timeFormat.format(entry.timestampMs)} [${entry.category.name}] ${entry.detail}"
        }
    }

    fun clear() = synchronized(lock) { buffer.clear() }
}
