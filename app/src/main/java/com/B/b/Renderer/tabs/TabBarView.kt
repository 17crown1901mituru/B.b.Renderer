package com.B.b.Renderer.tabs

import android.content.Context
import android.graphics.Color
import android.view.Gravity
import android.view.ViewGroup
import android.widget.Button
import android.widget.HorizontalScrollView
import android.widget.LinearLayout
import android.widget.TextView

/**
 * 画面上部の簡易タブバー。各タブに切替/ピン留めトグル/閉じるを持たせている。
 * PiP表示のON/OFFはピン留め済みタブに対してのみ意味を持つため、ピンが付いている
 * タブだけ「窓」ボタン(PiP表示切替)を追加で出す。
 */
class TabBarView(
    context: Context,
    private val tabManager: TabManager,
    private val onTabChanged: () -> Unit,
) : HorizontalScrollView(context) {

    private val row = LinearLayout(context).apply {
        orientation = LinearLayout.HORIZONTAL
        gravity = Gravity.CENTER_VERTICAL
    }

    init {
        addView(row, ViewGroup.LayoutParams(ViewGroup.LayoutParams.WRAP_CONTENT, ViewGroup.LayoutParams.WRAP_CONTENT))
        setBackgroundColor(Color.parseColor("#222222"))
    }

    fun refresh() {
        row.removeAllViews()
        tabManager.allTabIds().forEach { id ->
            row.addView(buildTabChip(id))
        }
        row.addView(
            Button(context).apply {
                text = "+"
                textSize = 14f
                setOnClickListener {
                    onNewTabRequested?.invoke()
                }
            },
        )
    }

    var onNewTabRequested: (() -> Unit)? = null

    private fun buildTabChip(id: Long): LinearLayout {
        val isForeground = tabManager.foregroundId == id
        val chip = LinearLayout(context).apply {
            orientation = LinearLayout.HORIZONTAL
            gravity = Gravity.CENTER_VERTICAL
            setBackgroundColor(if (isForeground) Color.parseColor("#444444") else Color.parseColor("#222222"))
            setPadding(dp(6), dp(4), dp(6), dp(4))
        }
        chip.addView(
            TextView(context).apply {
                text = tabManager.titleOf(id).take(16)
                setTextColor(Color.WHITE)
                textSize = 11f
                setPadding(dp(4), 0, dp(4), 0)
                setOnClickListener {
                    onTabSelected?.invoke(id)
                }
            },
        )
        if (tabManager.isPinned(id)) {
            chip.addView(
                smallLabelButton(if (tabManager.isShownAsPip(id)) "窓✓" else "窓") {
                    onPipToggleRequested?.invoke(id, !tabManager.isShownAsPip(id))
                },
            )
        }
        chip.addView(
            smallLabelButton(if (tabManager.isPinned(id)) "📌✓" else "📌") {
                onPinToggleRequested?.invoke(id, !tabManager.isPinned(id))
            },
        )
        chip.addView(
            smallLabelButton("×") {
                onCloseRequested?.invoke(id)
            },
        )
        return chip
    }

    var onTabSelected: ((Long) -> Unit)? = null
    var onPinToggleRequested: ((Long, Boolean) -> Unit)? = null
    var onPipToggleRequested: ((Long, Boolean) -> Unit)? = null
    var onCloseRequested: ((Long) -> Unit)? = null

    private fun smallLabelButton(label: String, onClick: () -> Unit) =
        TextView(context).apply {
            text = label
            setTextColor(Color.LTGRAY)
            textSize = 10f
            setPadding(dp(4), 0, dp(4), 0)
            setOnClickListener { onClick() }
        }

    private fun dp(value: Int): Int =
        (value * resources.displayMetrics.density).toInt()
}
