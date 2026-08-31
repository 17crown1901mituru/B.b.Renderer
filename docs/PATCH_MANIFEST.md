# このzipについて

拡張機能アカウント側でここまで実装・修正した差分一式。展開してプロジェクトルートへ
そのまま上書きコピーすれば、パスのズレなく反映できます。

## 反映方法

```bash
unzip -o bb_renderer_patch.zip -d /path/to/project/
```

(`app/`配下は既存ファイルを上書き、`DECISION_engine_activity_feature_split.md`は
プロジェクト直下に新規追加されます)

## 含まれるファイル(18)

### 引き継ぎ資料(プロジェクト直下)
- `DECISION_engine_activity_feature_split.md` — EngineActivityのコールバック洗い出しと
  EngineFeature移行のための整理。本流側での実装時に参照してください。

### 新規ファイル(5)
- `app/src/main/java/com/B/b/Renderer/data/ClipboardHistoryStore.kt`
- `app/src/main/java/com/B/b/Renderer/input/TextSelectionGeometry.kt`
- `app/src/main/java/com/B/b/Renderer/input/TextSelectionGestureHelper.kt`
- `app/src/main/java/com/B/b/Renderer/render/SelectionOverlayView.kt`
- `app/src/main/java/com/B/b/Renderer/js/RhinoSandbox.kt`

### 修正ファイル(13)
- `app/src/main/java/com/B/b/Renderer/EngineActivity.kt`
- `app/src/main/java/com/B/b/Renderer/data/BrowserDatabase.kt`
- `app/src/main/java/com/B/b/Renderer/debug/DebugDrawerView.kt`
- `app/src/main/java/com/B/b/Renderer/device/DeviceScriptEngine.kt`
- `app/src/main/java/com/B/b/Renderer/js/JsEngine.kt`
- `app/src/main/java/com/B/b/Renderer/js/JsWindow.kt`
- `app/src/main/java/com/B/b/Renderer/permissions/BrowserCapabilityBridge.kt`
- `app/src/main/java/com/B/b/Renderer/permissions/SitePermissions.kt`
- `app/src/main/java/com/B/b/Renderer/render/EngineFrameLayout.kt`
- `app/src/main/java/com/B/b/Renderer/render/EngineHostView.kt`
- `app/src/main/java/com/B/b/Renderer/render/EngineView.kt`
- `app/src/main/java/com/B/b/Renderer/render/gpu/GLEngineView.kt`
- `app/src/main/java/com/B/b/Renderer/util/ClipboardHelper.kt`

## 実装内容の要約

1. 画面長押しテキスト選択→コピー(単一テキスト要素・単一行相当のみ対応、詳細は
   `TextSelectionGeometry.kt`のコメント参照)
2. コピー履歴(SQLite、最大100件・自動間引き)、ドロワー「クリップボード」タブ
   (手入力の一時保存欄つき)
3. `navigator.clipboard.writeText()` / `readText()`対応(`JsThenable`という簡易Promise代替、
   `SitePermissions.CLIPBOARD_WRITE` / `CLIPBOARD_READ`でサイト単位の許可制)
4. `RhinoSandbox`: `Packages`/`java`等のグローバルと、リフレクション経由の到達を
   `ClassShutter`で塞ぐ(`JsEngine`・`DeviceScriptEngine`両方に適用)。デプロイ時の
   安全性チェックで指摘された箇所への対応。

## 未実装(引き継ぎ資料参照)

`EngineFeature`基盤(EngineActivityの機能追加のたびの肥大化を解消する設計)は、
設計のみで実装は未着手。`DECISION_engine_activity_feature_split.md`にコールバック
洗い出し・移行イメージを記載済み。
