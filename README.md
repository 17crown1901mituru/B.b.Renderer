# B.b.Renderer

Google WebViewに依存しない、独自DOM/CSS/レイアウト/描画/JSエンジンを備えたAndroid用ブラウザエンジン。HTMX連携・マルチタブ・メディア再生・アプリ内ショートカット(マクロ)実行まで、WebView相当の機能を段階的に自前実装している。

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

- `compileSdk`/`targetSdk` = 35、`minSdk` = 26、JVM target 17
- `versionCode`はCI側で`BUILD_VERSION_CODE`(Unixエポック秒)を渡す運用。ローカルビルドでは固定値`1`にフォールバックする

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

## 主な依存ライブラリ

| 用途 | ライブラリ |
|---|---|
| HTMLパース(DOM構築の下ごしらえのみ) | Jsoup 1.17.2 |
| HTTP通信(fetch/XHR相当、HTMX連携) | OkHttp 4.12.0 |
| JSエンジン(content側・device側とも共通の1本) | Rhino 1.9.1 |
| メディア再生(video/audio) | AndroidX Media3 (ExoPlayer/Session/UI) 1.5.0 |
| 非同期処理 | Kotlin Coroutines 1.8.1 |

Rhinoは以前1.7.15を使用していましたが、`Proxy`/`Reflect`未対応でhtmx 2.0.10が動かないことが判明したため1.9.1へ変更しています(詳細は「htmx.js統合」節参照)。また、旧BeanShell(bsh)ベースのアプリ内ショートカットエンジンはRhinoへ一本化・置き換え済みです(`device/`パッケージ、後述)。

## 構成

```
app/src/main/java/com/B/b/Renderer/
├── core/         DOM基底 (Node, Element/ImageElement, Event, HtmlFragmentParser)
├── style/        CSS (CssParser, StyleResolver, UserAgentStyles, Style/ComputedStyle)
├── layout/       Box model・Flexbox計算 (LayoutEngine)
├── js/           ページ側JS実行 (JsEngine, JsElement, JsDocument, JsWindow, JsStorage,
│                 JsStyle, JsXMLHttpRequest, JsCustomEventHost/JsEvent, JsClassList,
│                 JsDomContext, JsElementRegistry, HtmxSeqOptimizer, HxOnAttributeScanner,
│                 Es6RhinoRunner(Babel経由のES6→ES5変換))
├── htmx/         HTMX連携・差分検知 (HtmxRenderEngine, SeqReconciler, StabilityTracking)
├── device/       アプリ内ショートカット(マクロ)実行 (DeviceScriptEngine, ShortcutApi,
│                 RjsShortcutScanner, DeviceToContentBridge)
├── input/        ヒットテスト・タッチ入力・テキスト選択・ピンチズーム
│                 (InputHandling, SelectionInputHandler, TextSelectionState, ZoomGestureHelper)
├── media/        video/audio再生 (JsMediaElement, MediaPlaybackService, VideoOverlayManager,
│                 CodecSupportChecker, ExternalPlayerFallback)
├── network/      Cookie管理 (SimpleCookieJar、サードパーティCookieブロック対応)
├── permissions/  ブラウザ機能の許可モデル (SitePermissions, GlobalAppSettings,
│                 BrowserCapabilityBridge, RuntimePermissionManager, LocalFilePicker)
├── data/         ブックマーク・履歴の永続化 (BookmarkStore, HistoryStore, BrowserDatabase)
├── tabs/         マルチタブ・ピン留め・PiP (TabManager, TabSession, TabBarView,
│                 TabKeepAliveService)
├── thermal/      発熱監視 (ThermalGuard)
├── benchmark/    実測ベースのレンダリングTier判定 (RenderTierBenchmark)
├── render/       描画バックエンド選択・周辺UI
│   ├── EngineHostView.kt / RendererFactory.kt   Canvas/GPU切り替えの共通契約
│   ├── CanvasRenderer.kt / EngineView.kt        software Canvasフォールバック
│   ├── AccessibilityBridge.kt                    独自描画DOMのTalkBack対応
│   ├── AddressBarView.kt / EngineFrameLayout.kt  アドレスバー・画面全体のレイアウト組み立て
│   ├── FindInPageController.kt                   ページ内検索(要素単位ハイライト)
│   ├── SelectionOverlayRenderer.kt                テキスト選択ハンドルの描画
│   └── gpu/    GPUレンダリング本体(GLES30, NDK不使用)
│       ├── GpuCapabilityDetector.kt   拡張機能検出・Tier判定
│       ├── QuadBatchRenderer.kt        背景色矩形のバッチ描画
│       ├── TextAtlas.kt / TextTextureCache.kt   テキストのラスタライズ+アトラス化キャッシュ
│       ├── TexturedQuadRenderer.kt / AtlasQuadRenderer.kt  テキストテクスチャの描画
│       ├── OesQuadRenderer.kt          動画(SurfaceTexture)のGL_TEXTURE_EXTERNAL_OES描画
│       ├── GLEngineRenderer.kt         GLSurfaceView.Renderer本体
│       └── GLEngineView.kt             GPU版WebView代替View
├── debug/        デバッグドロワー・挙動監査ログ (DebugDrawerView, BehaviorAuditLog)
├── util/         ClipboardHelper等の小物
└── EngineActivity.kt   エントリポイント(タブ・ナビゲーション制御、画面組み立てはEngineFrameLayoutへ委譲)
```

