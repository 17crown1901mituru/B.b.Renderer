# B.b.Renderer

Google WebViewに依存しない、独自DOM/CSS/レイアウト/HTMX連携エンジン。

設計の経緯・理由を知りたい場合は`docs/decisions/`、現在の実装状況・未対応項目を
知りたい場合は`docs/status/`を参照(詳しくはリポジトリ直下の`PROJECT_MAP.md`)。
このREADMEには「今すぐ従うべきルール」「今すぐ実行できる手順」のみを記載する。

## ビルド方針(重要)

**このプロジェクトはNDK/C++/CMakeを一切使用しない。純Java/Kotlinのみでビルドが完結する。**

- GPUレンダリングは`android.opengl.GLES30`など**Android SDKに標準搭載されているKotlin/Java API経由**で行う
- JSエンジンはRhino(純Java実装)を使う。V8やJavaScriptCoreのような、NDK経由でしか組み込めないエンジンは採用しない
- Vulkanのような、Android上で実質NDK必須になるAPIも採用しない

**Pull RequestやAIツールでの自動生成コードにC++/CMake/NDK関連ファイルが含まれていた場合、それは方針から外れているので採用しないでください。** 理由・過去の経緯は`docs/decisions/DECISION_no_ndk_build_policy.md`参照。

## ビルド

```bash
# ローカル(Android SDK/JDK17が入っている環境)
gradle assembleDebug

# もしくはGitHub Actions (push時に自動実行、.github/workflows/build.ymlを参照)
```

### Gradle Wrapperについて

`gradlew` / `gradlew.bat` / `gradle-wrapper.properties` は同梱していますが、
**`gradle/wrapper/gradle-wrapper.jar`(バイナリ)は含まれていません**。
このリポジトリを作った環境にGradle/ネットワークがなく、バイナリを生成できなかったためです。

`build.yml`(通常のビルド)は`gradle/actions/setup-gradle`でGradle本体を都度用意する方式にしているため、
**jarが無くてもCIビルド自体は動きます**。`./gradlew`をローカルで使いたい場合のみ、以下の手順でjarを補ってください。

1. Actionsタブ → `Generate Gradle Wrapper Jar` → `Run workflow` を手動実行
2. 完了後のartifact`gradle-wrapper-jar`をダウンロード
3. 中身の`gradle-wrapper.jar`を`gradle/wrapper/`配下に配置してコミット
4. 以後`./gradlew`がローカルで使える。`generate-wrapper.yml`は不要になったら削除してよい

## 構成

```
app/src/main/java/com/B/b/Renderer/
├── core/         DOM基底 (Node, Element, HtmlFragmentParser)
├── style/        CSS (ComputedStyle, CssParser, StyleResolver)
├── layout/       Box model計算 (LayoutEngine)
├── htmx/         HTMX連携・差分検知 (HtmxRenderEngine, SeqReconciler, StabilityTracking)
├── input/        ヒットテスト・タッチ入力・テキスト選択 (InputHandling, TextSelectionGestureHelper)
├── js/           Rhino実行エンジン・DOMシム (JsEngine, JsWindow, JsDomContext, RhinoSandbox)
├── device/       .rjsショートカット実行 (DeviceScriptEngine, ShortcutApi, RjsShortcutScanner)
├── media/        video/audio再生 (JsMediaElement, MediaPlaybackService等)
├── network/      HTTP通信 (fetch/XHR裏付け)
├── data/         永続化 (HistoryStore, BookmarkStore, ClipboardHistoryStore, BrowserDatabase)
├── permissions/  ドメイン単位の権限管理 (SitePermissions, BrowserCapabilityBridge)
├── tabs/         タブ管理 (TabManager, TabBarView, TabKeepAliveService)
├── debug/        デバッグドロワー (DebugDrawerView, BehaviorAuditLog)
├── thermal/      発熱時のTier降格 (ThermalGuard)
├── benchmark/    起動時のGPU/Canvas判定 (RenderTierBenchmark)
├── util/         横断的な小ユーティリティ (ClipboardHelper)
├── render/       描画バックエンド選択 (EngineHostView, EngineFrameLayout, RendererFactory)
│   ├── CanvasRenderer.kt / EngineView.kt   software Canvasフォールバック(MINIMAL Tier用)
│   └── gpu/      GPUレンダリング本体(GLES30, NDK不使用)
│       ├── GpuCapabilityDetector.kt   拡張機能検出・Tier判定
│       ├── QuadBatchRenderer.kt        背景色矩形のバッチ描画
│       ├── TextTextureCache.kt         テキストのラスタライズ+テクスチャキャッシュ
│       ├── TexturedQuadRenderer.kt     テキストテクスチャの描画
│       ├── GLEngineRenderer.kt         GLSurfaceView.Renderer本体
│       └── GLEngineView.kt             GPU版WebView代替View
└── EngineActivity.kt   エントリポイント(RendererFactory経由でCanvas/GPUを自動選択)
```

