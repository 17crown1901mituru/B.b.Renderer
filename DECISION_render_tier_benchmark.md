# 決定事項: 起動時Tier判定は実測ベンチマーク方式で確定、外部データソースは不採用

OPEN_DECISIONS.mdの「ベンチマークテストが具体的に何を測る想定だったか」への回答。

## 結論

**GPU拡張・GPUモデル名の実効性能を外部から取得する仕組みは追加しない。実機での実測(`RenderTierBenchmark`)のみを判定根拠とする。**

## 検証した外部データソース案(すべて不採用)

| 案 | 却下理由 |
|---|---|
| Khronos OpenGL Registry | 拡張仕様書・APIヘッダのみ。性能データは無い |
| Expo `gl-view` | React Native前提。Java/Kotlin縛りの方針と不一致 |
| Realtech VR GLview | Windowsデスクトップアプリ。公開APIなし、Androidから叩けない |
| `execomrt/glview-kit` | Windows専用C++17 SDK(`infogl.dll`依存)。NDK/C++禁止方針に直接抵触 |

モバイルGPU(Adreno/Mali/PowerVR等)の実効性能を機械可読な形で公開している公式・準公式ソースは存在しないと判断した。

## 採用した設計: `RenderTierBenchmark`

- `GpuCapabilityDetector`(静的なEGL拡張判定)は毎起動そのまま実行を継続。軽量なので問題ない
- 加えて、まだ判定が確定していない端末(`Build.FINGERPRINT`単位)でのみ、実際のページ描画フレームを計測する
  - 最初の10フレームはウォームアップとして除外
  - 続く30フレームの平均描画時間(`glFinish()`で GPU 完了を待って計測)が33ms(30fps相当)を超えたら、そのセッションは`GPU_SLOW`
  - 1回のセッション結果だけでは確定させない。**3セッション分の結果が集まった時点で多数決により確定**し、以後はキャッシュ済みの判定を使う(単発の外れ値による誤判定を避けるため)
  - サンプリング中に`PowerManager.currentThermalStatus`が悪化していた場合はそのセッションを投票にすら加算せず破棄する(発熱由来の一時的な低下を混入させないため)
- 確定して`GPU_SLOW`になった端末は、`RendererFactory.create()`でTier判定に関わらずCanvas版(`EngineView`)を強制する
- デバッグドロワーに現在の判定状態の表示と、手動リセットボタン(`RenderTierBenchmark.reset()`)を追加した。判定ミスに気づいた場合や、OSアップデートを伴わないドライバ更新があった場合の安全弁

## 保留にしたもの

`GL_OES_vertex_array_object`/`GL_EXT_multi_draw_arrays`拡張を実際に使う描画パス(VAO化、multi-draw-arrays化)の新規実装は行っていない。理由: 現状の`QuadBatchRenderer`/`AtlasQuadRenderer`は元々1バッチ=1 draw callに収まっており、これらの拡張が効く「大量のdraw callをまとめる」場面が無い。拡張パスを実装してから旧実装と比較する、という本来の意味でのA/Bベンチマークは、拡張パス自体の実装が先に必要な別タスクとして保留する。

## 変更したファイル

- 新規: `benchmark/RenderTierBenchmark.kt`
- `render/gpu/GLEngineRenderer.kt`: ベンチマークのフック(`onSurfaceCreated`/`onDrawFrame`)、および前回合意済みの`setRenderer`クラッシュ修正(`layoutEngine`可変化、`updateLayoutEngine()`)を反映
- `render/gpu/GLEngineView.kt`: 上記に伴う`attach()`の分岐(初回のみ`setRenderer()`)、`appContext`の受け渡し
- `render/RendererFactory.kt`: `shouldForceCanvasFallback()`によるTier判定の上書き
- `debug/DebugDrawerView.kt`: 判定状態の表示・手動リセットボタン
- 新規: `permissions/RuntimePermissionManager.kt`、`EngineActivity.kt`: `POST_NOTIFICATIONS`のruntime request(既知バグの修正)

## 未確認事項(このドキュメントでは扱わない)

実機ビルドでの動作確認は未実施。ビルド環境がこのセッションから直接アクセスできないため、次のステップとしてユーザー側で確認が必要。
