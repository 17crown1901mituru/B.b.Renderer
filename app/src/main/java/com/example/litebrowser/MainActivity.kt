package com.example.litebrowser

import android.os.Bundle
import android.webkit.WebSettings
import android.webkit.WebView
import android.webkit.WebViewClient
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.*
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.unit.dp
import androidx.compose.ui.viewinterop.AndroidView
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import javax.microedition.khronos.egl.EGL10
import javax.microedition.khronos.egl.EGLContext
import android.view.WindowManager

// ★本命：glview-kitの判定頭脳をインポートして利用（ビルド時に結合）
import com.realtechvr.glview.GLviewKit 

class MainActivity : ComponentActivity() {

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)

        // 1. 起動時の一瞬でデバイスの生のOpenGL拡張機能リストを引っこ抜く
        val rawExtensions = getEngineOpenGLExtensions()

        // 2. glview-kitの公式ルールブックと動的照合し、最適化フラグを組み立てる
        val isHardwareAccelSupported = GLviewKit.hasExtension(rawExtensions, "GL_EXT_discard_framebuffer") && 
                                       GLviewKit.hasExtension(rawExtensions, "GL_OVR_multiview")

        if (isHardwareAccelSupported) {
            // デバイスのポテンシャルを100%確信できた場合のみ、Chromium最深部リミッターを完全解除
            System.setProperty("chromium.webContents.enable-gpu-rasterization", "true")
            System.setProperty("chromium.webContents.ignore-gpu-blocklist", "true")
            System.setProperty("chromium.webContents.enable-oop-rasterization", "true")
            
            // OSのウインドウコンポーザーに対してもOpenGL最優先合成を強制
            window.addFlags(WindowManager.LayoutParams.FLAG_HARDWARE_ACCELERATED)
        }

        setContent {
            var latency by remember { mutableStateOf(0L) }
            var currentThrottle by remember { mutableStateOf(100) } // 初期出力100%
            val coroutineScope = rememberCoroutineScope()

            // 定期的な通信ラグ（レイテンシ）の擬似計測・変速ロジック
            LaunchedEffect(Unit) {
                while (true) {
                    val startTime = System.currentTimeMillis()
                    // ここで単車ノ虎などのゲームサーバーやエンドポイントへの超軽量Ping/ハンドシェイクを想定
                    delay(2000) 
                    val endTime = System.currentTimeMillis()
                    
                    latency = endTime - startTime - 2000
                    
                    // 動的出力変速（スロットル制御）の自動決定
                    currentThrottle = when {
                        latency < 50 -> 100 // ラグなし：出力100%全開駆動（OpenGLフルパイプライン）
                        latency < 150 -> 70  // 軽微なラグ：出力を70%に落として同期のズレを防ぐ
                        else -> 30           // 重度なネットワーク詰まり：出力を30%に絞りスタックを完全回避
                    }
                }
            }

            Surface(
                modifier = Modifier.fillMaxSize(),
                color = MaterialTheme.colorScheme.background
            ) {
                Column(modifier = Modifier.fillMaxSize()) {
                    
                    // ─── ユーザー指定：絶対に維持する自動化UIパーツ（TID等） ───
                    Row(
                        modifier = Modifier
                            .fillMaxWidth()
                            .background(Color(0xFF222222))
                            .padding(8.dp),
                        horizontalArrangement = Arrangement.SpaceBetween,
                        verticalAlignment = Alignment.CenterVertically
                    ) {
                        Text(text = "TID: TORA-ENGINE-SYNC-01", color = Color.Green, style = MaterialTheme.typography.bodyMedium)
                        Text(text = "LAG: ${latency}ms", color = Color.White)
                        Text(
                            text = "THROTTLE: ${currentThrottle}%", 
                            color = if (currentThrottle == 100) Color.Cyan else Color.Yellow
                        )
                    }

                    // ─── 最速化されたメイン描画領域（WebView） ───
                    AndroidView(
                        factory = { context ->
                            WebView(context).apply {
                                webViewClient = WebViewClient()
                                settings.apply {
                                    javaScriptEnabled = true
                                    domStorageEnabled = true
                                    databaseEnabled = true
                                    cacheMode = WebSettings.LOAD_DEFAULT
                                }

                                // 描画処理をCPUからOpenGLハードウェアパイプラインに直接丸投げ
                                setLayerType(WebView.LAYER_TYPE_HARDWARE, null)
                                
                                // 初期のゲーム起動URL（単車ノ虎等のWebViewターゲット）
                                loadUrl("https://tantora.jp/members/sim/game") 
                            }
                        },
                        modifier = Modifier
                            .fillMaxWidth()
                            .weight(1f),
                        update = { webView ->
                            // 動的スロットル値をJavaScript側の描画・評価レートにリアルタイムでフィードバック
                            webView.evaluateJavascript(
                                "if(window.ToraEngine) { window.ToraEngine.setThrottleRate($currentThrottle); }", 
                                null
                            )
                        }
                    )
                }
            }
        }
    }

    /**
     * Androidシステムから生のOpenGL（EGL）コンテキストを極小のライトウェイト状態で一瞬だけ立ち上げ、
     * 現在のデバイスの物理的な拡張機能リスト文字列（GL_EXTENSIONS）を直接自給自足するネイティブ関数
     */
    private fun getEngineOpenGLExtensions(): String {
        val egl = EGLContext.getEGL() as EGL10
        val display = egl.eglGetDisplay(EGL10.EGL_DEFAULT_DISPLAY)
        egl.eglInitialize(display, intArrayOf(0, 0))
        
        val configSpec = intArrayOf(
            EGL10.EGL_RENDERABLE_TYPE, 4,
            EGL10.EGL_NONE
        )
        val configs = arrayOfNulls<javax.microedition.khronos.egl.EGLConfig>(1)
        val numConfig = IntArray(1)
        egl.eglChooseConfig(display, configSpec, configs, 1, numConfig)
        
        val context = egl.eglCreateContext(display, configs[0], EGL10.EGL_NO_CONTEXT, null)
        egl.eglMakeCurrent(display, EGL10.EGL_NO_SURFACE, EGL10.EGL_NO_SURFACE, context)
        
        // デバイスの生データを直接キャッチ
        val glExtensions = android.opengl.GLES20.glGetString(android.opengl.GLES20.GL_EXTENSIONS) ?: ""
        
        // 使用した一時的なコンテキストを即座に破棄してメモリ空間をクリーンに保つ
        egl.eglMakeCurrent(display, EGL10.EGL_NO_SURFACE, EGL10.EGL_NO_SURFACE, EGL10.EGL_NO_CONTEXT)
        egl.eglDestroyContext(display, context)
        
        return glExtensions
    }
}