## 実装済みの主な機能(使い方の要点のみ。詳細な対応状況は`docs/status/`参照)

- インラインフロー・`<a>`タグ、Flexboxレイアウト、margin/paddingの%指定
- ドロワーの「📁開く」ボタン、または`file://`直接入力によるローカルHTML確認(下記参照)
- GPUレンダリング(Tier判定で自動選択、`MINIMAL`判定時のみCanvas版にフォールバック)
- 画面長押しによるテキスト範囲選択・コピー、コピー履歴(ドロワー「クリップボード」タブ)
- `navigator.clipboard.writeText()`/`readText()`(サイト単位の許可制)

## ローカルファイルでの動作確認

### 方法1: ドロワーの「📁開く」ボタン(基本はこちら)

1. ドロワー(ハンバーガーメニュー)を開き、アドレスバー行にある「📁開く」ボタンをタップする
2. システム標準のファイルピッカーが開くので、確認したい`.html`ファイルを選ぶ(端末内のどこにあってもよい——Downloads配下でも、MT Managerで配置した場所でも問題ない)
3. 選択すると自動的にそのページへ遷移する

内部的にはSAF(Storage Access Framework)の`ACTION_OPEN_DOCUMENT`を使っており、ピッカーで明示的に選んだファイルへの読み取り権限がその場で付与される仕組みのため、Android 10以降のスコープドストレージの制限を受けない(`LocalFilePicker.kt`参照)。

既知の制約: `content://` URIはfile://と違い相対パス解決ができないため、この方法で開いたHTML内の相対パス画像参照(`<img src="images/x.png">`等)は解決に失敗する。絶対URL(`https://...`)の画像か、単体で完結したHTMLであれば問題ない。相対パス画像を含むテストページは方法2(file://)を使うこと。

### 方法2: file://を直接入力(相対パス画像を含むページなど)

1. 確認したい`.html`ファイル(および相対参照する画像等)を、**端末の以下のディレクトリ**に配置する(MT Managerで配置してOK):
   ```
   /storage/emulated/0/Android/data/com.B.b.Renderer/files/
   ```
   このディレクトリ(`Context.getExternalFilesDir(null)`が指す場所)は**アプリ専用領域なので、Android 10以降のスコープドストレージ制限を受けず、追加の権限操作無しで確実に読み書きできる**。存在しなければ作成してよい(アプリ起動後に自動生成されることもある)。
2. アプリのアドレスバーに次の形式で入力する:
   ```
   file:///storage/emulated/0/Android/data/com.B.b.Renderer/files/test.html
   ```
   (`file://` のあとに絶対パスをそのまま続ける。スキームの後ろが `///` と3連続スラッシュになる点に注意——`file://` + 絶対パス `/storage/...` を連結すると自然にそうなる)
3. HTML内から`<img src="images/photo.png">`のように相対パスで画像を参照している場合も、同じディレクトリ配下に置けば自動的に解決される。

**`/storage/emulated/0/Download`等の「一般の公開ストレージ」に置いたファイルを直接`file://`で開きたい場合**は、Android 10以降ではOS側のスコープドストレージ制限により読み込みに失敗することがある(`AndroidManifest.xml`の`READ_EXTERNAL_STORAGE`権限は`maxSdkVersion="32"`を付けており、API 33以降ではそもそも効果を持たない)。この場合は方法1(ドロワーの「📁開く」ボタン)を使うこと。

読み込みに失敗した場合はクラッシュせず、`RENDER_DIAG`ログに`local file read failed: ...`という形で理由(`FileNotFoundException`等)が記録される(ハンバーガーメニューのデバッグログパネルで確認可能)。

## htmx.js統合(任意)

htmx.js自体をRhino上で動かし、Kotlin側のseq最適化を外側から被せるハイブリッド構成に対応しています。

