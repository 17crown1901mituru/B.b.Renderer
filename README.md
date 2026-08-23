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
├── core/       DOM基底 (Node, Element, HtmlFragmentParser)
├── style/      CSS (ComputedStyle, CssParser, StyleResolver)
├── layout/     Box model計算 (LayoutEngine)
├── htmx/       HTMX連携・差分検知 (HtmxRenderEngine, SeqReconciler, StabilityTracking)
├── input/      ヒットテスト・タッチ入力 (InputHandling)
├── media/      video/audio再生 (JsMediaElement, MediaPlaybackService等)
├── render/     描画バックエンド選択 (EngineHostView, RendererFactory)
│   ├── CanvasRenderer.kt / EngineView.kt   software Canvasフォールバック(MINIMAL Tier用)
│   └── gpu/    GPUレンダリング本体(GLES30, NDK不使用)
│       ├── GpuCapabilityDetector.kt   拡張機能検出・Tier判定
│       ├── QuadBatchRenderer.kt        背景色矩形のバッチ描画
│       ├── TextTextureCache.kt         テキストのラスタライズ+テクスチャキャッシュ
│       ├── TexturedQuadRenderer.kt     テキストテクスチャの描画
│       ├── GLEngineRenderer.kt         GLSurfaceView.Renderer本体
│       └── GLEngineView.kt             GPU版WebView代替View
└── EngineActivity.kt   エントリポイント(RendererFactory経由でCanvas/GPUを自動選択)
```

## 現状のステータス

- DOM構築・CSS解決・box model計算・HTMX差分検知・タッチ入力・メディア再生は実装済み
- **GPUレンダリング実装済み**：`GpuCapabilityDetector`でTier判定し、MINIMAL判定時のみCanvas版にフォールバック。それ以外はGLES30による矩形バッチ描画＋テキストテクスチャ描画
- **インラインフロー・`<a>`タグ実装済み**(2026-08)：`display:inline`要素とテキストが混在する内容(`<p>text <a>link</a> more</p>`等)を1つの折り返し塊として扱い、実ブラウザ同様「文中リンク」として描画・タップ判定できる。`<a>`のデフォルトスタイル(青字+下線)・タップ時のnavigate処理も対応済み。ただし`<a>`の中にさらに`<b>`等のインライン要素を入れる二重入れ子は非対応(`LayoutEngine.layoutInlineRun`のコメント参照)
- **Flexboxレイアウト実装済み**(2026-08)：`flex-direction`(row/column)・`justify-content`・`align-items`・`flex-grow`/`flex-shrink`/`flex-basis`・`gap`に対応。`flex-wrap`は非対応(常にnowrap相当)、`flex-basis:auto`かつ`width/height:auto`な項目はコンテンツ量に応じた自動サイズ計算(shrink-to-fit)ができないため基準サイズ0扱い(`flex-grow`指定との組み合わせが前提。詳細は`LayoutEngine.layoutFlexRow`/`layoutFlexColumn`のコメント参照)
- **margin/paddingの%指定に対応済み**(2026-08)：CSS仕様通り、top/bottom/left/right全てcontaining blockの"幅"を基準に解決する
- **ドロワーの「📁開く」ボタンでローカルファイルを選択可能**(2026-08)：SAF(システム標準のファイルピッカー)を呼び出し、`content://` URIとして読み込む。スコープドストレージの制限を受けず、公開ストレージのどこにあるファイルでも権限操作無しで選べる。`file://`の手打ちより基本的にこちらを使えばよい
- **file://によるローカルHTML/画像の直接読み込みにも対応済み**(2026-08)：上記ボタンが使えない場面(例: URL欄に直接打ちたい、決まったテストファイルを毎回同じURLで開きたい)向けのフォールバック。下記「ローカルファイルでの動作確認」参照
- SVG画像は非対応(`android.graphics.BitmapFactory`はラスター画像(PNG/JPEG/WEBP等)専用で、XMLベースのSVGはデコードできない。読み込み自体は試みるが`ImageLoadState.FAILED`になり、クラッシュはしない)
- **JSエンジン(Rhino)の組み込み・DOM/JSバインディングは未着手**（依存関係のみ`build.gradle.kts`に追加済み）
- 外部`<link rel="stylesheet">`の取得は未対応(`<style>`インラインのみ)
- コンパイル未検証の範囲について：`<a>`タグ実装(インラインフロー導入前の版)までは実機でのビルド・動作を確認済み。Flexbox・margin/padding%・現行のインラインフロー実装・ローカルファイルピッカーは**まだ実機での動作確認前**のため、pushでのCI結果・実機ログ(`RENDER_DIAG`等)を都度確認してください

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

