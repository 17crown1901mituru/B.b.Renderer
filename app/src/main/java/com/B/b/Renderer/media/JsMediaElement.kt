package com.B.b.Renderer.media

import android.content.Context
import android.content.Intent
import android.content.pm.PackageManager
import android.graphics.SurfaceTexture
import android.net.Uri
import android.view.Surface
import androidx.media3.common.C
import androidx.media3.common.MediaItem
import androidx.media3.common.PlaybackException
import androidx.media3.common.Player
import androidx.media3.exoplayer.ExoPlayer
import org.mozilla.javascript.Context as RhinoContext
import org.mozilla.javascript.Function
import org.mozilla.javascript.ScriptableObject

/**
 * <video>/<audio> の DOM ノードに対応する Rhino バインディング。
 * 実際のデコード/再生は ExoPlayer に委譲し、ここは「操縦桿」のみを提供する。
 *
 * layoutRectProvider: レイアウトエンジン側が計算した現在の矩形を取得するコールバック。
 * onUnsupported: canPlayType で候補が全滅した際に、エンジン側(描画層)へ通知するコールバック。
 */
open class JsMediaElement(
    private val appContext: Context,
    private val isVideo: Boolean,
    private val layoutRectProvider: () -> LayoutRect,
    private val onUnsupported: (MediaSourceCandidate?) -> Unit,
    private val requestRedraw: () -> Unit,
) : ScriptableObject() {

    private var player: ExoPlayer? = null
    private var surfaceTexture: SurfaceTexture? = null
    private var pendingSurfacePlayer: ExoPlayer? = null
    var textureId: Int = -1
        private set

    private val eventListeners = mutableMapOf<String, MutableList<Function>>()
    private var sourceCandidates: List<MediaSourceCandidate> = emptyList()
    private var currentUnsupported: MediaSourceCandidate? = null

    override fun getClassName(): String = if (isVideo) "HTMLVideoElement" else "HTMLAudioElement"

    /** GLEngineRenderer側が「テクスチャ確保が必要な要素か」を判定するために公開する */
    val isVideoElement: Boolean get() = isVideo

    // ---- src / source候補 ----

    fun jsSet_src(value: String) {
        sourceCandidates = listOf(MediaSourceCandidate(value, guessMimeType(value)))
        loadBestSource()
    }

    fun jsGet_src(): String = sourceCandidates.firstOrNull()?.url ?: ""

    /** <source>タグ複数指定時に、パース層から渡される */
    fun setSourceCandidates(candidates: List<MediaSourceCandidate>) {
        sourceCandidates = candidates
        loadBestSource()
    }

    private fun loadBestSource() {
        releasePlayer()
        val playable = CodecSupportChecker.selectPlayableSource(sourceCandidates)
        if (playable == null) {
            currentUnsupported = sourceCandidates.firstOrNull()
            onUnsupported(currentUnsupported)
            emitEvent("error", "no supported source")
            return
        }
        currentUnsupported = null
        startPlayback(playable)
    }

    private fun startPlayback(source: MediaSourceCandidate) {
        val exo = ExoPlayer.Builder(appContext).build().also { player = it }
        exo.addListener(createPlayerListener())
        exo.setMediaItem(MediaItem.fromUri(source.url))
        exo.prepare()
        if (isVideo) {
            if (textureId != -1) {
                attachSurfaceTexture(exo)
            } else {
                // GLスレッド側でのOESテクスチャ確保がまだ済んでいない。bindTextureId到着時にアタッチする。
                pendingSurfacePlayer = exo
            }
        }
    }

    private fun releasePlayer() {
        player?.release()
        player = null
        pendingSurfacePlayer = null
        surfaceTexture?.release()
        surfaceTexture = null
    }

    // ---- 再生制御メソッド (jsFunction_ プレフィックスでRhinoが自動バインド) ----

    fun jsFunction_play() {
        val p = player
        if (p == null) {
            emitEvent("error", "no source")
            return
        }
        p.play()
    }

    fun jsFunction_pause() {
        player?.pause()
    }

    fun jsFunction_load() {
        player?.prepare()
    }

    fun jsFunction_canPlayType(mimeType: String): String = CodecSupportChecker.query(mimeType)

    fun jsFunction_addEventListener(type: String, callback: Function) {
        eventListeners.getOrPut(type) { mutableListOf() }.add(callback)
    }

    fun jsFunction_removeEventListener(type: String, callback: Function) {
        eventListeners[type]?.remove(callback)
    }

    /** 非対応時、ユーザーがプレースホルダーをタップしたら呼ばれる想定 */
    fun jsFunction_openWithExternalApp() {
        val target = currentUnsupported ?: return
        ExternalPlayerFallback.showChooser(appContext, target)
    }

    // ---- プロパティ ----

    fun jsGet_currentTime(): Double = (player?.currentPosition ?: 0L) / 1000.0
    fun jsSet_currentTime(seconds: Double) {
        player?.seekTo((seconds * 1000).toLong())
    }

    fun jsGet_duration(): Double {
        val d = player?.duration ?: return Double.NaN
        return if (d == C.TIME_UNSET) Double.NaN else d / 1000.0
    }

    fun jsGet_paused(): Boolean = player?.isPlaying?.not() ?: true
    fun jsGet_ended(): Boolean = player?.playbackState == Player.STATE_ENDED

    fun jsGet_muted(): Boolean = player?.volume == 0f
    fun jsSet_muted(v: Boolean) {
        player?.volume = if (v) 0f else 1f
    }

    fun jsGet_volume(): Double = (player?.volume ?: 1f).toDouble()
    fun jsSet_volume(v: Double) {
        player?.volume = v.toFloat().coerceIn(0f, 1f)
    }

    // ---- ExoPlayerイベント → JSイベントへの橋渡し ----

    private fun createPlayerListener() = object : Player.Listener {
        override fun onPlaybackStateChanged(state: Int) {
            when (state) {
                Player.STATE_READY -> emitEvent("canplaythrough")
                Player.STATE_ENDED -> emitEvent("ended")
                Player.STATE_BUFFERING -> emitEvent("waiting")
            }
        }

        override fun onIsPlayingChanged(isPlaying: Boolean) {
            emitEvent(if (isPlaying) "play" else "pause")
        }

        override fun onPlayerError(error: PlaybackException) {
            // 再生開始後の実行時エラー。ここでも非対応表示に倒す。
            currentUnsupported = sourceCandidates.firstOrNull()
            onUnsupported(currentUnsupported)
            emitEvent("error", error.message ?: "playback error")
        }

        override fun onPositionDiscontinuity(
            oldPos: Player.PositionInfo,
            newPos: Player.PositionInfo,
            reason: Int,
        ) {
            emitEvent("timeupdate")
        }
    }

    /**
     * Rhinoの Context はスレッドローカルなので、呼び出しスレッド上で
     * enter/exit を必ず対にする。JS評価スレッドを分離している構成では
     * ここをキュー経由の呼び出しに差し替えること。
     */
    private fun emitEvent(type: String, detail: Any? = null) {
        val listeners = eventListeners[type] ?: return
        val rhinoCtx = RhinoContext.enter()
        try {
            val scope = getTopLevelScope(this)
            listeners.forEach { fn -> fn.call(rhinoCtx, scope, this, arrayOf(detail)) }
        } finally {
            RhinoContext.exit()
        }
    }

    // ---- 映像フレームのGPU直結 (videoのみ) ----

    private fun attachSurfaceTexture(exo: ExoPlayer) {
        surfaceTexture = SurfaceTexture(textureId).apply {
            setOnFrameAvailableListener {
                requestRedraw()
            }
        }
        exo.setVideoSurface(Surface(surfaceTexture))
    }

    /**
     * GLコンテキスト確立後、外部からtextureIdを注入する（GL呼び出しはGLスレッド限定のため）。
     * 既に再生開始済みでSurfaceTexture未アタッチだった場合(pendingSurfacePlayer)はここで確定させる。
     */
    fun bindTextureId(id: Int) {
        textureId = id
        val pending = pendingSurfacePlayer
        if (pending != null) {
            attachSurfaceTexture(pending)
            pendingSurfacePlayer = null
        }
    }

    /**
     * GLスレッドから毎フレーム呼ぶ。最新の映像フレームをテクスチャに反映し、
     * UV補正行列(SurfaceTextureの回転/クロップ分)をoutTransformMatrixへ書き込む。
     * SurfaceTexture未確立(再生開始前 or 音声のみ)ならfalseを返す。
     */
    fun updateTexImage(outTransformMatrix: FloatArray): Boolean {
        val st = surfaceTexture ?: return false
        return try {
            st.updateTexImage()
            st.getTransformMatrix(outTransformMatrix)
            true
        } catch (e: IllegalStateException) {
            // GLコンテキストがカレントでない等、稀な失敗はこのフレームをスキップして継続する
            false
        }
    }

    fun hasVideoSurface(): Boolean = isVideo && surfaceTexture != null

    fun release() {
        releasePlayer()
        eventListeners.clear()
    }

    private fun guessMimeType(url: String): String = when {
        url.endsWith(".mp4") -> "video/mp4"
        url.endsWith(".webm") -> "video/webm"
        url.endsWith(".m3u8") -> "application/x-mpegURL"
        url.endsWith(".mp3") -> "audio/mpeg"
        url.endsWith(".ogg") -> "audio/ogg"
        else -> if (isVideo) "video/*" else "audio/*"
    }
}

data class LayoutRect(val x: Int, val y: Int, val width: Int, val height: Int) {
    fun center(): Pair<Int, Int> = (x + width / 2) to (y + height / 2)
}