## 実装済み機能の概要

### DOM / CSS / レイアウト

- Jsoupで構文解析のみ任せ、`Node`/`Element`ツリーへ変換(`HtmlFragmentParser`)
- CSS: セレクタ(カンマ区切り複合可)・`!important`・詳細度/ソース順によるカスケード解決(`CssParser`/`StyleResolver`)
- タグ既定スタイル(User Agent Stylesheet相当、`h1`等の既定フォントサイズ・margin)を実装済み
- 対応プロパティ: `color`/`background-color`/`font-size`(px/em/%/rem/vw/vh)/`display`/`position`/`width`/`height`/`z-index`/`pointer-events`/`margin`系(%対応)/`padding`系(%対応)/`text-align`/`text-decoration`、Flexbox一式(後述)
- **`<img>`のHTML `width`/`height`属性フォールバックに対応**：ページ側CSSでwidth/heightが一切指定されていない場合に限り、`<img width="200" height="100">`のような属性値をCSS px相当として採用する(`StyleResolver.resolve()`)。CSS側の指定が最優先である点はHTML仕様通り
- density(画素密度)対応：CSSのpx/em/rem/vw/vh等はすべて「デバイス非依存ピクセル」として解釈し、物理ピクセルへの変換は`StyleResolver`内で一括して行う(GLの投影行列・タッチ入力・アクセシビリティ座標など、他の座標系には手を入れない設計)
- Flexbox: `flex-direction`(row/column)・`justify-content`・`align-items`・`flex-grow`/`flex-shrink`/`flex-basis`・`gap`に対応。`flex-wrap`は非対応(常にnowrap相当)
- インラインフロー・`<a>`タグ対応：`display:inline`要素とテキストが混在する内容を1つの折り返し塊として扱い、実ブラウザ同様「文中リンク」として描画・タップ判定できる。ただし`<a>`の中にさらに`<b>`等を入れる二重入れ子は非対応
- 外部`<link rel="stylesheet" href="...">`の取得・インライン`<style>`両方に対応(`EngineActivity`)
- SVG画像は非対応(`BitmapFactory`がラスター画像専用のため。デコード自体は試みるが`ImageLoadState.FAILED`になり、クラッシュはしない)

### 描画

- `RendererFactory`が起動時にGPU/Canvasを自動選択。GPU実装は`GLES30`によるテキスト・矩形のバッチ描画(1バッチ=1 drawCall基準)
- テキストは折り返し塊(inline run)単位でラスタライズし、共有アトラス(`TextAtlas`、4096×4096)に敷き詰めることでdrawCall数をテキスト要素数ではなくアトラスページ数に抑える。複数行折り返しは`StaticLayout`ベースで実装済み
- `RenderTierBenchmark`：GPU拡張の静的検出(`GpuCapabilityDetector`)だけでなく、実機での描画フレーム時間を実測してTierを確定する。1セッションの結果では確定させず、3セッション分の多数決で確定(発熱中のセッションは投票から除外)。確定後は端末(`Build.FINGERPRINT`)ごとにキャッシュし、判定を強制するベンチマークは走らせない
- 独自描画DOMをAndroid標準のアクセシビリティフレームワーク(TalkBack等)に橋渡しする`AccessibilityBridge`を実装済み

