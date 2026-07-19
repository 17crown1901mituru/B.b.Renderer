package com.B.b.Renderer.media

import android.content.Context
import android.content.Intent
import android.net.Uri
import android.widget.Toast

/**
 * canPlayType() が全滅した場合のみ使う保険ルート。
 * 特定アプリを決め打ちせず、OSに選択を委ねる。
 */
object ExternalPlayerFallback {

    fun showChooser(context: Context, target: MediaSourceCandidate) {
        val playIntent = Intent(Intent.ACTION_VIEW).apply {
            setDataAndType(Uri.parse(target.url), target.mimeType)
        }

        val resolvedApps = context.packageManager.queryIntentActivities(playIntent, 0)
        if (resolvedApps.isEmpty()) {
            Toast.makeText(context, "この端末には対応する再生アプリがありません", Toast.LENGTH_SHORT).show()
            return
        }

        val chooser = Intent.createChooser(playIntent, "再生アプリを選択").apply {
            addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
        }
        context.startActivity(chooser)
    }
}
