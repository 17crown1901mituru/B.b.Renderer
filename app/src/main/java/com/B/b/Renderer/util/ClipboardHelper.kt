package com.B.b.Renderer.util

import android.content.ClipData
import android.content.ClipboardManager
import android.content.Context
import android.widget.Toast

object ClipboardHelper {

    /**
     * 選択されたテキストを Android クリップボードにコピー
     */
    fun copyToClipboard(context: Context, text: String) {
        if (text.isBlank()) return

        val clipboard = context.getSystemService(Context.CLIPBOARD_SERVICE) as ClipboardManager
        val clip = ClipData.newPlainText("B.b.Renderer Selection", text)
        clipboard.setPrimaryClip(clip)

        Toast.makeText(context, "コピーしました", Toast.LENGTH_SHORT).show()
    }

    /**
     * navigator.clipboard.readText()相当。OSクリップボードにテキスト以外(画像等)が
     * 入っている、または何も入っていない場合はnullを返す(呼び出し側でPromiseのreject
     * 相当として扱う想定、js/JsWindow.ktのJsClipboard.readText()参照)。
     *
     * 【安全上の注意】クリップボードの読み取りは書き込みより機微度が高い
     * (他アプリでコピーしたパスワードやワンタイムコード等が残っている可能性がある)。
     * このメソッド自体はOS側の許可チェックを一切行わないため、呼び出し元
     * (BrowserCapabilityBridge.readClipboardText())で必ずSitePermissions.CLIPBOARD_READ
     * の許可判定を先に済ませること。
     */
    fun readClipboardText(context: Context): String? {
        val clipboard = context.getSystemService(Context.CLIPBOARD_SERVICE) as ClipboardManager
        if (!clipboard.hasPrimaryClip()) return null
        val clip = clipboard.primaryClip ?: return null
        if (clip.itemCount == 0) return null
        return clip.getItemAt(0).coerceToText(context)?.toString()
    }
}