### JSエンジン(Rhino)・htmx連携

- ページ側`<script>`をRhinoで実行(`JsEngine`)。DOM操作(`JsElement`/`JsDocument`)、`window`/タイマー/`location`スタブ(`JsWindow`)、`localStorage`/`sessionStorage`(`JsStorage`)、インラインstyle代入(`JsStyle`)、`XMLHttpRequest`(`JsXMLHttpRequest`、OkHttp裏付け)、`CustomEvent`(コンストラクタ形式・`createEvent`+`initCustomEvent`形式の両対応)に対応
- htmx.js(2.0.10、BSD 2-Clause License)を`assets/libs/htmx.min.js`に同梱し、`EngineActivity`起動時に自動読み込み。**htmxは必ず2.x系を使うこと**(4.x系は内部通信が`fetch()`前提になり、このエンジンの`fetch`未実装のため動かない)
- htmxの`kn`関数が無条件で`new Proxy(...)`を使うため、`Proxy`/`Reflect`をネイティブサポートするRhino 1.8.0以降(1.9.1を採用)が必須
- `HtmxSeqOptimizer`：`htmx:beforeSwap`/`afterSwap`に自動フックし、変化のなかった要素のGPU描画コマンドキャッシュを引き継ぐ差分最適化層
- `HxOnAttributeScanner`：htmxが使う`hx-on:`系属性探索用のXPathを、汎用XPath実装無しで代替する限定スキャナ
- `Es6RhinoRunner`(要`assets/libs/babel.min.js`、MIT License、未同梱)：`enableEs6Support`呼び出しでES6+構文をBabel経由でES5変換してから実行可能
- 未実装: `MutationObserver`(代わりに`innerHTML`代入後の手動`htmx.process()`フックで代替)、`hx-boost`とAndroidバックスタックの統合、`element.dataset`

### アプリ内ショートカット(マクロ)実行

- `device/`パッケージが、ページ側JS(content)とは独立した「アプリ内マクロ」実行系を提供する。以前のBeanShell(bsh)実装から、content側と同じRhinoへ一本化・置き換え済み
- `assets/shortcuts/`配下の`*.rjs`ファイルをファイル名ベースでショートカットとして自動登録(`RjsShortcutScanner`)
- 安全設計の原則：`Context.javaToJS(activity, scope)`のようなActivityそのものへの無制限アクセスは一切公開しない。スコープに注入するのは`shortcuts`(`ShortcutApi`インスタンス)と少数の便利関数(print/popup/alert/runOnUIThread)のみで、`Packages`/`java`/`ctx`等の汎用Javaアクセス経路はスコープに置かない。新しいJava操作が必要な場合は必ず`ShortcutApi.kt`にメソッドを追加し、安全性を吟味した上で再ビルド・再配布する運用(動的なAPI拡張の仕組みは意図的に持たない。詳細は`DECISION_shortcut_api_boundary.md`参照)
- `DeviceToContentBridge`：device側の実行結果をcontent側(ページJS)へ橋渡しする際も、生のJava/Androidオブジェクト参照は渡さず値のみ渡す

### マルチタブ・ピン留め・PiP・発熱対策

- フォアグラウンド以外のタブは既定で完全休止(URLのみ保持、エンジン一式は破棄)
- `pinned`指定したタブはActivity破棄後もJS/メディアを裏で動かし続ける(`TabKeepAliveService`によるForeground Service延命)
- `pinned`かつ`showAsPip`のタブは、さらに小窓(Canvas固定、発熱対策)として画面に表示できる
- `ThermalGuard`が`PowerManager`の温度状態悪化を検知すると、pinned/PiPタブを強制的に減らす(API 29未満では常にNONE扱い)

