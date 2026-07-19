package com.B.b.Renderer.tabs

import android.app.Notification
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.app.Service
import android.content.Intent
import android.os.Build
import android.os.IBinder
import com.B.b.Renderer.EngineActivity

/**
 * ピン留めタブ(pinned)を、Activity破棄後もプロセスごと生かし続けるためのForeground Service。
 *
 * このサービス自体はJsEngine等の実体を持たない。TabManager/JsEngineは引き続き
 * EngineActivity(のプロセス)側に存在する。このサービスの役割は「フォアグラウンド
 * サービスとして起動している間、OSがこのプロセスを優先的に殺さないようにする」
 * という、通知を伴う延命シグナルだけを担う。
 *
 * 起動/停止はEngineActivity側がpinned数の増減に合わせて呼ぶ(0件になったら停止)。
 */
class TabKeepAliveService : Service() {

    companion object {
        const val CHANNEL_ID = "tab_keep_alive"
        const val NOTIFICATION_ID = 42
        const val EXTRA_PINNED_COUNT = "pinned_count"
    }

    override fun onBind(intent: Intent?): IBinder? = null

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        val pinnedCount = intent?.getIntExtra(EXTRA_PINNED_COUNT, 0) ?: 0
        startForeground(NOTIFICATION_ID, buildNotification(pinnedCount))
        return START_STICKY
    }

    private fun buildNotification(pinnedCount: Int): Notification {
        ensureChannel()
        val openAppIntent = PendingIntent.getActivity(
            this,
            0,
            Intent(this, EngineActivity::class.java),
            PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE,
        )
        return Notification.Builder(this, CHANNEL_ID)
            .setContentTitle("B.b.Renderer")
            .setContentText("ピン留め中のタブ ${pinnedCount}件を裏で実行中")
            .setSmallIcon(android.R.drawable.ic_dialog_info) // TODO: 専用アイコンが用意でき次第差し替える(現状R.drawable自体が未整備)
            .setContentIntent(openAppIntent)
            .setOngoing(true)
            .build()
    }

    private fun ensureChannel() {
        if (Build.VERSION.SDK_INT < Build.VERSION_CODES.O) return
        val manager = getSystemService(NotificationManager::class.java)
        if (manager.getNotificationChannel(CHANNEL_ID) != null) return
        val channel = NotificationChannel(
            CHANNEL_ID,
            "ピン留めタブの実行維持",
            NotificationManager.IMPORTANCE_LOW,
        )
        manager.createNotificationChannel(channel)
    }
}
