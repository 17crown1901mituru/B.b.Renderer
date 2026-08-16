package com.B.b.Renderer.render.gpu

import android.opengl.EGL14
import android.opengl.EGLConfig
import android.opengl.EGLContext
import android.opengl.EGLDisplay
import android.opengl.EGLSurface
import android.opengl.GLES30
import android.util.Log

data class GpuCapabilities(
    val vendor: String,
    val renderer: String,
    val versionString: String,
    val extensions: Set<String>,
    val maxTextureSize: Int,
) {
    fun has(ext: String): Boolean = ext in extensions
}

enum class RenderTier { MINIMAL, STANDARD, OPTIMIZED, MAX }

/**
 * オフスクリーンのPBufferでEGLコンテキストを一瞬だけ立ち上げ、
 * GPU情報を取得してから破棄する。GLSurfaceView本体には影響しない。
 */
object GpuCapabilityDetector {

    fun detect(): GpuCapabilities? {
        var display: EGLDisplay? = null
        var context: EGLContext? = null
        var surface: EGLSurface? = null

        try {
            display = EGL14.eglGetDisplay(EGL14.EGL_DEFAULT_DISPLAY)
            if (display == EGL14.EGL_NO_DISPLAY) {
                Log.w(TAG, "EGL display unavailable")
                return null
            }

            val versionOut = IntArray(2)
            if (!EGL14.eglInitialize(display, versionOut, 0, versionOut, 1)) {
                Log.w(TAG, "EGL initialize failed")
                return null
            }

            val configAttribs = intArrayOf(
                EGL14.EGL_RENDERABLE_TYPE, EGL_OPENGL_ES3_BIT,
                EGL14.EGL_SURFACE_TYPE, EGL14.EGL_PBUFFER_BIT,
                EGL14.EGL_RED_SIZE, 8,
                EGL14.EGL_GREEN_SIZE, 8,
                EGL14.EGL_BLUE_SIZE, 8,
                EGL14.EGL_NONE,
            )
            val configs = arrayOfNulls<EGLConfig>(1)
            val numConfigs = IntArray(1)
            if (!EGL14.eglChooseConfig(display, configAttribs, 0, configs, 0, 1, numConfigs, 0) ||
                numConfigs[0] == 0
            ) {
                Log.w(TAG, "EGL choose config failed")
                return null
            }

            val contextAttribs = intArrayOf(EGL14.EGL_CONTEXT_CLIENT_VERSION, 3, EGL14.EGL_NONE)
            val chosenConfig = configs[0] ?: run {
                Log.w(TAG, "No EGL config chosen")
                return null
            }
            context = EGL14.eglCreateContext(display, chosenConfig, EGL14.EGL_NO_CONTEXT, contextAttribs, 0)
            if (context == EGL14.EGL_NO_CONTEXT) {
                Log.w(TAG, "EGL context creation failed")
                return null
            }

            val pbufferAttribs = intArrayOf(EGL14.EGL_WIDTH, 1, EGL14.EGL_HEIGHT, 1, EGL14.EGL_NONE)
            surface = EGL14.eglCreatePbufferSurface(display, chosenConfig, pbufferAttribs, 0)
            if (surface == EGL14.EGL_NO_SURFACE) {
                Log.w(TAG, "EGL pbuffer surface creation failed")
                return null
            }

            if (!EGL14.eglMakeCurrent(display, surface, surface, context)) {
                Log.w(TAG, "EGL makeCurrent failed")
                return null
            }

            val vendor = GLES30.glGetString(GLES30.GL_VENDOR) ?: ""
            val renderer = GLES30.glGetString(GLES30.GL_RENDERER) ?: ""
            val version = GLES30.glGetString(GLES30.GL_VERSION) ?: ""
            val extensionsRaw = GLES30.glGetString(GLES30.GL_EXTENSIONS) ?: ""
            val extensions = extensionsRaw.split(" ").filter { it.isNotBlank() }.toSet()

            val maxTexSize = IntArray(1)
            GLES30.glGetIntegerv(GLES30.GL_MAX_TEXTURE_SIZE, maxTexSize, 0)

            return GpuCapabilities(
                vendor = vendor,
                renderer = renderer,
                versionString = version,
                extensions = extensions,
                maxTextureSize = maxTexSize[0],
            )
        } catch (e: Exception) {
            Log.w(TAG, "GPU capability detection failed: ${e.message}")
            return null
        } finally {
            try {
                if (display != null) {
                    EGL14.eglMakeCurrent(display, EGL14.EGL_NO_SURFACE, EGL14.EGL_NO_SURFACE, EGL14.EGL_NO_CONTEXT)
                    if (surface != null) EGL14.eglDestroySurface(display, surface)
                    if (context != null) EGL14.eglDestroyContext(display, context)
                    EGL14.eglTerminate(display)
                }
            } catch (e: Exception) {
                Log.w(TAG, "EGL cleanup error: ${e.message}")
            }
        }
    }

    /**
     * 拡張の"組み合わせ"が揃って初めて上位Tierへ昇格させる。
     * 単一拡張の有無だけでは判定しない(中途半端な対応端末の誤爆を避けるため)。
     */
    fun classifyTier(caps: GpuCapabilities?): RenderTier {
        if (caps == null) return RenderTier.MINIMAL
        val glesVersion = parseGlesVersion(caps.versionString)

        return when {
            glesVersion >= 3.1f &&
                caps.has("GL_OES_vertex_array_object") &&
                caps.has("GL_EXT_multi_draw_arrays") -> RenderTier.MAX

            glesVersion >= 3.0f && caps.has("GL_OES_vertex_array_object") -> RenderTier.OPTIMIZED

            glesVersion >= 3.0f -> RenderTier.STANDARD

            else -> RenderTier.MINIMAL
        }
    }

    private fun parseGlesVersion(versionString: String): Float {
        // 例: "OpenGL ES 3.2 build ..." から "3.2" を抜き出す
        val match = Regex("(\\d+)\\.(\\d+)").find(versionString) ?: return 0f
        return "${match.groupValues[1]}.${match.groupValues[2]}".toFloatOrNull() ?: 0f
    }

    private const val EGL_OPENGL_ES3_BIT = 0x0040
    private const val TAG = "GpuCapabilityDetector"
}
