package com.B.b.Renderer.style

data class ComputedStyle(
    // --- 継承されるプロパティ ---
    val color: Color = Color.BLACK,
    val fontSize: Float = 16f,
    val fontWeight: Int = 400,
    val textAlign: TextAlign = TextAlign.LEFT,
    val pointerEvents: PointerEvents = PointerEvents.AUTO,

    // --- 継承されないプロパティ ---
    val display: Display = Display.BLOCK,
    val position: Position = Position.STATIC,
    val width: CssValue = CssValue.Auto,
    val height: CssValue = CssValue.Auto,
    val margin: BoxEdges = BoxEdges.ZERO,
    val padding: BoxEdges = BoxEdges.ZERO,
    val backgroundColor: Color = Color.TRANSPARENT,
    val zIndex: Int? = null,
) {
    fun inheritableSubset(): ComputedStyle = ComputedStyle(
        color = color,
        fontSize = fontSize,
        fontWeight = fontWeight,
        textAlign = textAlign,
        pointerEvents = pointerEvents,
        // 非継承プロパティは意図的にデフォルト値へリセット
    )
}

enum class Display { BLOCK, INLINE, FLEX, NONE }
enum class Position { STATIC, RELATIVE, ABSOLUTE, FIXED, STICKY }
enum class TextAlign { LEFT, CENTER, RIGHT }
enum class PointerEvents { AUTO, NONE }

sealed class CssValue {
    object Auto : CssValue()
    data class Px(val value: Float) : CssValue()
    data class Percent(val value: Float) : CssValue()
}

data class BoxEdges(val top: Float, val right: Float, val bottom: Float, val left: Float) {
    companion object {
        val ZERO = BoxEdges(0f, 0f, 0f, 0f)
    }
}

data class Color(val r: Int, val g: Int, val b: Int, val a: Int = 255) {
    companion object {
        val BLACK = Color(0, 0, 0)
        val WHITE = Color(255, 255, 255)
        val TRANSPARENT = Color(0, 0, 0, 0)
    }

    fun toHex(): String = String.format("#%02X%02X%02X%02X", r, g, b, a)
}
