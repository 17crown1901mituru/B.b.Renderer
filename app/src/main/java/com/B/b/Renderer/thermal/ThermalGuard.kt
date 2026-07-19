package com.B.b.Renderer.thermal

import android.app.Activity
import android.os.Build
import android.os.PowerManager
import com.B.b.Renderer.debug.BehaviorAuditLog

/**
 * 端末の温度状態を監視し、悪化したら呼び出し元に知らせる。
 *
 * 複数タブを同時に生かし続ける(pinned)機能は、メモリ・GPUの話以上に発熱の話が
 * 本質的なボトルネックになる(2026-07議論分)。個々のタブの負荷を厳密に見積もるより、
 * OS自体が判定する`PowerManager`の温度状態を信頼し、悪化したら問答無用でpinned数を
 * 減らす、という単純な方針にしている。
 *
 * API 29(Android 10)未満では温度状態APIが無いため、常にNONE(何もしない)扱いにする。
 */
class ThermalGuard(private val activity: Activity) {

    private val powerManager = activity.getSystemService(Activity.POWER_SERVICE) as? PowerManager
    private var listener: PowerManager.OnThermalStatusChangedListener? = null

    /** THERMAL_STATUS_MODERATE(3)以上になったら呼ばれる。呼び出し側でpinnedタブを減らす等の対応をする。 */
    fun startMonitoring(onThrottleNeeded: () -> Unit) {
        if (Build.VERSION.SDK_INT < Build.VERSION_CODES.Q) return
        val pm = powerManager ?: return
        val l = PowerManager.OnThermalStatusChangedListener { status ->
            BehaviorAuditLog.record(BehaviorAuditLog.Category.JS_EVAL, "thermal status changed: $status")
            if (status >= PowerManager.THERMAL_STATUS_MODERATE) {
                onThrottleNeeded()
            }
        }
        listener = l
        pm.addThermalStatusListener(l)
    }

    fun currentStatusIsElevated(): Boolean {
        if (Build.VERSION.SDK_INT < Build.VERSION_CODES.Q) return false
        val status = powerManager?.currentThermalStatus ?: return false
        return status >= PowerManager.THERMAL_STATUS_MODERATE
    }

    fun stopMonitoring() {
        if (Build.VERSION.SDK_INT < Build.VERSION_CODES.Q) return
        listener?.let { powerManager?.removeThermalStatusListener(it) }
        listener = null
    }
}
