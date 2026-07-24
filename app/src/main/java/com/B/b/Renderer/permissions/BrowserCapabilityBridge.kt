package com.B.b.Renderer.permissions

import android.Manifest
import android.app.Activity
import android.content.pm.ActivityInfo
import android.content.pm.PackageManager
import android.location.Location
import android.location.LocationListener
import android.location.LocationManager
import android.os.Build
import android.os.Handler
import android.os.VibrationEffect
import android.os.Vibrator
import android.os.VibratorManager
import android.view.WindowManager
import androidx.core.app.ActivityCompat
import androidx.core.content.ContextCompat

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

    /** localStorage等、Context起点の処理(SharedPreferences等)から使う。Activity自体もContext。 */
    val context: android.content.Context get() = activity

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

    // --- navigator.geolocation ---
    //
    // サイト単位の許可(SitePermissions.GEOLOCATION、既定不許可)に加えて、
    // OSレベルのACCESS_FINE/COARSE_LOCATION権限が必要。後者は初回要求時に
    // システムの許可ダイアログを挟む必要があるため、結果はコールバックで
    // 非同期に返す(ActivityCompat.requestPermissions → EngineActivity#onRequestPermissionsResult
    // → onLocationPermissionResult()、という往復になる)。

    private var pendingLocationRequest: Pair<(Double, Double, Float) -> Unit, (String) -> Unit>? = null

    private fun hasLocationPermission(): Boolean =
        ContextCompat.checkSelfPermission(activity, Manifest.permission.ACCESS_FINE_LOCATION) ==
            PackageManager.PERMISSION_GRANTED ||
            ContextCompat.checkSelfPermission(activity, Manifest.permission.ACCESS_COARSE_LOCATION) ==
            PackageManager.PERMISSION_GRANTED

    fun getCurrentLocation(
        domain: String,
        onSuccess: (lat: Double, lon: Double, accuracyMeters: Float) -> Unit,
        onError: (message: String) -> Unit,
    ) {
        if (!sitePermissions.isAllowed(domain, SitePermissions.Capability.GEOLOCATION)) {
            onError("permission denied (site setting)")
            return
        }
        if (!hasLocationPermission()) {
            pendingLocationRequest = onSuccess to onError
            ActivityCompat.requestPermissions(
                activity,
                arrayOf(Manifest.permission.ACCESS_FINE_LOCATION, Manifest.permission.ACCESS_COARSE_LOCATION),
                LOCATION_PERMISSION_REQUEST_CODE,
            )
            return
        }
        fetchLocation(onSuccess, onError)
    }

    /** EngineActivity#onRequestPermissionsResultから転送してもらう想定。 */
    fun onLocationPermissionResult(requestCode: Int, grantResults: IntArray) {
        if (requestCode != LOCATION_PERMISSION_REQUEST_CODE) return
        val pending = pendingLocationRequest ?: return
        pendingLocationRequest = null
        if (hasLocationPermission()) {
            fetchLocation(pending.first, pending.second)
        } else {
            pending.second("permission denied (OS)")
        }
    }

    private fun fetchLocation(onSuccess: (Double, Double, Float) -> Unit, onError: (String) -> Unit) {
        if (!hasLocationPermission()) {
            onError("permission denied (OS)")
            return
        }
        val locationManager = activity.getSystemService(Activity.LOCATION_SERVICE) as? LocationManager
        if (locationManager == null) {
            onError("location service unavailable")
            return
        }
        val providers = listOf(LocationManager.GPS_PROVIDER, LocationManager.NETWORK_PROVIDER)
            .filter { runCatching { locationManager.isProviderEnabled(it) }.getOrDefault(false) }
        if (providers.isEmpty()) {
            onError("location providers disabled")
            return
        }

        // Web Geolocationの「すぐ返る」体感に近づけるため、まず直近の既知位置を優先する。
        // 実機で新規測位を待つとgetCurrentPosition()が数秒〜十数秒ブロックされて体感が悪いため。
        @Suppress("MissingPermission")
        val lastKnown = providers.mapNotNull { runCatching { locationManager.getLastKnownLocation(it) }.getOrNull() }
            .maxByOrNull { it.time }
        if (lastKnown != null) {
            onSuccess(lastKnown.latitude, lastKnown.longitude, lastKnown.accuracy)
            return
        }

        requestFreshLocation(locationManager, providers.first(), onSuccess, onError)
    }

    private fun requestFreshLocation(
        locationManager: LocationManager,
        provider: String,
        onSuccess: (Double, Double, Float) -> Unit,
        onError: (String) -> Unit,
    ) {
        var resolved = false
        val listener = object : LocationListener {
            override fun onLocationChanged(location: Location) {
                if (resolved) return
                resolved = true
                locationManager.removeUpdates(this)
                onSuccess(location.latitude, location.longitude, location.accuracy)
            }
        }
        val requested = runCatching {
            @Suppress("MissingPermission")
            locationManager.requestSingleUpdate(provider, listener, activity.mainLooper)
        }
        if (requested.isFailure) {
            onError("failed to request location: ${requested.exceptionOrNull()?.message}")
            return
        }
        Handler(activity.mainLooper).postDelayed({
            if (!resolved) {
                resolved = true
                locationManager.removeUpdates(listener)
                onError("location request timed out")
            }
        }, LOCATION_TIMEOUT_MS)
    }

    companion object {
        private const val LOCATION_PERMISSION_REQUEST_CODE = 9301
        private const val LOCATION_TIMEOUT_MS = 10_000L
    }
}
