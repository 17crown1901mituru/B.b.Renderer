package com.B.b.Renderer.js

import com.B.b.Renderer.core.DirtyLevel
import com.B.b.Renderer.core.Element
import com.B.b.Renderer.style.Color
import com.B.b.Renderer.style.Display

/**
 * `element.style.xxx = ...` 相当。CSSカスケード全体を通す代わりに、
 * インラインstyle代入として最優先で直接ComputedStyleへ書き込む簡易実装。
 * 対応プロパティは頻出のもののみ。未対応プロパティへの代入は無視する。
 */
class JsStyle(private val element: Element, private val domContext: JsDomContext) {

    var backgroundColor: String
        get() = element.computedStyle.backgroundColor.toHex()
        set(value) {
            parseColor(value)?.let { color ->
                element.computedStyle = element.computedStyle.copy(backgroundColor = color)
                element.markDirty(DirtyLevel.STYLE)
                domContext.requestRedraw()
            }
        }

    var color: String
        get() = element.computedStyle.color.toHex()
        set(value) {
            parseColor(value)?.let { c ->
                element.computedStyle = element.computedStyle.copy(color = c)
                element.markDirty(DirtyLevel.STYLE)
                domContext.requestRedraw()
            }
        }

    var display: String
        get() = element.computedStyle.display.name.lowercase()
        set(value) {
            val displayValue = when (value.trim().lowercase()) {
                "none" -> Display.NONE
                "flex" -> Display.FLEX
                "inline" -> Display.INLINE
                else -> Display.BLOCK
            }
            element.computedStyle = element.computedStyle.copy(display = displayValue)
            // display変更は box model 自体に影響するため LAYOUT まで上げる
            element.markDirty(DirtyLevel.LAYOUT)
            domContext.requestRedraw()
        }

    private fun parseColor(value: String): Color? {
        val hex = value.trim().removePrefix("#")
        return when (hex.length) {
            6 -> Color(hex.substring(0, 2).toInt(16), hex.substring(2, 4).toInt(16), hex.substring(4, 6).toInt(16))
            8 -> Color(
                hex.substring(0, 2).toInt(16),
                hex.substring(2, 4).toInt(16),
                hex.substring(4, 6).toInt(16),
                hex.substring(6, 8).toInt(16),
            )
            else -> null
        }
    }
}
