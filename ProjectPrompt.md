# プロジェクト概要 & アーキテクチャ方針(他AIセッションへの携帯用コンテキスト)

このファイルは、このプロジェクトについて他のAI/セッションへそのままコピペして渡すための
自己完結した文脈です。分割せず単一ファイルのまま使ってください。より詳しい情報が要る場合は
`PROJECT_MAP.md`(このリポジトリの道しるべ)→`README.md`(今すぐ従うべきルール・手順)→
`docs/decisions/`(なぜそう決めたか)→`docs/status/`(今どうなっているか)の順で参照してください。

## これは何か

**B.b.Renderer**は、Google WebViewに依存しない、Android用の独自ブラウザエンジンです。
DOM・CSS・レイアウト・GPU描画・JavaScriptエンジン(Rhino)・HTMX連携までを、Kotlin/Javaで
自前実装しています。サーバーサイドのWebアプリケーションではなく、**実在のWebサイトを
Android端末上でレンダリングするクライアント**である点に注意してください
(「HTMLフラグメントをレスポンスとして返す」ような、このアプリ自身がサーバーになる構成では
ありません。htmxはこのエンジンが読み込んだページ側のJSとして動くもので、通信先は実際の
Webサーバーです)。

## 基本方針 & 開発スタンス

1. **NDK/C++/CMakeを一切使用しない**: GPU描画は`android.opengl.GLES30`等のAndroid SDK標準
   Kotlin/Java APIのみで行い、JSエンジンはNDK不要な純Java実装のRhinoを使う。V8/JavaScriptCore/
   Vulkan等、NDK経由が前提になるものは採用しない(理由・過去の経緯は
   `docs/decisions/DECISION_no_ndk_build_policy.md`参照)。
2. **モジュール・ライブラリの厳選(引き算の設計)**: 新たな外部ライブラリの導入は慎重に検討し、
   言語(Kotlin)の標準機能および軽量な自前実装を優先する。不要な抽象化レイヤーを増やさず、
   コードの透過性と構造のシンプルさを重視する。
3. **Rhinoは同一プロセス内で動かす**: Node.js等の外部プロセスとしてではなく、JVM(Android
   ART)の同一メモリ上で直接JSを評価・実行する。プロセス間通信は発生しない。

## 技術スタック

- Kotlin(compileSdk/targetSdk 35, minSdk 26, JVM target 17)
- JSエンジン: Rhino 1.9.1(Proxy/Reflect対応、htmx 2.0.10の要求で1.8.0以降が必須)
- HTMLパース: Jsoup / HTTP通信: OkHttp / メディア再生: AndroidX Media3(ExoPlayer)
- ページ側フロントエンド連携: htmx.org 2.0.10(必ず2.x系、4.x系は`fetch()`前提で非対応)

## 実装済みの主な機能領域

DOM/CSS/Flexboxレイアウト、GPU/Canvas自動切替描画、Rhino JSエンジン(DOM操作・XHR・
CustomEvent・clipboard等のシム一式)、`.rjs`によるアプリ内ショートカット(マクロ)実行、
マルチタブ・ピン留め・PiP・発熱対策、video/audio再生、ドメイン単位の権限モデル、
ブックマーク/履歴/コピー履歴、画面長押しテキスト選択。詳細と現在の対応状況は
`README.md`および`docs/status/todo_code_comments_triage.md`参照。

## Rhinoのセキュリティ境界(必ず守ること)

Webページ由来の任意のJSが実行される都合上、Rhino標準の`Packages`/`java`等のグローバル
(任意のJavaクラスへの到達経路)は`js/RhinoSandbox.kt`で塞いである。**新しくRhino
Contextを生成する箇所を追加する場合は、必ず`RhinoSandbox.ensureClassShutterInstalled()`と
`RhinoSandbox.stripJavaGlobals(scope)`を`initStandardObjects()`直後に呼ぶこと。**
新しいJava機能をページ側JSに公開する場合も、`JsWindow`/`ShortcutApi`のようなホワイトリスト
方式(Kotlinオブジェクトを明示的に注入)以外の方法は取らないこと。

## EngineActivityを触る場合

`EngineActivity.kt`は機能追加のたびに肥大化する「God Activity」化を避けるため、
`features/EngineFeature.kt`のFeatureパターンへの移行を進めている。新しい機能
(コールバック配線を伴うもの)を追加する場合は、`EngineFeature`を実装した1クラスとして書き、
必要な配線はそのクラスの`onCreate()`/`onSessionAttached()`内で完結させること。
`EngineActivity.kt`本体への変更は`features`リストへの追加程度に留める。詳細・実装済みの例
(`ClipboardFeature`)は`docs/decisions/DECISION_engine_feature.md`参照。

## AIへの回答指示

- 「ライブラリを追加して解決する」提案ではなく、Kotlin標準機能や最小限の自前実装で解決する
  コード例を提示すること。
- ビルド環境を持たないセッションから作業する場合、構文/型のミスがその場で検証できない前提で
  慎重にコードを書き、変更点(何を・なぜ変えたか)を明記すること。
- 出力の際は修正箇所の差分ではなく、変更したファイルの全文を出力すること。ファイル数が多い
  場合はzipにまとめて出力すること。
