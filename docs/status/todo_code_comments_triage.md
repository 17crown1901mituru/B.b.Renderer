# コード内メモ書き 洗い出しTODOリスト

作成: 拡張機能アカウント側 / 2026-08
対象: コード内コメントに散見される「TODO」「未対応」「未実装」「既知の制約」
「暫定」「見送り」等のマーカー、および旧README.mdの「現状のステータス」
「未実装・既知の制約」「既知の要修正ポイント」節から移設した項目。

## 運用ルール(このファイルの更新方法)

- 新設・改修に着手する前に、このファイルで優先度・対応範囲を確認する
- **対応が終わったら、該当項目をこのファイルから削除する。** いつ・誰が対応したかは
  git のコミットログで追えるので、このファイル側に完了履歴を残さない
  (残すと時間が経つほど未対応項目が埋もれて見づらくなるだけで、二重管理になる)
- 新たにコード内で同種のコメントを見つけたら、優先度を判断した上でP0〜P2のいずれかに追記する
- 集計(末尾)の件数は、追加・削除のたびに更新する
- **意図的な設計判断(理由があってそうしている)はここに書かない。** それは
  `docs/decisions/`の役目。ここに書くのは「まだ対応できていないこと」だけ

## 優先度の基準

| 優先度 | 基準 |
|---|---|
| **P0** | 「未対応→安全側の代替」ではなく「未対応→気付かれず違う結果になる」もの。よくあるページ/操作で発生しうる |
| **P1** | 機能として未対応だが、フォールバック(空文字・無視・簡易値)で動作は継続する。よくあるケースで発生しうる、または影響範囲が広い |
| **P2** | 発生条件が限定的、または見た目・性能面の改善どまり |

P0が一番「気付かれないまま静かに間違った表示/挙動になる」ため、実害の割に発見しにくく優先度を高くしている。

---

## P0(優先度: 高)

### 1. `CssParser.kt` — 未対応の`@media`条件が「常に適用」扱いになる
`orientation`/`resolution`等、未対応の`@media`条件は「無視されて除外」ではなく**「常にtrue」として本来出したいはずのスタイルがそのまま適用**されてしまう(L34-43, L153, L175)。「未対応→安全側で無視」ではなく「未対応→意図と違うスタイルが常に当たる」ため、ページ側の意図と異なる表示になり得る。
- 該当箇所: `CssParser.kt:34, 36, 43, 153, 175`
- 影響: `@media (orientation: portrait)`等を使うページで、意図しないスタイルが常時適用される
- 対応案: 未対応条件は「常にtrue」ではなく「常にfalse(適用しない)」に倒す方が事故が少ない

### 2. `StyleResolver.kt` — カスケード評価順序による値の取りこぼし
簡易的な逐次カスケード評価のため、後から反映されるべき値が反映される前の値を使ってしまうケースがある(L248, L279、2箇所で同一制約に言及)。
- 該当箇所: `StyleResolver.kt:248, 279`
- 影響: 特定のセレクタ優先順位の組み合わせで、意図と異なるスタイルが最終的に勝つ

### 3. `GLEngineRenderer.kt` — テキストのセグメント化が単語区切りを無視
DOM順そのままでセグメント化しており、単語区切りを考慮していない(L350)。
- 該当箇所: `GLEngineRenderer.kt:350`
- 影響: GPU描画パスで、テキストの見た目上の区切りがDOM構造次第でおかしくなる可能性

---

## P1(優先度: 中)

### 4. `UserAgentStyles.kt` — margin/font-weight/一部単位が未実装
- margin未実装(`StyleResolver`がmarginプロパティ自体未対応)
- font-weight(太字)は`ComputedStyle`にフィールドはあるが`StyleResolver`が未対応
- em/rem/キーワード単位は未対応でフォールバック値になる
- 該当箇所: `UserAgentStyles.kt:12, 22, 32, 37`
- 影響: 基本的なCSSボックスモデル・文字装飾が効かないページが多く出る、体感品質への影響が大きい

### 5. `LayoutEngine.kt` — インライン入れ子要素・行高さの制約
`<a><b>text</b></a>`のような入れ子インライン要素で、内側(`<b>`)のテキストが無視される。行の高さもコンテナ自身のfont-size基準で一律(L384-390)。
- 該当箇所: `LayoutEngine.kt:384-390`
- 影響: リンクの中に強調タグ等がある一般的なマークアップで、一部テキストが表示されない

### 6. `LayoutEngine.kt` — ルート基準%の高さ未対応
`height: 50%`等、ルート要素基準のパーセント指定が簡易実装では未対応(L714、`contentHeight`を代用)。
- 該当箇所: `LayoutEngine.kt:714`

### 7. `JsWindow.kt` — `hx-boost`のAndroidバックスタック統合が未実装
実際のAndroidバックスタックとの統合は別途必要、と明記(L127)。現状no-opスタブ。
- 該当箇所: `JsWindow.kt:127`
- 影響: HTMXの`hx-boost`を使うページで、戻るボタンの挙動がSPA的にならない

