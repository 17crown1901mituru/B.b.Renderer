package com.B.b.Renderer.permissions

import android.app.Activity
import android.content.pm.ActivityInfo
import android.os.Build
import android.os.VibrationEffect
import android.os.Vibrator
import android.os.VibratorManager
import android.view.WindowManager

/**
 * navigator.vibrate() / navigator.wakeLock / screen.orientation.lock() の実処理。
 *
 * 要求元によって参照する設定が異なる:
 *   - ページ側JS(fromUser=false、既定): SitePermissions(ドメイン単位の許可)を確認
 *   - ユーザー自身の操作(fromUser=true): GlobalAppSettings(アプリ全体設定)を確認。
 *     ドメインには一切依存しない(今どのサイトを見ていても同じ設定が効く)
 */
class BrowserCapabilityBridge(
    private val activity: Activity,
    private val sitePermissions: SitePermissions,
    private val globalSettings: GlobalAppSettings,
) {
    private var wakeLockHeld = false

    fun vibrate(domain: String, patternMs: LongArray, fromUser: Boolean = false): Boolean {
        val allowed = if (fromUser) {
            globalSettings.userVibrationEnabled
        } else {
            sitePermissions.isAllowed(domain, SitePermissions.Capability.VIBRATE)
        }
        if (!allowed) return false
        val vibrator = getVibrator() ?: return false
        vibrator.vibrate(VibrationEffect.createWaveform(patternMs, -1))
        return true
    }

    fun requestWakeLock(domain: String, fromUser: Boolean = false): Boolean {
        val allowed = if (fromUser) {
            true // ユーザー自身の明示操作は常に許可(GlobalAppSettings.userKeepScreenOnの変更そのものがこの呼び出し)
        } else {
            sitePermissions.isAllowed(domain, SitePermissions.Capability.WAKE_LOCK)
        }
        if (!allowed) return false
        activity.runOnUiThread {
            activity.window.addFlags(WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON)
        }
        wakeLockHeld = true
        return true
    }

    fun releaseWakeLock() {
        if (!wakeLockHeld) return
        // ユーザーが「常時スリープ防止」を有効にしている間は、ページ遷移では解除しない
        if (globalSettings.userKeepScreenOn) return
        activity.runOnUiThread {
            activity.window.clearFlags(WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON)
        }
        wakeLockHeld = false
    }

    /** type: "portrait" | "landscape" | "portrait-primary" | "landscape-primary" 等(簡略化) */
    fun lockOrientation(domain: String, type: String, fromUser: Boolean = false): Boolean {
        val allowed = if (fromUser) {
            true // ユーザー自身の明示操作(アプリ全体設定の変更)は常に許可
        } else {
            sitePermissions.isAllowed(domain, SitePermissions.Capability.ORIENTATION_LOCK)
        }
        if (!allowed) return false
        applyOrientation(type)
        return true
    }

    fun unlockOrientation() {
        // ユーザーが全体設定で固定を選んでいる場合は、ページ遷移では解除せずその設定を維持する
        val userLock = globalSettings.userOrientationLock
        if (userLock.isNotBlank()) {
            applyOrientation(userLock)
            return
        }
        activity.runOnUiThread {
            activity.requestedOrientation = ActivityInfo.SCREEN_ORIENTATION_UNSPECIFIED
        }
    }

    private fun applyOrientation(type: String) {
        val orientation = when {
            type.startsWith("landscape") -> ActivityInfo.SCREEN_ORIENTATION_LANDSCAPE
            type.startsWith("portrait") -> ActivityInfo.SCREEN_ORIENTATION_PORTRAIT
            else -> ActivityInfo.SCREEN_ORIENTATION_UNSPECIFIED
        }
        activity.runOnUiThread { activity.requestedOrientation = orientation }
    }

    private fun getVibrator(): Vibrator? =
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.S) {
            val manager = activity.getSystemService(Activity.VIBRATOR_MANAGER_SERVICE) as? VibratorManager
            manager?.defaultVibrator
        } else {
            @Suppress("DEPRECATION")
            activity.getSystemService(Activity.VIBRATOR_SERVICE) as? Vibrator
        }
}
