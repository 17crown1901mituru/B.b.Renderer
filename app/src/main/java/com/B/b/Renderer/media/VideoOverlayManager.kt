package com.B.b.Renderer.media

import android.app.Activity
import android.content.res.Configuration
import android.util.Rational
import android.widget.FrameLayout
import androidx.annotation.OptIn
import androidx.core.view.WindowInsetsCompat
import androidx.core.view.WindowInsetsControllerCompat
import androidx.media3.common.util.UnstableApi
import androidx.media3.exoplayer.ExoPlayer
import androidx.media3.ui.PlayerView
import android.app.PictureInPictureParams

/**
 * <video controls> のネイティブUI、フルスクリーン、PiPをまとめて扱う。
 * GPU描画パイプラインとは別の Android View レイヤーとして、
 * レイアウトエンジンが確定した矩形に毎フレーム座標だけ同期する。
 */
@OptIn(UnstableApi::class)
class VideoOverlayManager(
    private val activity: Activity,
    private val rootContainer: FrameLayout,
) {
    private val overlays = mutableMapOf<JsMediaElement, PlayerView>()

    fun attachControls(element: JsMediaElement, exoPlayer: ExoPlayer, useControls: Boolean) {
        val playerView = overlays.getOrPut(element) {
            PlayerView(activity).also { rootContainer.addView(it) }
        }
        playerView.player = exoPlayer
        playerView.useController = useControls
    }

    fun detach(element: JsMediaElement) {
        overlays.remove(element)?.let { rootContainer.removeView(it) }
    }

    /** DOM変更・スクロール・リサイズのたびにレイアウトエンジン側から呼ぶ */
    fun syncPosition(element: JsMediaElement, rect: LayoutRect) {
        val overlay = overlays[element] ?: return
        overlay.layoutParams = FrameLayout.LayoutParams(rect.width, rect.height).apply {
            leftMargin = rect.x
            topMargin = rect.y
        }
    }

    // ---- フルスクリーン ----

    private var fullscreenTarget: JsMediaElement? = null
    private var savedRect: LayoutRect? = null

    fun requestFullscreen(element: JsMediaElement, currentRect: LayoutRect, onChange: () -> Unit) {
        fullscreenTarget = element
        savedRect = currentRect

        val controller = WindowInsetsControllerCompat(activity.window, activity.window.decorView)
        controller.hide(WindowInsetsCompat.Type.systemBars())
        controller.systemBarsBehavior =
            WindowInsetsControllerCompat.BEHAVIOR_SHOW_TRANSIENT_BARS_BY_SWIPE

        val overlay = overlays[element]
        overlay?.layoutParams = FrameLayout.LayoutParams(
            FrameLayout.LayoutParams.MATCH_PARENT,
            FrameLayout.LayoutParams.MATCH_PARENT,
        )
        onChange()
    }

    fun exitFullscreen(onChange: () -> Unit) {
        val element = fullscreenTarget ?: return
        val rect = savedRect ?: return

        WindowInsetsControllerCompat(activity.window, activity.window.decorView)
            .show(WindowInsetsCompat.Type.systemBars())

        syncPosition(element, rect)
        fullscreenTarget = null
        savedRect = null
        onChange()
    }

    // ---- Picture-in-Picture ----

    fun requestPictureInPicture(intrinsicWidth: Int, intrinsicHeight: Int) {
        if (intrinsicWidth <= 0 || intrinsicHeight <= 0) return
        val params = PictureInPictureParams.Builder()
            .setAspectRatio(Rational(intrinsicWidth, intrinsicHeight))
            .build()
        activity.enterPictureInPictureMode(params)
    }

    fun isInPictureInPictureMode(newConfig: Configuration): Boolean {
        return activity.isInPictureInPictureMode
    }

    // ---- 非対応時のプレースホルダー ----

    /**
     * GPU描画側は通常のUIボックス描画パスに乗せるだけでよく、
     * 映像クアッド(GL_TEXTURE_EXTERNAL_OES)は使用しない。
     * ここでは描画対象データだけを返し、実描画はエンジンのGLレンダラに委ねる。
     */
    fun buildUnsupportedPlaceholder(rect: LayoutRect): UnsupportedPlaceholder {
        return UnsupportedPlaceholder(
            rect = rect,
            message = "この形式は再生できません",
            tapAction = TapAction.OPEN_EXTERNAL_CHOOSER,
        )
    }
}

data class UnsupportedPlaceholder(
    val rect: LayoutRect,
    val message: String,
    val tapAction: TapAction,
)

enum class TapAction { OPEN_EXTERNAL_CHOOSER }