### メディア再生(video/audio)

- `<video>`/`<audio>`はExoPlayer(Media3)に実際のデコード/再生を委譲し、`JsMediaElement`が「操縦桿」としてRhinoバインディングを提供
- `<video controls>`のネイティブUI・フルスクリーン・PiPは`VideoOverlayManager`が、GPU描画パイプラインとは別のAndroid Viewレイヤーとして扱う(座標だけ毎フレーム同期)
- 動画のGPU直結描画は`OesQuadRenderer`が`SurfaceTexture`を`GL_TEXTURE_EXTERNAL_OES`経由で描画する形で対応済み。ただし通常のGPU描画パイプライン全体との接続はまだ途上(下記「既知の要修正ポイント」参照)
- `CodecSupportChecker`(`canPlayType()`の実体)・`ExternalPlayerFallback`(候補が全滅した場合の外部アプリ委譲)を実装
- `MediaPlaybackService`：MediaSessionをバインドし、ロック画面/通知/Bluetoothボタン連携をOSに任せるForeground Service

### 権限・プライバシーモデル

- `SitePermissions`：`navigator.vibrate()`等、ページ側JSからの機能要求をドメイン単位で許可/拒否。既定はページ側からの要求を無効にする保守的な方針(実ブラウザのサイト権限モデルに倣う)
- `GlobalAppSettings`：ユーザー自身がアプリ全体に対して望む挙動(例: 常時スリープ防止)を、ドメインに依存せず一元管理
- `BrowserCapabilityBridge`：`navigator.vibrate()`/`wakeLock`/`screen.orientation.lock()`の実処理。要求元がページ側かユーザー操作かで参照する設定を切り替える
- `SimpleCookieJar`(OkHttp CookieJar)：サードパーティCookieを既定でブロックし、`SitePermissions`側でドメイン単位の例外を登録可能。永続化はプロセス内メモリのみ(再起動でクリア)
- `RuntimePermissionManager`：`POST_NOTIFICATIONS`等の実行時権限をまとめて要求
- `BehaviorAuditLog`：ページ側JSの挙動監査・このアプリ内で発生した操作の記録に限定したインメモリ監査ログ。OSレベルの全画面入力キャプチャや他アプリの監視は一切行わない

### ブックマーク・履歴

- `BrowserDatabase`(`android.database.sqlite`、SDK標準)上に`BookmarkStore`(URL一意)・`HistoryStore`(同一URL連続訪問は時刻更新のみで行を増やさない)を実装

### 入力・アクセシビリティ

- タップ・縦スクロール(`InputHandling`)、テキスト選択(`SelectionInputHandler`/`TextSelectionState`)、ピンチズーム(`ZoomGestureHelper`、スクロール操作とは別経路)に対応
- ページ内検索(`FindInPageController`)：文字列単位ではなく、クエリを含む要素ごとの背景ハイライトに割り切った実装
- `AccessibilityBridge`により、独自描画DOMをTalkBack等の標準アクセシビリティサービスへ「仮想ビュー階層」として公開

### ローカルファイルでの動作確認

#### 方法1: ドロワーの「📁開く」ボタン(基本はこちら)

1. ドロワー(ハンバーガーメニュー)を開き、アドレスバー行にある「📁開く」ボタンをタップする
2. システム標準のファイルピッカーが開くので、確認したい`.html`ファイルを選ぶ(端末内のどこにあってもよい)
3. 選択すると自動的にそのページへ遷移する

内部的にはSAF(Storage Access Framework)の`ACTION_OPEN_DOCUMENT`を使っており、ピッカーで明示的に選んだファイルへの読み取り権限がその場で付与される仕組みのため、Android 10以降のスコープドストレージの制限を受けない(`LocalFilePicker.kt`参照)。

既知の制約: `content://` URIはfile://と違い相対パス解決ができないため、この方法で開いたHTML内の相対パス画像参照(`<img src="images/x.png">`等)は解決に失敗する。絶対URL(`https://...`)の画像か、単体で完結したHTMLであれば問題ない。相対パス画像を含むテストページは方法2(file://)を使うこと。

