# EngineActivity 整理メモ(EngineFeature移行のための引き継ぎ資料)

作成: 拡張機能アカウント側 / 2026-08
対象: `app/src/main/java/com/B/b/Renderer/EngineActivity.kt`(現在930行)

## 今の状態と、本流側への要望(このセクションのみ都度書き換える)

- **本流側で実装完了**: `EngineFeature`インターフェース本体(`features/EngineFeature.kt`)、
  および最初の移植先として`ClipboardFeature`(`features/ClipboardFeature.kt`)を実装済み。
  クリップボード/テキスト選択(`onCopyTapped`/`onCancelSelectionTapped`/
  `onClipboardWriteRequested`/`onClipboardReadRequested`/`onTextSelectionChanged`)は
  全てこのFeatureへ移植し、EngineActivity本体からは配線コードを削除した(挙動は変更なし)
- 最終的な設計・構築順序の注意点(下記4-1)への対応・`EngineFeatureContext`に何を
  含めたか等は`docs/decisions/DECISION_engine_feature.md`にまとめた。以後の設計判断は
  そちらを正とする(このファイルの2〜6章は着手前の分析としての参照価値のみ残す)
- **次の移植候補**: ページ内検索(FindInPageController)・ズーム。優先度や対象外に
  したもの(`buildSession`本体・`DebugDrawerView`構築・タブナビゲーション本体)も
  `docs/decisions/DECISION_engine_feature.md`に記載済み
- 状況が変わったら、この4行を現在の状態に合わせて書き換えるだけでよい
  (過去のログは残さない。いつ誰が何をしたかはgitのコミットログを参照する)

## 1. 目的

`EngineActivity`が機能追加のたびに肥大化する「God Activity」化を解消するため、
`EngineFeature`インターフェース(機能ごとに配線をカプセル化し、Activity本体は
リストを回すだけの薄い箱にする案)への移行を本流側で実装してもらうにあたり、
現状の責務・コールバック配線を洗い出したもの。実装方針そのものの決定は含まない
(判断材料の提供が目的)。

---

## 2. 現状の責務カテゴリ(体感的な行数の内訳)

| カテゴリ | 主な内容 | 目安行数 |
|---|---|---|
| A. タブのライフサイクル | `runNavigation`/`goBack`/`goForward`/`reloadCurrentTab`/`switchToTab`/`closeTab`/`applyForeground` | 〜130行 |
| B. セッション構築 | `buildSession`(fetch→parse→style→layout→JS初期化の一括構築、非同期) | 〜130行 |
| C. 画面の組み立て配線 | `onCreate`内、`EngineFrameLayout`/`DebugDrawerView`/`TabBarView`へのコールバック注入 | 〜120行 |
| D. PiP/発熱対応 | `refreshPipOverlays`/`ThermalGuard`連携/`syncKeepAliveService` | 〜40行 |
| E. ネットワーク/ローカルファイル | `fetchHtml`/`readLocalFile`/`readContentUri`/`fetchStylesheets` | 〜100行 |
| F. 画像読み込み | `loadImage` | 〜50行 |
| G. **クリップボード機能(今回追加)** | `onCopyTapped`/`onCancelSelectionTapped`/`onClipboardWriteRequested`/`clipboardHistoryStore` | 〜25行 |
| H. **テキスト選択UI配線(今回追加)** | `onTextSelectionChanged`(`applyForeground`内) | 〜10行 |
| その他 | device shortcuts橋渡し(`buildShortcutApi`)、URL解決、フォームパラメータ収集等 | 残り |

G・Hが今回このアカウントで追加した分。**「1機能でこの規模」が積み重なっていくと
そう遠くないうちにA〜Fと同程度の分量になる**、というのが今回の実感。

---

## 3. コールバック/状態の洗い出し(Feature移行の対象候補)

タイミング列の意味:
- `onCreate`: Activity起動時に1回だけ配線
- `perSession`: `applyForeground(session)`のたびに配線し直す(タブ切替のたび)
- `perAction`: ユーザー操作等、都度呼ばれるだけ(配線タイミングの問題ではない)

| コールバック/状態 | 所有(現状) | タイミング | 依存するもの | Feature化の優先度 |
|---|---|---|---|---|
| `engineFrame.addressBarView.onSubmit` | EngineActivity | onCreate | `navigateForegroundTo` | 低(コア機能) |
| `engineFrame.onCopyTapped` | EngineActivity | onCreate | `engineHost.selectedText()`, `ClipboardHelper`, `clipboardHistoryStore` | **高(切り出し済み設計あり)** |
| `engineFrame.onCancelSelectionTapped` | EngineActivity | onCreate | `engineHost.clearTextSelection()` | **高** |
| `capabilityBridge.onClipboardWriteRequested` | EngineActivity | onCreate | `ClipboardHelper`, `clipboardHistoryStore` | **高** |
| `engineHost.onTextSelectionChanged` | EngineActivity | **perSession** | `session.layoutEngine.zoomScale/scrollY`, `engineFrame.updateSelectionOverlay` | **高** |
| `engineHost.onHtmxTrigger` | EngineActivity | perSession | `session.onHtmxTrigger` | 低(コア機能) |
| `engineHost.onNavigate` | EngineActivity | perSession | `resolveUrl`, `navigateForegroundTo` | 低(コア機能) |
| `debugDrawer`への大量のコンストラクタ引数(21個) | EngineActivity | onCreate | ほぼ全フィールド | 中(下記4章参照) |
| `tabBarView`の4コールバック | EngineActivity | onCreate | `tabManager`, `syncKeepAliveService` | 低(コア機能) |
| `onBackPressedDispatcher`のコールバック | EngineActivity | onCreate | `engineFrame`, `tabManager` | 低(コア機能) |
| `jsEngine.window.onOpenPopup` | EngineActivity | **buildSession内(セッション構築のたび)** | `openNewTab` | 中 |
| `clipboardHistoryStore`(フィールド) | EngineActivity | onCreate(lazy) | なし(独立) | **高** |

