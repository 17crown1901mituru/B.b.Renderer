# B.b.Renderer

Google WebViewに依存しない、独自DOM/CSS/レイアウト/HTMX連携エンジン。

## ビルド方針(重要)

**このプロジェクトはNDK/C++/CMakeを一切使用しません。純Java/Kotlinのみでビルドが完結します。**

- GPUレンダリングは`android.opengl.GLES30`など**Android SDKに標準搭載されているKotlin/Java API経由**で行う
- JSエンジンはRhino(純Java実装)を使う。V8やJavaScriptCoreのような、NDK経由でしか組み込めないエンジンは採用しない
- Vulkanのような、Android上で実質NDK必須になるAPIも採用しない

これは意図的な制約です。理由は主に2つあります。

1. **クロスコンパイルの複雑さを避けるため**：C++を混ぜるとABI別ビルド(`arm64-v8a`/`armeabi-v7a`/`x86_64`)やNDKバージョン管理がCIに乗り、ビルド時間・失敗要因が増える
2. **Rhino(JSエンジン)との連携のしやすさ**：DOM⇔JSのバインディングを全てKotlin/Java側で完結させたいため、ネイティブ層を挟むと呼び出しが複雑になる

**Pull RequestやAIツールでの自動生成コードにC++/CMake/NDK関連ファイルが含まれていた場合、それは方針から外れているので採用しないでください。** 過去に一度、GPU実装の中でVulkan/OpenGLのC++実装(JNI Bridge含む)が混入したことがあり、方針確認の上で除去した経緯があります。

## ビルド

```bash
# ローカル(Android SDK/JDK17が入っている環境)
gradle assembleDebug

# もしくはGitHub Actions (push時に自動実行、.github/workflows/build.ymlを参照)
```