package com.B.b.Renderer.render

import android.content.Context
import android.graphics.Color
import android.text.InputType
import android.view.Gravity
import android.view.inputmethod.EditorInfo
import android.widget.EditText
import android.widget.FrameLayout
import androidx.core.view.ViewCompat
import androidx.core.view.WindowInsetsCompat

/**
 * 画面上部のアドレスバー。
 *
 * 従来、engineViewRoot(ページ描画領域)はedge-to-edge(targetSdk 35)の対象外として
 * あえてinsetsパディングを入れていなかった(ページ自体をシステムバーの裏まで見せたい
 * ユースケースを想定していたため)。その結果、エラーページ等の先頭テキストが
 * ステータスバーと被って読めなくなる副作用が出ていた(2026-07)。
 *
 * このビュー自体がステータスバー分のtop paddingを自前で確保しつつ、
 * 現在のURL表示 + タップして編集→Enter(Go)で遷移、というアドレスバーの役割を兼ねる
 * ことで、「ステータスバー分の余白」を無駄にせず実用的なUIとして使う。
 * engineViewRoot側のinsets非対応方針自体は変更しない。
 */
class AddressBarView(context: Context) : FrameLayout(context) {

    /** ユーザーがURLを入力してGo/Enterした時に呼ばれる。呼び出し側でnavigateForegroundTo等に繋ぐ。 */
    var onSubmit: ((String) -> Unit)? = null

    private val editText = EditText(context).apply {
        inputType = InputType.TYPE_TEXT_VARIATION_URI or InputType.TYPE_CLASS_TEXT
        imeOptions = EditorInfo.IME_ACTION_GO
        maxLines = 1
        isSingleLine = true
        textSize = 14f
        setTextColor(Color.WHITE)
        setHintTextColor(Color.parseColor("#88FFFFFF"))
        hint = "URLを入力"
        setBackgroundColor(Color.parseColor("#33FFFFFF"))
    }

    init {
        setBackgroundColor(Color.parseColor("#CC000000"))
        addView(
            editText,
            LayoutParams(LayoutParams.MATCH_PARENT, LayoutParams.WRAP_CONTENT).apply {
                gravity = Gravity.CENTER_VERTICAL
                leftMargin = dp(8)
                rightMargin = dp(8)
                topMargin = dp(4)
                bottomMargin = dp(4)
            },
        )
        editText.setPadding(dp(10), dp(6), dp(10), dp(6))

        editText.setOnEditorActionListener { _, actionId, _ ->
            if (actionId == EditorInfo.IME_ACTION_GO) {
                submitCurrentText()
                true
            } else {
                false
            }
        }

        // このビュー自身がステータスバー分のtop paddingを持つ(edge-to-edge対応)。
        // engineViewRoot側は従来通り触らない(ページがシステムバー裏まで塗られる
        // ユースケースを維持するため)。
        ViewCompat.setOnApplyWindowInsetsListener(this) { view, insets ->
            val bars = insets.getInsets(WindowInsetsCompat.Type.systemBars())
            view.setPadding(view.paddingLeft, bars.top, view.paddingRight, view.paddingBottom)
            insets
        }
    }

    private fun submitCurrentText() {
        val text = editText.text.toString().trim()
        if (text.isNotBlank()) {
            onSubmit?.invoke(normalizeUrl(text))
            editText.clearFocus()
        }
    }

    private fun normalizeUrl(input: String): String =
        if (input.contains("://")) input else "https://$input"

    /**
     * 現在のURLを表示欄に反映する。ユーザーが編集中(フォーカスあり)の場合は
     * 上書きしない(タイプ中に外部からURLが書き換わって編集内容が消えるのを防ぐ)。
     */
    fun setUrl(url: String) {
        if (!editText.hasFocus()) {
            editText.setText(url)
        }
    }

    private fun dp(value: Int): Int = (value * resources.displayMetrics.density).toInt()
}
