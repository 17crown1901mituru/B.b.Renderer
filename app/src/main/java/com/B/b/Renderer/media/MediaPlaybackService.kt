package com.B.b.Renderer.media

import androidx.media3.common.MediaMetadata
import androidx.media3.exoplayer.ExoPlayer
import androidx.media3.session.MediaSession
import androidx.media3.session.MediaSessionService
import org.mozilla.javascript.Function
import org.mozilla.javascript.ScriptableObject

/**
 * mediaPlayback フォアグラウンドサービス。
 * MediaSession をバインドした時点でロック画面/通知/Bluetoothボタンの
 * 連携はOS側が自動で処理する。
 *
 * マニフェスト側で以下が必須:
 *   <uses-permission android:name="android.permission.FOREGROUND_SERVICE_MEDIA_PLAYBACK" />
 *   <service android:name=".MediaPlaybackService"
 *            android:foregroundServiceType="mediaPlayback"
 *            android:exported="false" />
 */
class MediaPlaybackService : MediaSessionService() {

    private lateinit var mediaSession: MediaSession
    private lateinit var player: ExoPlayer

    override fun onCreate() {
        super.onCreate()
        player = ExoPlayer.Builder(this).build()
        mediaSession = MediaSession.Builder(this, player).build()
    }

    override fun onGetSession(controllerInfo: MediaSession.ControllerInfo): MediaSession = mediaSession

    override fun onDestroy() {
        mediaSession.run {
            player.release()
            release()
        }
        super.onDestroy()
    }

    fun currentSession(): MediaSession = mediaSession
}

/**
 * JS の navigator.mediaSession をRhinoにバインドする層。
 * setActionHandler で登録されたJS関数はここに保持し、
 * OS側のメディアボタンイベントが来たタイミングで呼び出す。
 */
class JsNavigatorMediaSession(
    private val mediaSession: MediaSession,
) : ScriptableObject() {

    private val actionHandlers = mutableMapOf<String, Function>()

    override fun getClassName(): String = "MediaSession"

    fun jsFunction_setActionHandler(action: String, handler: Function?) {
        if (handler == null) {
            actionHandlers.remove(action)
            return
        }
        actionHandlers[action] = handler

        // play/pauseはExoPlayer側の状態にも直接反映しておく
        when (action) {
            "play" -> mediaSession.player.playWhenReady = true
            "pause" -> mediaSession.player.playWhenReady = false
        }
    }

    fun jsSet_metadata(metadata: JsMediaMetadata) {
        val exoMetadata = MediaMetadata.Builder()
            .setTitle(metadata.title)
            .setArtist(metadata.artist)
            .setArtworkUri(metadata.artworkUri?.let { android.net.Uri.parse(it) })
            .build()

        // Player.mediaMetadataは読み取り専用のため、現在のMediaItemを
        // 新しいメタデータで作り直して差し替える。
        val player = mediaSession.player
        val currentItem = player.currentMediaItem ?: return
        val updatedItem = currentItem.buildUpon().setMediaMetadata(exoMetadata).build()
        val currentIndex = player.currentMediaItemIndex
        player.replaceMediaItem(currentIndex, updatedItem)
    }

    /** OS側のメディアボタン(Bluetooth含む)経由でアクションが飛んできた時に呼ぶ */
    fun dispatchAction(action: String, rhinoCtx: org.mozilla.javascript.Context) {
        val handler = actionHandlers[action] ?: return
        val scope = getTopLevelScope(this)
        handler.call(rhinoCtx, scope, this, emptyArray())
    }
}

data class JsMediaMetadata(
    val title: String = "",
    val artist: String = "",
    val artworkUri: String? = null,
)
