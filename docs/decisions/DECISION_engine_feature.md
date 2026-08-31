# DECISION: EngineFeature基盤の導入(God Activity対策)

作成: 本流側 / 2026-08
関連: `docs/status/engine_activity_feature_split.md`(拡張セッション側による事前分析。
構築順序の注意点やコールバック洗い出しの元資料はそちら参照)

## 背景

`EngineActivity.kt`は機能が増えるたびに肥大化し続けていた(2026-08時点で930行超)。
典型的なパターンは、新機能を1つ足すたびに`onCreate()`へ

```kotlin
engineFrame.onXxxTapped = { ... }
someBridge.onYyyRequested = { ... }
```

のようなコールバック配線コードが増え、加えて`applyForeground()`(タブ切替時の再配線をまとめた
関数)にも「タブ固有の値を捕捉し直す配線」が同様に積み上がっていく、というものだった。
機能同士は本来ほぼ独立している(クリップボードとページ内検索は互いに関知しない)にも関わらず、
配線先が同じ1つの`onCreate()`/`applyForeground()`に混在するため:

- 1つの機能を読むために930行のActivity全体を読む必要がある
- 新機能追加のたびにActivity本体への差分が発生し、無関係な機能同士がdiff上で衝突しやすい
- ビルド環境を持たないアカウント(拡張セッション)が機能追加する際、Activity本体への変更が
  必須になり、コンパイル未検証のまま大きな共有ファイルを触るリスクが高まる

## 決定

`app/src/main/java/com/B/b/Renderer/features/EngineFeature.kt`に、機能拡張点となる
`EngineFeature`インターフェースと、Featureへ渡す`EngineFeatureContext`を導入した。

```kotlin
interface EngineFeature {
    fun onCreate(context: EngineFeatureContext) {}
    fun onSessionAttached(session: TabSession, context: EngineFeatureContext) {}
    fun onDestroy() {}
}
```

`EngineActivity`側は

1. `onCreate()`で主要View(`engineFrame`/`engineHost`/`tabManager`)を組み立てた直後に
   `EngineFeatureContext`を1つ作り、`features.forEach { it.onCreate(featureContext) }`
2. `applyForeground()`(タブ切替のたび)で`features.forEach { it.onSessionAttached(session, featureContext) }`
3. `onDestroy()`で`features.forEach { it.onDestroy() }`

を呼ぶだけの薄い箱にする。各Featureは自分が必要とするコールバックの配線を、自分の
`onCreate()`/`onSessionAttached()`の中で完結させる。

`EngineFeatureContext`には`activity`/`engineFrame`/`engineHost`/`tabManager`/
`sitePermissions`/`globalSettings`/`capabilityBridge`を含めた(拡張セッション側の
未確定事項6-1「tabManagerまで渡してよいか」への回答: 渡す。Featureがタブ操作までできて
しまう責務の曖昧さより、フィールドを絞りすぎて後から頻繁に追加する方がコストが高いと判断)。
無いコラボレータが必要になった場合はこのクラスにフィールドを追加すればよく、既存Featureへの
影響は無い。

### 構築順序の制約(拡張セッション側4-1の指摘への対応)

`engineFrame`/`engineHost`が両方揃った直後(`onCreate()`内、`engineFrame`生成の直後)に
`featureContext`を生成している。`tabManager`はそれより前に作られるが、
`EngineFeatureContext`のコンストラクタに渡す時点で既に存在していれば順序上の問題は無い。

### GLSurfaceViewのタイミング制約(拡張セッション側4-2の指摘)

`GLEngineView.attach()`内で`setRenderer()`より前に`requestRender()`系処理を呼ぶと
GLThread未生成でNPEになる件は、拡張セッション側で`GLEngineView.kt`自体に
`safeRequestRender()`ガードと`clearSelection()`呼び出し位置の是正で対応済み(このFeature
基盤側での追加対応は不要)。

## 最初の移植: ClipboardFeature

拡張セッションが実装した「画面長押しテキスト選択→コピー・コピー履歴・
`navigator.clipboard.writeText()`/`readText()`」を`features/ClipboardFeature.kt`へ
移植した(挙動は変更なし、配線の置き場所を変えただけ)。理由:

- 5つの配線(`engineFrame.onCopyTapped`/`onCancelSelectionTapped`、
  `capabilityBridge.onClipboardWriteRequested`/`onClipboardReadRequested`、
  `engineHost.onTextSelectionChanged`)が互いに強く関連しており、1機能としてまとまりが良い
