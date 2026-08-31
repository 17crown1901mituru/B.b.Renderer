package com.B.b.Renderer.features

import com.B.b.Renderer.EngineActivity
import com.B.b.Renderer.permissions.BrowserCapabilityBridge
import com.B.b.Renderer.permissions.GlobalAppSettings
import com.B.b.Renderer.permissions.SitePermissions
import com.B.b.Renderer.render.EngineFrameLayout
import com.B.b.Renderer.render.EngineHostView
import com.B.b.Renderer.tabs.TabManager
import com.B.b.Renderer.tabs.TabSession

/**
 * EngineActivityの機能拡張ポイント(2026-08、God Activity化対策として導入)。
 *
 * それまではクリップボード・テキスト選択・検索・ズーム…といった機能ごとの配線
 * (`engineFrame.onCopyTapped = {...}`のようなコールバック代入や、関連インスタンスの生成)が
 * 機能が増えるたびにEngineActivity.onCreate()へ積み上がっていく構造だった。この積み上がりを
 * 止めるため、「機能1つ = EngineFeatureを実装した1クラス」とし、EngineActivity側は
 *   1. 起動時に機能のリストを作る(featuresプロパティ)
 *   2. onCreate/onSessionAttached/onDestroyの3タイミングでリストを順に呼ぶだけ
 * という薄い箱に留める。
 *
 * 設計方針:
 *   - 各Featureは自分が必要とするコールバックの配線を、自分のonCreate()/onSessionAttached()の
 *     中で完結させる。「新しいコールバックを1つ足すたびにEngineActivity.onCreate()に
 *     専用コードを書き足す」という増殖パターンを止めるのが目的なので、Feature実装側は
 *     EngineFeatureContext経由で受け取ったコラボレータに対して自分で代入してよい
 *     (EngineActivity側が代わりに配線してあげる必要はない)。
 *   - タブ切替のたびに再配線が必要なもの(engineHost.onHtmxTrigger/onNavigate/
 *     onTextSelectionChanged等、TabSession固有の値をクロージャで捕捉する必要があるもの)は
 *     onSessionAttached(session, context)で都度差し替える。これはこれまで
 *     EngineActivity.applyForeground()に集約されていた処理の一部を、機能ごとに切り出した形。
 *   - Featureは自分専用の状態(前回の選択範囲、ダイアログの表示中フラグ等)を自分のクラス内に
 *     持ってよい。むしろEngineActivityのフィールドとして持たせるのをやめるのが狙いの半分。
 *   - EngineFeatureContext.activityを公開しているのは、Context型引数を要求する既存API
 *     (例: ClipboardHelper.copyToClipboard(context, ...))やrunOnUiThread()呼び出しのため。
 *     Activity全体への参照を渡す以上「何でもできてしまう」制約の緩さそのものは残るが、
 *     少なくとも「onCreate()に書き足す」という増殖の圧力は無くなる。
 *   - onCreate/onSessionAttached/onDestroyはいずれもデフォルト実装(何もしない)を持つため、
 *     必要なものだけoverrideすればよい。
 *
 * 移行方針(2026-08時点): 既存のEngineActivity内の配線を全部いっぺんに移すのはリスクが
 * 高いため、まずクリップボード関連(features/ClipboardFeature.kt)を皮切りに、1機能ずつ
 * 段階的に移す。移植候補の一覧・進め方はDECISION_engine_feature.mdを参照。
 */
interface EngineFeature {

    /** engineFrame/engineHost等の主要Viewが用意された直後、Activity生存中に1回だけ呼ばれる。 */
    fun onCreate(context: EngineFeatureContext) {}

    /** フォアグラウンドタブが(新規オープン・切替・戻る/進む/リロードで)差し替わるたびに呼ばれる。 */
    fun onSessionAttached(session: TabSession, context: EngineFeatureContext) {}

    /** Activity.onDestroy()。リソース解放が必要なFeatureのみoverrideする。 */
    fun onDestroy() {}
}

/**
 * Featureが必要とする主要コラボレータをまとめたもの。EngineActivity.onCreate()内で
 * 主要Viewの組み立てが終わった時点で1つ生成し、以後は全Featureで使い回す
 * (Feature側で同じ参照を毎回persist/受け渡しし直す必要が無いようにするため)。
 *
 * ここに無いコラボレータが必要になった場合(例: 新しいFeatureがHistoryStoreを必要とする等)は、
 * このクラスにフィールドを追加すること。EngineActivity側の該当フィールドを
 * private→internal/publicに変える程度で済むはずで、既存Featureへの影響は無い。
 */
class EngineFeatureContext(
    val activity: EngineActivity,
    val engineFrame: EngineFrameLayout,
    val engineHost: EngineHostView,
    val tabManager: TabManager,
    val sitePermissions: SitePermissions,
    val globalSettings: GlobalAppSettings,
    val capabilityBridge: BrowserCapabilityBridge,
)
