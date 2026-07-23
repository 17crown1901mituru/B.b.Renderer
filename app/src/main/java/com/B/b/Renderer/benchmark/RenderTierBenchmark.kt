package com.B.b.Renderer.benchmark

import android.content.Context
import android.os.Build
import android.os.PowerManager
import android.util.Log

/**
 * 「GPU拡張の有無でTier判定しているが、実際にそのTierが速いのかは測っていない」という
 * 課題への対応。ただし現状のQuadBatchRenderer/AtlasQuadRendererは元々1バッチ=1 drawCallに
 * 収まっており、GL_OES_vertex_array_object/GL_EXT_multi_draw_arraysが効くような
 * 「大量のdrawCallをまとめる」場面が無いため、拡張単位でのA/B比較は行わない
 * (2026-07議論分。拡張パスを新規実装してからの比較は別タスクとして保留)。
 *
 * ここでやるのはもっと手前の話: 「GLES3.0 GPU描画パスを選んだこと自体が、この実機にとって
 * 妥当だったか」を実測で検証すること。GpuCapabilityDetectorは静的なEGL属性しか見ておらず、
 * 「対応しているはず」止まりで「実際に快適か」は分からない。
 *
 * 判定は端末のBuild.FINGERPRINT単位でSharedPreferencesにキャッシュし、確定した端末には
 * 毎起動ベンチマークを強制しない(無駄に負荷をかけ続けない)。
 *
 * 1回のセッション(1起動あたり通常1回、GLコンテキストの生成につき1回)の結果だけを
 * ただちに確定させることはしない。1回だけの計測は「たまたまその時重かった/軽かった」
 * というノイズを拾いやすいため、REQUIRED_AGREEING_SESSIONS回ぶんのセッション結果が
 * 集まるまでは投票として蓄積し、集まった時点で多数決で確定する(2026-07議論分、
 * 単発サンプルを恒久判定にしないための変更)。サンプリング中に端末が発熱していた場合は
 * そのセッションを投票にすら含めず破棄する(一時的なスロットリングの混入を防ぐため)。
 */
object RenderTierBenchmark {

    enum class Verdict { UNKNOWN, GPU_OK, GPU_SLOW }

    private const val TAG = "RenderTierBenchmark"
    private const val PREFS_NAME = "render_tier_benchmark"

    // 起動直後のシェーダーコンパイル・ドライバのウォームアップの揺れを避けるため、
    // 最初の数フレームは計測に含めない。
    private const val WARMUP_FRAMES = 10
    private const val SAMPLE_FRAMES = 30

    // 30fps相当(33ms/frame)を継続的に割るようなら「このGPU経路はこの端末には重い」と判断する。
    private const val SLOW_FRAME_BUDGET_MS = 33.0

    // この回数ぶんのセッション結果が集まって初めて確定させる(偶数だと多数決で割れうるので奇数)。
    private const val REQUIRED_AGREEING_SESSIONS = 3

    private var warmupRemaining = 0
    private val samples = mutableListOf<Long>()
    private var sessionActive = false
    private var discarded = false

    /** デバッグドロワー等での表示用。確定前は常にUNKNOWN。 */
    fun currentVerdict(context: Context): Verdict = readVerdict(context)

    /** 未確定の端末でのみtrue。onSurfaceCreated側で、これがtrueのときだけbeginSession()する。 */
    fun shouldRunSession(context: Context): Boolean = readVerdict(context) == Verdict.UNKNOWN

    /** 確定済みの判定がGPU_SLOWなら、Tier判定に関わらずCanvas版を使わせる。 */
    fun shouldForceCanvasFallback(context: Context): Boolean = readVerdict(context) == Verdict.GPU_SLOW

    /** 誤判定に気づいた場合等の手動リセット用(デバッグドロワーからの呼び出しを想定)。 */
    fun reset(context: Context) {
        prefs(context).edit().clear().apply()
    }

