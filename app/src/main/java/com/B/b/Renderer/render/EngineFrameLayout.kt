package com.B.b.Renderer.render

import android.content.Context
import android.graphics.Color
import android.util.TypedValue
import android.view.Gravity
import android.view.View
import android.view.ViewGroup
import android.widget.FrameLayout
import android.widget.LinearLayout
import android.widget.TextView
import androidx.core.view.ViewCompat
import androidx.core.view.WindowInsetsCompat
import androidx.drawerlayout.widget.DrawerLayout

/**
 * sp(=density×fontScale)ではなく、density基準の固定pxでテキストサイズを指定する。
 * 詳細な経緯はDebugDrawerView.ktの同名ヘルパーのコメント参照。
 */
private fun TextView.setFixedTextSize(spValue: Float) {
    setTextSize(TypedValue.COMPLEX_UNIT_PX, spValue * resources.displayMetrics.density)
}

/**
 * B.b.Rendererの「画面の組み立て」だけを担当するクラス。
 *
 * 責務の境界:
 *   - ここが持つのは、Android Viewの配置・重なり順・システムバー分のinsets処理のみ。
 *     どのURLを開くか、タブをどう切り替えるかといったナビゲーションロジックは
 *     一切持たない(それはEngineActivity側の責務のまま)。
 *   - ページ内DOM(<div>や<h1>等)のbox model計算はlayout/LayoutEngine.ktが担当する、
 *     全く別のレイヤー。このクラスは「Androidのネイティブ画面部品(アドレスバー・
 *     PiP枠・ローディング表示・ドロワー)をどこに置くか」だけを扱う
 *     (2026-07、EngineActivity肥大化への対応として切り出し。命名時、
 *     Google Chromeを連想させないよう「chrome」という語は避けた)。
 *
 * 使い方(EngineActivity側):
 *   1. RendererFactory.create(context)で作ったページ描画View(GPU/Canvas)を
 *      contentViewとして渡してこのクラスを生成する
 *   2. addressBarView.onSubmit にナビゲーション処理を、必要ならloadingIndicator/
 *      pipContainerを直接操作する
 *   3. ドロワー(DebugDrawerView等)は別途Activity側で組み立て、
 *      attachEndDrawer(...)でこのクラスに差し込む
 *   4. setContentView(このインスタンス) を呼ぶ(DrawerLayoutを継承しているため
 *      そのままcontentViewにできる)
 */