- `app/src/main/assets/libs/htmx.min.js` に**配置済み**(htmx.org 2.0.10、`EngineActivity`が自動読み込み)
- **必ずhtmx 2.x系を使ってください**(`https://cdn.jsdelivr.net/npm/htmx.org@2.0.10/dist/htmx.min.js` 等)。**4.x系は内部通信が`XMLHttpRequest`から`fetch()`に置き換わっており、このエンジンのDOMシムは`fetch`を実装していないため動きません**
- ライセンス: htmx.org は BSD 2-Clause License

### Rhinoバージョンについて(重要)

htmx 2.0.10はFormDataラッパー(`kn`関数)内で`new Proxy(...)`を使っており、これは**フォーム有無に関わらず全リクエストで無条件に実行されます**。`Proxy`はBabelでもポリフィル不可能な機能(構文変換ではなくJSエンジン自体のメタプログラミング機構が必要)なため、Rhino自体がネイティブでProxyをサポートしている必要があります。

- Rhino 1.7.x系は`Proxy`/`Reflect`未対応(1.7.15で確認、htmx 2.0.10が動かない)
- **Rhino 1.8.0で`Proxy`/`Reflect`が追加され、1.9.1まで引き継がれています**(ソースの`NativeProxy.java`で確認済み)
- そのため`build.gradle.kts`は`org.mozilla:rhino:1.9.1`を使用しています
- Rhino 1.8.0以降は実行時にJava 11以上(推奨17/21)を要求します
- 初回CIビルドで型/dex変換エラーが出ないか確認してください

### 実装済みのDOMシム

- `XMLHttpRequest`(`JsXMLHttpRequest.kt`、OkHttp裏付け、Rhinoのdefineclass経由で`new`可能)
- `CustomEvent`(`JsCustomEventHost.kt`) / `document.createEvent('CustomEvent')`+`initCustomEvent()`フォールバック(`JsDocument.kt`/`JsEvent.kt`)の両対応
- `element.closest()`/`matches()`(`JsElement.kt`、`style.CssSelectorEngine`を再利用)
- `element.dispatchEvent()`によるバブリング(`JsElement.kt`内で自前実装、Engine側`Element.dispatchEvent`とは別軸)
- `requestAnimationFrame`/`history`(no-opスタブ)/`location`(`JsWindow.kt`)
- `navigator.clipboard.writeText()`/`readText()`(`JsWindow.kt`のJsClipboard、`JsThenable`という簡易Promise代替を使用)
- `HtmxSeqOptimizer`(`HtmxSeqOptimizer.kt`)は`htmx:beforeSwap`/`afterSwap`イベントに自動でフックされ(`JsEngine.loadHtmx()`内でbootstrapスクリプトを自動注入)、変化のなかった要素のGPU描画コマンドキャッシュを引き継ぐ

未対応API・既知の制約の一覧は`docs/status/todo_code_comments_triage.md`参照。

## ES6構文サポート(任意)

`JsEngine.enableEs6Support(babelJsStream)`を呼ぶと、ページ側の`<script>`がES6+構文(`let`/`const`/アロー関数等)でもBabel経由でES5変換してから実行できます。呼ばなければES5前提で直接実行されます(未対応構文は構文エラー)。

- `Es6RhinoRunner.java`(js/パッケージ)は、Rhino単体でBabelを動かしてES6→ES5変換する橋渡しクラス
- 必要な`babel.min.js`(MIT License)は同梱していません。`app/src/main/assets/libs/babel.min.js`に配置してから`enableEs6Support`を呼ぶ想定です

## Rhinoのセキュリティ境界(重要)

Webページ由来の任意のJSが実行される都合上、Rhino自体が標準で持つ`Packages`/`java`等の
グローバル(任意のJavaクラスへの到達経路)は`RhinoSandbox.kt`で塞いである。新しく
Rhinoコンテキストを生成する箇所を追加する場合は、必ず`RhinoSandbox.ensureClassShutterInstalled()`
と`RhinoSandbox.stripJavaGlobals(scope)`を`initStandardObjects()`直後に呼ぶこと。
新しいJava機能をページ側JSに公開する場合も、`JsWindow`/`JsNavigator`のような
ホワイトリスト方式(Kotlinオブジェクトを明示的に注入)以外の方法は取らないこと。
