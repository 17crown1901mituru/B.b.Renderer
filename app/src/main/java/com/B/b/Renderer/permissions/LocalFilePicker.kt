package com.B.b.Renderer.permissions

import android.net.Uri
import android.util.Log
import androidx.activity.ComponentActivity
import androidx.activity.result.contract.ActivityResultContracts

/**
 * ローカルファイル(開発中のHTMLの動作確認用途)を、SAF(Storage Access Framework、
 * システム標準のファイルピッカー)経由で開くためのヘルパー。
 *
 * 2026-08、「file://を手打ちしてアプリ専用ディレクトリに手動配置する」運用が
 * 面倒だという指摘を受けて追加。ドロワーに「ファイルを開く」ボタンを置き、ここ経由で
 * システムのファイルピッカーを呼び出す(DebugDrawerView.onOpenLocalFileRequested→
 * EngineActivity.localFilePicker.launch()という配線)。
 *
 * file://直接指定(EngineActivity.readLocalFile()参照)との違い:
 * SAF経由で得られる`content://` URIは、ユーザーがピッカーで明示的に選んだファイルに
 * 対する一時的な読み取り権限として自動的に付与されるため、Android 10以降の
 * スコープドストレージの制限を受けず、公開ストレージ(Downloads等)のどこにある
 * ファイルでも追加の権限宣言・実行時許可無しで開ける。これが正攻法。
 *
 * ActivityResultContractsの登録タイミングの制約はRuntimePermissionManagerと同じ
 * (Activityが STARTED になる前、つまりonCreate最初期でインスタンス化すること。
 * by lazyにしてはいけない)。
 */
class LocalFilePicker(activity: ComponentActivity, private val onPicked: (Uri) -> Unit) {

    private val launcher = activity.registerForActivityResult(
        ActivityResultContracts.OpenDocument(),
    ) { uri ->
        if (uri != null) {
            onPicked(uri)
        } else {
            Log.i(TAG, "file picker cancelled")
        }
    }

    /**
     * ファイルピッカーを開く。mimeTypeは意図的に"*/*"にしている——HTMLファイルの
     * Content-Typeはファイルマネージャ/プロバイダによって"text/html"/"text/plain"/
     * "application/octet-stream"等まちまちで、"text/html"に絞ると実機で候補から
     * 弾かれてしまうことが多いため(拡張子ベースの厳密フィルタは行わず、
     * どんなファイルでも選べるようにしておく実用上の判断)。
     */
    fun launch() {
        launcher.launch(arrayOf("*/*"))
    }

    companion object {
        private const val TAG = "LocalFilePicker"
    }
}