class EngineFrameLayout(
    context: Context,
    private val contentView: View,
) : DrawerLayout(context) {

    /** URL表示/入力欄。ステータスバー分の余白を兼ねる(2026-07、白画面バグ調査時の副産物)。 */
    val addressBarView: AddressBarView = AddressBarView(context)

    /** pinned+showAsPipなタブの小窓を並べるコンテナ。中身の追加/削除はActivity側が行う。 */
    val pipContainer: LinearLayout = LinearLayout(context).apply {
        orientation = LinearLayout.VERTICAL
    }

    /** 読み込み中インジケーター(画面上部の細い帯)。表示/非表示はActivity側が制御する。 */
    val loadingIndicator: View = View(context).apply {
        setBackgroundColor(Color.parseColor("#2196F3"))
        visibility = View.GONE
    }

    /** 右下のドロワー開閉トグルボタン。 */
    val drawerToggleButton: TextView = TextView(context).apply {
        text = "☰"
        setFixedTextSize(16f)
        setTextColor(Color.WHITE)
        setBackgroundColor(Color.parseColor("#88000000"))
    }

    private val mainContainer: FrameLayout = FrameLayout(context)

    init {
        drawerToggleButton.setPadding(dp(10), dp(6), dp(10), dp(6))
        drawerToggleButton.setOnClickListener { toggleEndDrawer() }

        mainContainer.apply {
            addView(contentView, FrameLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.MATCH_PARENT))
            addView(
                addressBarView,
                FrameLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT).apply {
                    gravity = Gravity.TOP
                },
            )
            addView(
                pipContainer,
                FrameLayout.LayoutParams(ViewGroup.LayoutParams.WRAP_CONTENT, ViewGroup.LayoutParams.WRAP_CONTENT).apply {
                    gravity = Gravity.TOP or Gravity.END
                },
            )
            addView(
                loadingIndicator,
                FrameLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, dp(3)).apply {
                    gravity = Gravity.TOP
                },
            )
            addView(
                drawerToggleButton,
                FrameLayout.LayoutParams(ViewGroup.LayoutParams.WRAP_CONTENT, ViewGroup.LayoutParams.WRAP_CONTENT).apply {
                    gravity = Gravity.BOTTOM or Gravity.END
                    setMargins(0, 0, dp(12), dp(12))
                },
            )
        }

        addView(mainContainer, DrawerLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.MATCH_PARENT))

        applyChromeInsets()
    }

    /**
     * システムバー(ステータスバー/ナビゲーションバー)分のinsetsを、
     * 画面部品それぞれに適切に反映する。targetSdk 35のedge-to-edge対応。
     *
     * - contentView(ページ描画領域)は、アドレスバーの高さ分だけ上を空ける。
     *   これを入れないと、ページ内容がアドレスバーの真裏(同じy=0起点)に
     *   隠れて見えなくなる(2026-07、RENDER_DIAGログで描画自体は成功して
     *   いるのに画面が白く見えるという不具合の実際の原因だった)。
     * - loadingIndicatorも同様にアドレスバーの下に表示する。
     * - drawerToggleButtonはナビゲーションバーに埋もれないよう下マージンを足す。
     *
     * アドレスバーの高さは実測せず、固定値(ADDRESS_BAR_HEIGHT_DP)で近似している。
     * フォントサイズや余白を変更した場合はここも合わせて調整すること。
     */
    private fun applyChromeInsets() {
        ViewCompat.setOnApplyWindowInsetsListener(contentView) { view, insets ->
            val bars = insets.getInsets(WindowInsetsCompat.Type.systemBars())
            val params = view.layoutParams as FrameLayout.LayoutParams
            params.topMargin = bars.top + dp(ADDRESS_BAR_HEIGHT_DP)
            view.layoutParams = params
            insets
        }

        ViewCompat.setOnApplyWindowInsetsListener(loadingIndicator) { view, insets ->
            val bars = insets.getInsets(WindowInsetsCompat.Type.systemBars())
            val params = view.layoutParams as FrameLayout.LayoutParams
            params.topMargin = bars.top + dp(ADDRESS_BAR_HEIGHT_DP)
            view.layoutParams = params
            insets
        }

        ViewCompat.setOnApplyWindowInsetsListener(drawerToggleButton) { view, insets ->
            val bars = insets.getInsets(WindowInsetsCompat.Type.systemBars())
            val params = view.layoutParams as FrameLayout.LayoutParams
            params.bottomMargin = dp(12) + bars.bottom
            params.rightMargin = dp(12) + bars.right
            view.layoutParams = params
            insets
        }
    }

    /**
     * endドロワー(現状はDebugDrawerView)をこのフレームに差し込む。
     * ドロワー自身にはシステムバー分の上下パディングを入れる
     * (ドロワー内の先頭要素がステータスバーに被らないようにするため)。
     *
     * @param drawerView 差し込むドロワーの中身のView
     * @param onOpened ドロワーが開いた時のコールバック(自動更新の開始等に使う想定)
     * @param onClosed ドロワーが閉じた時のコールバック
     */
    fun attachEndDrawer(drawerView: View, onOpened: () -> Unit, onClosed: () -> Unit) {
        val params = DrawerLayout.LayoutParams(ViewGroup.LayoutParams.WRAP_CONTENT, ViewGroup.LayoutParams.MATCH_PARENT)
        params.gravity = Gravity.END
        addView(drawerView, params)

        addDrawerListener(object : SimpleDrawerListener() {
            override fun onDrawerOpened(view: View) = onOpened()
            override fun onDrawerClosed(view: View) = onClosed()
        })

        ViewCompat.setOnApplyWindowInsetsListener(drawerView) { view, insets ->
            val bars = insets.getInsets(WindowInsetsCompat.Type.systemBars())
            view.setPadding(view.paddingLeft, bars.top, view.paddingRight, bars.bottom)
            insets
        }
    }

    /** endドロワーの開閉をトグルする。 */
    fun toggleEndDrawer() {
        if (isDrawerOpen(Gravity.END)) {
            closeDrawer(Gravity.END)
        } else {
            openDrawer(Gravity.END)
        }
    }

    private fun dp(value: Int): Int = (value * resources.displayMetrics.density).toInt()

    companion object {
        /** アドレスバーの概算高さ(dp)。実測ではなく固定値で近似している。
         *  EngineActivity側でLayoutEngineのviewportHeight算出にも同じ値を使うため、
         *  ここだけの秘密にせずconstとして公開している(2026-08対応)。 */
        const val ADDRESS_BAR_HEIGHT_DP = 48
    }
}