    /** GLコンテキストが新規に立った(onSurfaceCreated)タイミングで呼ぶ。 */
    fun beginSession() {
        warmupRemaining = WARMUP_FRAMES
        samples.clear()
        sessionActive = true
        discarded = false
    }

    /**
     * onDrawFrameの最後(実際の描画コマンド発行が終わった時点)で毎フレーム呼ぶ。
     * frameNanosはそのフレームの描画部分だけの所要時間(System.nanoTime()の差分)。
     */
    fun recordFrame(context: Context, frameNanos: Long) {
        if (!sessionActive || discarded) return

        if (isThermalElevated(context)) {
            discarded = true
            sessionActive = false
            Log.i(TAG, "benchmark discarded: thermal status elevated mid-sample, will retry next launch")
            return
        }

        if (warmupRemaining > 0) {
            warmupRemaining--
            return
        }

        samples.add(frameNanos)
        if (samples.size >= SAMPLE_FRAMES) {
            finishSession(context)
        }
    }

    private fun finishSession(context: Context) {
        sessionActive = false
        val avgMs = samples.map { it / 1_000_000.0 }.average()
        val sessionVerdict = if (avgMs > SLOW_FRAME_BUDGET_MS) Verdict.GPU_SLOW else Verdict.GPU_OK
        Log.i(TAG, "session result: avg=%.1fms verdict=%s (fingerprint=%s)".format(avgMs, sessionVerdict, Build.FINGERPRINT))
        samples.clear()
        recordVote(context, sessionVerdict)
    }

    /** このセッションの投票を積み、必要数集まったら多数決で確定・永続化する。 */
    private fun recordVote(context: Context, sessionVerdict: Verdict) {
        val p = prefs(context)
        val okKey = "${votePrefix()}_ok"
        val slowKey = "${votePrefix()}_slow"

        val okCount = p.getInt(okKey, 0) + if (sessionVerdict == Verdict.GPU_OK) 1 else 0
        val slowCount = p.getInt(slowKey, 0) + if (sessionVerdict == Verdict.GPU_SLOW) 1 else 0
        val totalVotes = okCount + slowCount

        if (totalVotes < REQUIRED_AGREEING_SESSIONS) {
            p.edit().putInt(okKey, okCount).putInt(slowKey, slowCount).apply()
            Log.i(TAG, "vote recorded ($totalVotes/$REQUIRED_AGREEING_SESSIONS): ok=$okCount slow=$slowCount, not final yet")
            return
        }

        val finalVerdict = if (slowCount > okCount) Verdict.GPU_SLOW else Verdict.GPU_OK
        Log.i(TAG, "verdict finalized after $totalVotes sessions: ok=$okCount slow=$slowCount -> $finalVerdict")
        p.edit()
            .putString(verdictKey(), finalVerdict.name)
            .remove(okKey)
            .remove(slowKey)
            .apply()
    }

    private fun isThermalElevated(context: Context): Boolean {
        if (Build.VERSION.SDK_INT < Build.VERSION_CODES.Q) return false
        val pm = context.applicationContext.getSystemService(Context.POWER_SERVICE) as? PowerManager
            ?: return false
        return pm.currentThermalStatus >= PowerManager.THERMAL_STATUS_MODERATE
    }

    private fun prefs(context: Context) =
        context.applicationContext.getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE)

    // 同一端末でもOSアップデート等でFINGERPRINTが変われば再計測させたいので、
    // キーにハッシュを含める(端末を跨いだ使い回しは想定しないので単純な文字列キーで十分)。
    private fun votePrefix(): String = "votes_${Build.FINGERPRINT.hashCode()}"
    private fun verdictKey(): String = "verdict_${Build.FINGERPRINT.hashCode()}"

    private fun readVerdict(context: Context): Verdict {
        val raw = prefs(context).getString(verdictKey(), null) ?: return Verdict.UNKNOWN
        return runCatching { Verdict.valueOf(raw) }.getOrDefault(Verdict.UNKNOWN)
    }
}
