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
}