### 8. `JsElement.kt` — outerHTML相当の読み取りが未実装
シリアライズ未実装のため常に空文字列を返す(L68)。
- 該当箇所: `JsElement.kt:68`
- 影響: `element.outerHTML`を読み取って処理するページ側スクリプトが動かない

### 9. `JsStyle.kt` — `element.style.xxx`の対応プロパティが頻出のみ
未対応プロパティへの代入は無視される(L11)。
- 該当箇所: `JsStyle.kt:11`

### 10. `EngineActivity.kt` — content://スキームの相対パス解決
`java.net.URI.resolve()`によるfile://同様の相対パス解決を受けない(L690-696)。将来的にSAFのDocumentsContract経由で兄弟ドキュメントを解決する案が示唆されている。
- 該当箇所: `EngineActivity.kt:690-696`
- 影響: SAFピッカーで開いたローカルHTMLが、相対パスで画像/CSSを参照していると読み込めない

### 11. `JsEngine.kt` — 複雑な混在パターンでスクリプト実行順がズレる制約
- 該当箇所: `JsEngine.kt:420, 424`(content://からの相対src解決も同じ理由で既知の制約)

### 12. 外部`<link rel="stylesheet">`の取得が未対応
`<style>`インラインのみ対応、外部CSSファイルの読み込みは未実装。
- 影響: 外部CSSに依存する一般的なページでスタイルが一切当たらない

### 13. `MutationObserver`が未実装
代わりにJS側の`innerHTML`代入後、自動で`htmx.process(element)`を呼ぶ手動フック(`JsDomContext.onDomMutated`)で代替している。
- 影響: `MutationObserver`を直接使うページ側スクリプトは動かない(htmx自体の差分検知は別経路のため影響を受けない)

### 14. `HtmxRenderEngine`のノード単位dirty判定が暫定実装
`ActionSignature`の空インスタンスを間に合わせで使っている箇所がある(次回リファクタ対象、と明記)。
- 影響: 特定条件でHTMX差分検知の精度に影響する可能性(詳細未検証)

### 15. `<video>`/`<audio>`のGPU直結が未接続
`SurfaceTexture`経由のGPUレンダリングパイプラインとまだ接続していない。GPU描画がデフォルトTierのため、影響範囲は広め。
- 影響: GPU Tierの端末で動画/音声要素が正しく描画されない可能性

---

## P2(優先度: 低)

| # | 内容 | 該当箇所 |
|---|---|---|
| 16 | GPU描画: 長い1要素のテキスト折り返し自体が未実装 | `TextAtlas.kt:16` |
| 17 | GPU描画: テキストのアトラスまとめ(バッチ描画)が未対応、drawCall増加は将来課題 | `GLEngineRenderer.kt:48, 254` |
| 18 | `CanvasRenderer`自体が「GPU本実装までの暫定レンダラー」という位置づけ | `CanvasRenderer.kt:12` |
| 19 | `CanvasRenderer`: containing block幅の簡易計算 | `CanvasRenderer.kt:67` |
| 20 | `@font-face`/`@keyframes`等の他at-ruleは安全側で無視(実害小さいため優先度低) | `CssParser.kt:69` |
| 21 | 未対応セレクタ(`:disabled`等の擬似クラス、子結合子`>`、属性セレクタ) | `CssParser.kt:193` |
| 22 | `JsStorage.kt`: dot記法での代入(`localStorage.foo = x`)未対応、bracket記法のみ | `JsStorage.kt:12` |
| 23 | `JsXMLHttpRequest.kt`: progress eventの逐次発火(ストリーミング)未対応 | `JsXMLHttpRequest.kt:28` |
| 24 | `TabKeepAliveService.kt`: 通知の専用アイコン未整備、`android.R.drawable`を仮使用 | `TabKeepAliveService.kt:50` |
| 25 | `EngineActivity.kt`: シークレットタブ運用のフラグ分岐が未実装 | `EngineActivity.kt:453` |
| 26 | `EngineFrameLayout.kt`: 選択位置直上のフローティングツールバー化、下部固定位置での簡易対応に留めている | `EngineFrameLayout.kt:88-90, 234` |
| 27 | `GLEngineView.kt`: 初回Surface生成遅延への応急策(`FLAG_NOT_FOCUSABLE`トグル)、根本原因は未特定 | `GLEngineView.kt:121` |
| 28 | `HtmlFragmentParser.kt`: StyleResolver実行前の暫定値 | `HtmlFragmentParser.kt:70` |
| 29 | SVG画像が非対応(ラスター専用の`BitmapFactory`のため)。読み込み失敗するが`ImageLoadState.FAILED`で安全に止まる(クラッシュしない) | `ImageLoader`関連 |
| 30 | `element.dataset`が未実装 | `js/`パッケージ |
| 31 | `EngineActivity`の`initialUrl`が仮のプレースホルダー、Intent/設定からの受け取りに差し替え未実施 | `EngineActivity.kt` |

---

## 集計

- P0: 3件
- P1: 12件
- P2: 16件
- 合計: 31件

**着手するならP0の3件から**、特に1番(`@media`誤適用)は「未対応→安全側」の原則を破っている唯一の箇所なので最優先候補。P1側では12番(外部stylesheet未対応)が一般的なページへの影響範囲として大きめ。
