package com.B.b.Renderer.features

import com.B.b.Renderer.data.ClipboardHistoryStore
import com.B.b.Renderer.tabs.TabSession
import com.B.b.Renderer.util.ClipboardHelper

/**
 * 画面長押しテキスト選択→コピー、コピー履歴、navigator.clipboard.writeText()/readText()を
 * まとめたFeature(2026-08、拡張セッション側の実装をEngineFeatureパターンへ移植したもの。
 * 機能自体の挙動は変更していない)。
 *
 * 元はEngineActivity.onCreate()/applyForeground()内で
 *   - engineFrame.onCopyTapped / onCancelSelectionTapped の配線
 *   - capabilityBridge.onClipboardWriteRequested / onClipboardReadRequested の配線
 *   - engineHost.onTextSelectionChanged の配線(タブ切替のたび、applyForeground()経由)
 * に分散していたが、いずれも「テキスト選択→コピー」という1つの機能の一部であるため
 * このクラス1つにまとめてある。
 *
 * clipboardHistoryStore自体はこのFeatureが所有する。ドロワーの「クリップボード」タブ
 * (DebugDrawerView)からも同じ履歴を参照する必要があるため、EngineActivity側は
 * `ClipboardFeature.clipboardHistoryStore`をDebugDrawerViewの生成時に渡している
 * (DebugDrawerView自体はまだ複数機能を抱えた大きなViewのままで、今回のFeature化の
 * 対象外。次の移植候補も含めてDECISION_engine_feature.md参照)。
 */
class ClipboardFeature : EngineFeature {

    /** 画面長押しコピー・navigator.clipboard.writeText()どちらの保存先も兼ねる(最大100件)。 */
    lateinit var clipboardHistoryStore: ClipboardHistoryStore
        private set

    override fun onCreate(context: EngineFeatureContext) {
        clipboardHistoryStore = ClipboardHistoryStore(context.activity)

        // 画面長押し選択→画面下部「コピー」バーのタップ。
        context.engineFrame.onCopyTapped = {
            val text = context.engineHost.selectedText()
            if (text.isNotBlank()) {
                ClipboardHelper.copyToClipboard(context.activity, text)
                clipboardHistoryStore.add(text)
            }
            context.engineHost.clearTextSelection()
        }
        context.engineFrame.onCancelSelectionTapped = { context.engineHost.clearTextSelection() }

        // navigator.clipboard.writeText()。許可判定(SitePermissions.CLIPBOARD_WRITE、既定不許可)
        // 自体はBrowserCapabilityBridge側で完結しており、ここは許可された場合の実処理
        // (長押し選択コピーと全く同じ2処理)だけを行う。
        context.capabilityBridge.onClipboardWriteRequested = { text ->
            ClipboardHelper.copyToClipboard(context.activity, text)
            clipboardHistoryStore.add(text)
        }

        // navigator.clipboard.readText()。書き込みと違い、こちらはコピー履歴
        // (clipboardHistoryStore)への保存は不要(ページが読み取った内容を溜める理由が無い)。
        // 許可判定(SitePermissions.CLIPBOARD_READ、WRITEとは別の既定不許可capability。
        // 読み取りは書き込みより機微度が高いため分けてある)もBrowserCapabilityBridge側で完結。
        context.capabilityBridge.onClipboardReadRequested = { ClipboardHelper.readClipboardText(context.activity) }
    }

    override fun onSessionAttached(session: TabSession, context: EngineFeatureContext) {
        // タブ切替のたびに、ハイライト再描画の基準(zoom/scrollY)をそのタブのlayoutEngineへ
        // 差し替える(engineHost.onHtmxTrigger/onNavigateと同じ理由。詳細はEngineHostView.kt参照。
        // session.layoutEngineをクロージャで捕捉しているため、このタブが別ページへ遷移して
        // onSessionAttached()が再度呼ばれれば、常にそのタブの「今のlayoutEngine」を基準にする)。
        context.engineHost.onTextSelectionChanged = {
            context.engineFrame.updateSelectionOverlay(
                context.engineHost.textSelectionState,
                session.layoutEngine.zoomScale,
                session.layoutEngine.scrollY,
            )
        }
    }
}