- `clipboardHistoryStore`というFeature固有の状態を持つため、「Featureは自分の状態を
  自分で持てる」というこの基盤の狙いを示す例として分かりやすい

拡張セッション側の当初案(`docs/status/engine_activity_feature_split.md`5章)では
`ClipboardFeature`と`TextSelectionFeature`を分ける設計だったが、実装時点で両者が
`clipboardHistoryStore`を介して密結合(テキスト選択のコピー確定時にコピー履歴へ保存する)
していたため、本流側では1クラス(`ClipboardFeature`)にまとめる方針に変更した。
分ける明確な利点(独立してテストする、片方だけ無効化する等)が今のところ無いための判断で、
将来的に分離が必要になったら分ければよい。

`clipboardHistoryStore`はドロワーの「クリップボード」タブ(`DebugDrawerView`)からも
参照する必要があるため、`ClipboardFeature.clipboardHistoryStore`を公開プロパティにし、
`EngineActivity`から`DebugDrawerView`のコンストラクタへそのまま渡している。
`DebugDrawerView`自体は複数機能(設定・履歴・ブックマーク・検索・ズーム・クリップボード)を
まだ1つのViewとして抱えたままで、今回のFeature化の対象外(下記「対象外にしたもの」参照)。

## 次の移植候補

1機能ずつ、本流側で移植していく想定。優先順ではなく、着手しやすさで並べる。

- **ページ内検索(FindInPageController)**: `onFindInPage`/`onFindNext`/`onFindPrevious`/
  `onFindClear`/`findStatusProvider`の5つのコールバックと、タブ切替のたび
  `findInPage?.clear(); findInPage = FindInPageController(...)`しているapplyForeground内の
  処理をまとめて`FindInPageFeature`へ。`FindInPageController`自体は既に単体クラスなので
  比較的移しやすい。
- **ズーム(zoom)**: `onZoomDelta`/`onZoomReset`/`zoomPercentProvider`の3つ。
  `tabManager.foregroundSession()?.layoutEngine`への参照だけで完結しており、
  `EngineFeatureContext.tabManager`のみで移植できるはず。
- **PiP/サーマル連携(refreshPipOverlays/syncKeepAliveService呼び出し)**: `TabBarView`の
  `onPinToggleRequested`/`onPipToggleRequested`やThermalGuardのコールバックから呼ばれている
  処理。ただし`closeTab`/`switchToTab`等ナビゲーション系と絡むため、後述の
  `EngineNavigator`的な小さい橋渡しインターフェースが要るかもしれない。

## 対象外にしたもの(当面Activity側に残す)

- **`buildSession()`とその周辺(HTML取得・パース・スタイル解決・レイアウト・JS/HTMXエンジン
  初期化・`loadImage()`)**: これはFeatureというより「タブ1つの構築そのもの」であり、
  無理に切り出すとコンストラクタ引数が膨大になるだけで見通しは良くならない。触るとしても
  「複数の小さなFeatureへ分解する」より先に、この処理自体を`TabSessionBuilder`のような
  専用クラスへ切り出す方が筋が良いはずで、これはEngineFeature基盤とは別の設計判断になる。
- **`DebugDrawerView`の構築**: 現状すでに1つの大きなViewクラスとして複数機能(設定・履歴・
  ブックマーク・検索・ズーム・クリップボード)を抱えている。EngineActivity側のコンストラクタ
  呼び出しを機能別に分割しても、受け皿であるDebugDrawerView自体が分かれない限り実質的な
  改善が薄い。DebugDrawerView自体をタブ(機能)ごとに分割するのは、これはこれで別の
  リファクタリング課題として切り出す想定(拡張セッション側4-3で提起された論点と同じ)。
- **タブナビゲーション本体(goBack/goForward/openNewTab/switchToTab/closeTab等)**: これらは
  「機能」というよりEngineActivityの本質的な責務(どのタブを今開くか)そのものなので、
  Feature化の対象にしない。

## 拡張セッションへの申し送り

新しい機能をこのアカウントで実装する場合、`EngineFeature`を実装した1クラスとして書き、
必要な配線は全てそのクラスの`onCreate()`/`onSessionAttached()`内で完結させてほしい。
`EngineActivity.kt`本体への変更は、`features`リストへ1行追加する(と、必要なら
`EngineFeatureContext`へのフィールド追加)だけで済むようにするのが狙い。
`EngineFeatureContext`に無いコラボレータが必要な場合は、その旨を伝えてもらえれば
本流側でフィールドを追加する。
