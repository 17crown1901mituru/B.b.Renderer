# B.b.Renderer
単車の虎向け、GPU直叩きによる爆速レンダリングエンジン。

## アーキテクチャ
- Native层: OpenGL ESによるGPUメモリ直書き
- JNI: Kotlin-C++間の高速通信ブリッジ
- Hook: WebView描画パイプラインのフックによる差分更新