#### 方法2: file://を直接入力(相対パス画像を含むページなど)

1. 確認したい`.html`ファイル(および相対参照する画像等)を、**端末の以下のディレクトリ**に配置する(MT Managerで配置してOK):
   ```
   /storage/emulated/0/Android/data/com.B.b.Renderer/files/
   ```
   このディレクトリ(`Context.getExternalFilesDir(null)`が指す場所)は**アプリ専用領域なので、Android 10以降のスコープドストレージ制限を受けず、追加の権限操作無しで確実に読み書きできる**。存在しなければ作成してよい(アプリ起動後に自動生成されることもある)。
2. アプリのアドレスバーに次の形式で入力する:
   ```
   file:///storage/emulated/0/Android/data/com.B.b.Renderer/files/test.html
   ```
   (`file://` のあとに絶対パスをそのまま続ける。スキームの後ろが `///` と3連続スラッシュになる点に注意)
3. HTML内から`<img src="images/photo.png">`のように相対パスで画像を参照している場合も、同じディレクトリ配下に置けば自動的に解決される。

**`/storage/emulated/0/Download`等の「一般の公開ストレージ」に置いたファイルを直接`file://`で開きたい場合**は、Android 10以降ではOS側のスコープドストレージ制限により読み込みに失敗することがある(`AndroidManifest.xml`の`READ_EXTERNAL_STORAGE`権限は`maxSdkVersion="32"`を付けており、API 33以降ではそもそも効果を持たない)。この場合は方法1(ドロワーの「📁開く」ボタン)を使うこと。

読み込みに失敗した場合はクラッシュせず、`RENDER_DIAG`ログに`local file read failed: ...`という形で理由(`FileNotFoundException`等)が記録される(ハンバーガーメニューのデバッグログパネルで確認可能)。

## 既知の制約

- `flex-basis:auto`かつ`width/height:auto`な項目は、コンテンツ量に応じた自動サイズ計算(shrink-to-fit)ができないため基準サイズ0扱い(`flex-grow`指定との組み合わせが前提)
- `<a>`要素の中にさらに`<b>`等のインライン要素を入れる二重入れ子は非対応(周囲テキストとの折り返し計算の都合)
- SVG画像は非対応
- Web Storageの任意プロパティ代入(`localStorage.foo = 'bar'`のようなdot記法)は未対応。`getItem`/`setItem`等メソッドAPIのみ
- `MutationObserver`、`hx-boost`とAndroidバックスタックの統合、`element.dataset`は未実装
- ページ内検索は文字列単位ではなく要素単位のハイライトに割り切っている

## 既知の要修正ポイント

- `EngineActivity`の`initialUrl`は仮のプレースホルダー、Intent/設定からの受け取りに差し替える必要あり
- `HtmxRenderEngine`内のノード単位dirty判定で、`ActionSignature`の空インスタンスを間に合わせで使っている箇所がある(次回リファクタ対象)
- `<video>`/`<audio>`のGPU直結(`SurfaceTexture`経由)はGPUレンダリングパイプライン全体とまだ完全には接続していない
- テキストは「1つの折り返し塊(inline run)につき1テクスチャ」方式。段落数が多いページではdrawCall数が増える(将来的な最適化余地)
- `network_security_config.xml`は`base-config cleartextTrafficPermitted="true"`にしてある。**汎用ブラウザとしての意図的な設定**(Chrome/Firefox等の一般ブラウザアプリも同様に平文通信を許可し、危険性の警告はブラウザのUI側の役割としている)。ここを`false`に戻すと、httpサイトが軒並み開けなくなるので注意
- コンパイル未検証の範囲: pushでのCI結果・実機ログ(`RENDER_DIAG`等)を都度確認すること。実装から実機確認までにタイムラグがある機能が含まれる

## ライセンス関連

- `htmx.min.js`(`app/src/main/assets/libs/`に同梱、2.0.10): htmx.org、BSD 2-Clause License
- `babel.min.js`(ES6サポート用、MIT License): 未同梱。`enableEs6Support`を使う場合のみ`app/src/main/assets/libs/babel.min.js`に配置すること
