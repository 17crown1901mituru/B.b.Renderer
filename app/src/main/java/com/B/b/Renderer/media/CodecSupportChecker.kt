package com.B.b.Renderer.media

import android.media.MediaCodecList
import android.media.MediaFormat

/**
 * HTMLMediaElement.canPlayType() の実体。
 * 判定は一切ここで行わず、端末のMediaCodecListに問い合わせるだけの窓口。
 * ソフトウェアフォールバックの可否だけ簡易に見る。
 */
object CodecSupportChecker {

    /**
     * @return "probably" | "maybe" | ""（HTMLMediaElement仕様に合わせた3値）
     */
    fun query(mimeTypeWithCodecs: String): String {
        val mimeType = mimeTypeWithCodecs.substringBefore(";").trim()
        if (mimeType.isEmpty()) return ""

        val format = try {
            if (mimeType.startsWith("video/")) {
                MediaFormat.createVideoFormat(mimeType, 1280, 720)
            } else {
                MediaFormat.createAudioFormat(mimeType, 44100, 2)
            }
        } catch (e: IllegalArgumentException) {
            return ""
        }

        val codecList = MediaCodecList(MediaCodecList.REGULAR_CODECS)
        val decoderName = try {
            codecList.findDecoderForFormat(format)
        } catch (e: Exception) {
            null
        }

        return when {
            decoderName != null -> "probably"
            isKnownSoftwareFallbackAvailable(mimeType) -> "maybe"
            else -> ""
        }
    }

    /**
     * ExoPlayerのソフトウェア拡張(FFmpeg extension等)を導入している場合のみ true にする想定。
     * 未導入なら常にfalseで問題ない（"maybe"を返さずハードウェア対応のみで判定する）。
     */
    private fun isKnownSoftwareFallbackAvailable(mimeType: String): Boolean {
        return mimeType in setOf("audio/mpeg", "audio/ogg")
    }

    /** どのソースも再生不可だった場合のフォールバック対象を絞り込む */
    fun selectPlayableSource(candidates: List<MediaSourceCandidate>): MediaSourceCandidate? {
        return candidates.firstOrNull { query(it.mimeType) != "" }
    }
}

data class MediaSourceCandidate(
    val url: String,
    val mimeType: String,
)
