package com.proterm.app.terminal

/**
 * 单个终端单元格：一个字符 + 前景/背景色 + 文本属性。
 * [widePad] 标记该格是宽字符（中文等）的第二列延续格，渲染时跳过字形绘制。
 */
data class Cell(
    var char: Char = ' ',
    var fg: Int = DEFAULT_COLOR,
    var bg: Int = DEFAULT_COLOR,
    var bold: Boolean = false,
    var underline: Boolean = false,
    var inverse: Boolean = false,
    var widePad: Boolean = false
) {
    fun copyFrom(o: Cell) {
        char = o.char
        fg = o.fg
        bg = o.bg
        bold = o.bold
        underline = o.underline
        inverse = o.inverse
        widePad = o.widePad
    }

    fun resetStyle() {
        char = ' '
        fg = DEFAULT_COLOR
        bg = DEFAULT_COLOR
        bold = false
        underline = false
        inverse = false
        widePad = false
    }

    companion object {
        const val DEFAULT_COLOR = -1
    }
}