- Rhino 1.7.x系は`Proxy`/`Reflect`未対応（1.7.15で確認、htmx 2.0.10が動かない）
- **Rhino 1.8.0で`Proxy`/`Reflect`が追加され、1.9.1まで引き継がれています**(ソースの`NativeProxy.java`で確認済み)
- そのため`build.gradle.kts`は`org.mozilla:rhino:1.9.1`を使用しています(1.7.15からの変更)
- Rhino 1.8.0以降は実行時にJava 11以上(推奨17/21)を要求します。Android/ART上での動作実績は1.7.x系ほど長くありませんが、Rhino公式リポジトリに`it-android`というAndroid統合テスト専用モジュールが存在しており、Android対応は公式にケアされている領域だと判断しました
- 初回CIビルドで型/dex変換エラーが出ないか確認してください

### 実装済みのDOMシム

- `XMLHttpRequest`(`JsXMLHttpRequest.kt`、OkHttp裏付け、Rhinoのdefineclass経由で`new`可能)
- `CustomEvent`(`JsCustomEventHost.kt`) / `document.createEvent('CustomEvent')`+`initCustomEvent()`フォールバック(`JsDocument.kt`/`JsEvent.kt`)の両対応
- `element.closest()`/`matches()`(`JsElement.kt`、`style.CssSelectorEngine`を再利用)
- `element.dispatchEvent()`によるバブリング(`JsElement.kt`内で自前実装、Engine側`Element.dispatchEvent`とは別軸)
- `requestAnimationFrame`/`history`(no-opスタブ)/`location`(`JsWindow.kt`)

### 未実装・既知の制約

- `MutationObserver`は実装していません。代わりにJS側の`innerHTML`代入後、自動で`htmx.process(element)`を呼ぶ手動フック(`JsDomContext.onDomMutated`)で代替しています
- `hx-boost`によるAndroidバックスタックとの統合は未実装(`history`はno-opスタブ)
- `element.dataset`は未実装
- `HtmxSeqOptimizer`(`HtmxSeqOptimizer.kt`)は`htmx:beforeSwap`/`afterSwap`イベントに自動でフックされ(`JsEngine.loadHtmx()`内でbootstrapスクリプトを自動注入)、変化のなかった要素のGPU描画コマンドキャッシュを引き継ぎます

## ES6構文サポート(任意)

`JsEngine.enableEs6Support(babelJsStream)`を呼ぶと、ページ側の`<script>`がES6+構文（`let`/`const`/アロー関数等）でもBabel経由でES5変換してから実行できます。呼ばなければES5前提で直接実行されます(未対応構文は構文エラー)。

- `Es6RhinoRunner.java`(js/パッケージ)は、Rhino単体でBabelを動かしてES6→ES5変換する橋渡しクラス
- 必要な`babel.min.js`(MIT License)は同梱していません。`app/src/main/assets/libs/babel.min.js`に配置してから`enableEs6Support`を呼ぶ想定です

## 既知の要修正ポイント

- `EngineActivity`の`initialUrl`は仮のプレースホルダー、Intent/設定からの受け取りに差し替える必要あり
- `HtmxRenderEngine`内のノード単位dirty判定で、`ActionSignature`の空インスタンスを間に合わせで使っている箇所がある（次回リファクタ対象）
- `<video>`/`<audio>`のGPU直結(`SurfaceTexture`経由)はGPUレンダリングパイプラインとまだ接続していない
- テキストは「1つの折り返し塊(inline run)につき1テクスチャ」方式(2026-08、インラインフロー導入で「1要素1テクスチャ」から変更。`<a>`混在の段落等、同じ折り返し内の複数要素が1テクスチャを共有するようになった)。それでも段落数が多いページではdrawCall数が増える(将来的な最適化余地)
- `network_security_config.xml`は`base-config cleartextTrafficPermitted="true"`にしてあります(2026-08、`https://`→`http://`へのリダイレクト先で「CLEARTEXT communication ... not permitted」となる不具合の修正)。**汎用ブラウザとしての意図的な設定**です——Chrome/Firefox等の一般ブラウザアプリも同様に平文通信を許可しており、危険性の警告はブラウザのUI側の役割としています。ここを`false`に戻すと、httpサイトが軒並み開けなくなるので注意してください
