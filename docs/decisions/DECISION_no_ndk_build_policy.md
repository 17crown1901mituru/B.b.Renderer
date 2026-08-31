# DECISION: NDK/C++/CMakeを一切使用しない

対象: プロジェクト全体のビルド方針
移設元: 旧README.md「ビルド方針(重要)」、`ProjectFeature.md`

## 決定事項

**このプロジェクトはNDK/C++/CMakeを一切使用しない。純Java/Kotlinのみでビルドが完結する。**

- GPUレンダリングは`android.opengl.GLES30`など**Android SDKに標準搭載されているKotlin/Java API経由**で行う
- JSエンジンはRhino(純Java実装)を使う。V8やJavaScriptCoreのような、NDK経由でしか組み込めないエンジンは採用しない
- Vulkanのような、Android上で実質NDK必須になるAPIも採用しない

## 理由

1. **クロスコンパイルの複雑さを避けるため**: C++を混ぜるとABI別ビルド(`arm64-v8a`/`armeabi-v7a`/`x86_64`)やNDKバージョン管理がCIに乗り、ビルド時間・失敗要因が増える
2. **Rhino(JSエンジン)との連携のしやすさ**: DOM⇔JSのバインディングを全てKotlin/Java側で完結させたいため、ネイティブ層を挟むと呼び出しが複雑になる

## 過去の経緯

過去に一度、GPU実装の中でVulkan/OpenGLのC++実装(JNI Bridge含む)が混入したことがあり、
方針確認の上で除去した。

## この決定が意味すること(README側のルールとの対応)

Pull RequestやAIツールでの自動生成コードにC++/CMake/NDK関連ファイルが含まれていた場合、
それは本方針から外れているため採用しない。README.md側にはこの結論(ルール)だけを
簡潔に記載し、理由の詳細はこのファイルを参照する形にしている。