**優先度「高」の5行が、今回設計した`ClipboardFeature`/`TextSelectionFeature`でそのまま
まとめられる想定の部分。** 実装は未着手(クラッシュ対応中断のため)。

---

## 4. 見えている罠・注意点

### 4-1. 構築順序への依存(重要)
`EngineFeature.onCreate(context)`的なフックを設ける場合、**`engineFrame`と`engineHost`が
両方存在した後**でないと配線できない(`onCreate`内、現在144行目付近以降)。一方
`tabManager`はそれより前(133行目)に作られている。Feature共通コンテキストを1つの
オブジェクトにまとめて渡す場合、そのオブジェクトの生成タイミングは
「`engineFrame`生成直後」に固定する必要がある。

### 4-2. GLSurfaceViewのタイミング制約(実際に踏んだ)
`GLEngineView.attach()`内で`setRenderer()`より前に`requestRender()`系の処理
(選択解除等)を呼ぶと、GLThread未生成でNullPointerExceptionになる。
Feature側から`engineHost`に対して何か「即座に再描画を伴う」操作をさせる設計にする場合、
**`attach()`の呼び出し順序(setRenderer確立が先)を必ず意識すること**。
今回は`onSessionAttached`相当の処理を`attach()`内の`setRenderer`/`queueEvent`より
後ろに置くことで回避した(`GLEngineView.kt`参照)。

### 4-3. `DebugDrawerView`のコンストラクタ肥大化
Feature化の対象を「EngineActivity本体の配線」に絞ると、`DebugDrawerView`の
コンストラクタ引数(現在21個)は手つかずのまま残る。ドロワー内のタブ(ナビ/検索表示/
クリップボード/ログ/設定)も実質「機能ごとのUI」なので、将来的には
`EngineFeature`が「自分のドロワータブ(View)を返す」ような形にできると、
`DebugDrawerView`側の肥大化も同時に解消できる可能性がある(**今回は未設計、
検討課題として提示のみ**)。

### 4-4. `applyForeground`はActivity本体に残さざるを得ない
タブ切替の中核(`engineHost.attach`呼び出し、`currentPageUrl`更新、履歴記録、
`findInPage`再構築)はFeatureに切り出す性質のものではない(=拡張ではなくコア機能)。
Featureに渡すのは「`applyForeground`が終わった後のsessionそのもの」で十分なはず。

---

## 5. 今回実装した2機能の移行イメージ(参考、未実装)

```kotlin
interface EngineFeature {
    fun onCreate(context: EngineFeatureContext) {}
    fun onSessionAttached(context: EngineFeatureContext, session: TabSession) {}
    fun onDestroy() {}
}

class EngineFeatureContext(
    val activity: Activity,
    val engineHost: EngineHostView,
    val engineFrame: EngineFrameLayout,
    val capabilityBridge: BrowserCapabilityBridge,
    val tabManager: TabManager,
)
```

- `ClipboardFeature`: `ClipboardHistoryStore`の所有、`onClipboardWriteRequested`の配線、
  `save(text)`を公開(→ドロワー構築時に`historyStore`/`onCopyFromHistoryRequested`を
  ここから取り出して渡す)
- `TextSelectionFeature(clipboardFeature)`: `onCopyTapped`/`onCancelSelectionTapped`/
  `onSessionAttached`内での`onTextSelectionChanged`配線。`ClipboardFeature.save()`を呼ぶだけで
  クリップボード側の実装詳細(SQLite等)には触れない

この2つは実装が数十行程度で完結する見込みで、**移行の実証・雛形として一番リスクが低い**
と思う。全体(タブ/PiP/ネットワーク層)まで一気に持っていくよりも、まずこの2つで
`EngineFeature`基盤自体が正しく機能するかを確認してから範囲を広げる進め方を推奨。

---

## 6. 未確定事項(本流側での判断待ち)

1. `EngineFeatureContext`に何を含めるか(`tabManager`まで渡してよいか、Featureが
   タブ操作までできてしまうと責務が曖昧になる懸念もある)
2. ドロワーのタブ化(4-3)まで踏み込むか、今回は見送るか
3. `onDestroy`/`onPause`/`onRequestPermissionsResult`等、他のActivityライフサイクルも
   Featureへフックを用意するか(現状クリップボード/テキスト選択はどちらも不要だったため
   最小のフックのみ設計した)
