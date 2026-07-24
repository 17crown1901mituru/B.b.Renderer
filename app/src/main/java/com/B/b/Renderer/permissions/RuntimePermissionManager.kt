package com.B.b.Renderer.permissions

import android.Manifest
import android.content.pm.PackageManager
import android.os.Build
import android.util.Log
import androidx.activity.ComponentActivity
import androidx.activity.result.contract.ActivityResultContracts
import androidx.core.content.ContextCompat

/**
 * 実行時権限(Android 6.0以降でダイアログ表示が必要なもの)をまとめて要求する。
 *
 * 現状このアプリが実際に必要とする実行時権限は POST_NOTIFICATIONS (API 33+、
 * TabKeepAliveServiceのForeground Service通知用)のみ。AndroidManifest.xmlには
 * 宣言済みだったが、requestPermissions側の実装が無く、Android 13+実機では
 * 通知が出ないまま(≒pinnedタブが動いているのかユーザーから見えない)状態だった。
 *
 * 今後ダウンロード機能等で権限が増えた場合は REQUIRED_PERMISSIONS に追記するだけでよい
 * (呼び出し側のEngineActivity#onCreateは変更不要)。
 *
 * ActivityResultContractsの登録は、Activityが STARTED になる前(=onCreate内、
 * super.onCreate()より後で構わないが最初期)に行う必要があるため、フィールド初期化ではなく
 * このクラスのコンストラクタで直接registerForActivityResultを呼ぶ。呼び出し側は
 * onCreateの一番最初で `RuntimePermissionManager(this)` をインスタンス化すること。
 */
class RuntimePermissionManager(private val activity: ComponentActivity) {

    private val launcher = activity.registerForActivityResult(
        ActivityResultContracts.RequestMultiplePermissions(),
    ) { results ->
        results.forEach { (permission, granted) ->
            if (granted) {
                Log.i(TAG, "granted: $permission")
            } else {
                // 拒否されても致命的ではない設計にすること。例えばPOST_NOTIFICATIONS拒否時、
                // TabKeepAliveServiceはstartForeground自体は成功し通知だけ出ない
                // (Android側の挙動。ここでクラッシュや機能停止はしない)。
                Log.w(TAG, "denied: $permission")
            }
        }
    }

    /** まだ許可されていないものだけ抜き出してダイアログを出す。全て許可済みなら何もしない。 */
    fun requestAllIfNeeded() {
        val missing = REQUIRED_PERMISSIONS.filter { permission ->
            minSdkFor(permission)?.let { Build.VERSION.SDK_INT >= it } != false &&
                ContextCompat.checkSelfPermission(activity, permission) != PackageManager.PERMISSION_GRANTED
        }
        if (missing.isNotEmpty()) {
            launcher.launch(missing.toTypedArray())
        }
    }

    companion object {
        private const val TAG = "RuntimePermissionManager"

        /**
         * 実行時ダイアログが必要な権限の一覧。INTERNET/VIBRATE/FOREGROUND_SERVICE*のような
         * normal permissionはここに含めない(Manifest宣言だけで自動的に付与されるため)。
         */
        private val REQUIRED_PERMISSIONS: List<String> = buildList {
            add(Manifest.permission.POST_NOTIFICATIONS)
        }

        /** その権限がAPIいくつから「実行時権限」になったか。該当しないものはnull(=常に対象)。 */
        private fun minSdkFor(permission: String): Int? = when (permission) {
            Manifest.permission.POST_NOTIFICATIONS -> Build.VERSION_CODES.TIRAMISU
            else -> null
        }
    }
}
