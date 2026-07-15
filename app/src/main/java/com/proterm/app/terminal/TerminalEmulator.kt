package com.proterm.app.terminal

import java.util.ArrayList

/**
 * 终端屏幕模型：维护 `rows x cols` 的单元格网格、光标、滚动区域、SGR 状态、
 * 主屏/替代屏缓冲，以及字符集（G0/G1 + GL 切换 + DEC 画线映射）、宽字符双格、
 * 自动换行、scrollback 历史缓冲。所有编辑原语在这里，[Vt100Parser] 只做解析分发。
 */
class TerminalEmulator(
    cols: Int,
    rows: Int,
    private val onUpdate: () -> Unit = {}
) {
    var cols: Int = cols
        private set
    var rows: Int = rows
        private set

    private var mainGrid: Array<Array<Cell>> = createGrid(cols, rows)
    private var altGrid: Array<Array<Cell>>? = null
    private var grid: Array<Array<Cell>> = mainGrid

    var cursorRow = 0
        private set
    var cursorCol = 0
        private set
    var cursorVisible = true
        private set
    var useAltScreen = false
        private set
    var autoWrap = true
        private set

    // 当前 SGR 状态
    private var curFg = Cell.DEFAULT_COLOR
    private var curBg = Cell.DEFAULT_COLOR
    private var curBold = false
    private var curUnderline = false
    private var curInverse = false

    private var scrollTop = 0
    private var scrollBottom = rows - 1

    // 字符集：0=US-ASCII, 1=DEC 画线
    private val CHARSET_US = 0
    private val CHARSET_DEC = 1
    private var g0 = CHARSET_US
    private var g1 = CHARSET_DEC
    private var gl = 0 // 当前 GL 使用的槽（0=G0, 1=G1）

    // scrollback
    private val history = ArrayList<Array<Cell>>()
    private val HISTORY_MAX = 5000

    private data class SavedCursor(
        var row: Int = 0, var col: Int = 0,
        var fg: Int = Cell.DEFAULT_COLOR, var bg: Int = Cell.DEFAULT_COLOR,
        var bold: Boolean = false, var underline: Boolean = false, var inverse: Boolean = false
    )
    private var savedMain = SavedCursor()
    private var savedAlt = SavedCursor()

    private val parser = Vt100Parser(this)

    private fun createGrid(c: Int, r: Int): Array<Array<Cell>> =
        Array(r) { Array(c) { Cell() } }

    private fun copyLine(line: Array<Cell>): Array<Cell> = line.map { it.copy() }.toTypedArray()

    // ---------- 字符集 ----------
    fun setCharset(slot: Int, dec: Boolean) {
        val v = if (dec) CHARSET_DEC else CHARSET_US
        if (slot == 0) g0 = v else if (slot == 1) g1 = v
    }
    fun setGL(slot: Int) { gl = if (slot == 0) 0 else 1 }

    /** 把可打印字节（0x20..0x7E）按当前 GL 字符集映射为 Unicode 码点 */
    fun mapByte(b: Int): Int {
        val cs = if (gl == 0) g0 else g1
        return if (cs == CHARSET_DEC && b in 0x60..0x7E) DEC_LINE[b - 0x60] else b
    }

    // DEC 特殊图形字符集：字节 0x60..0x7E -> Unicode 画线/符号
    private val DEC_LINE = intArrayOf(
        0x25C6, 0x2592, 0x2409, 0x240C, 0x240D, 0x240A, 0x00B0, 0x00B1,
        0x2424, 0x240B, 0x2518, 0x2510, 0x250C, 0x2514, 0x253C, 0x2500,
        0x2500, 0x2500, 0x2500, 0x2500, 0x251C, 0x2524, 0x2534, 0x252C,
        0x2502, 0x2264, 0x2265, 0x03C0, 0x2260, 0x00A3, 0x00B7
    )

    // ---------- 渲染视图（供 GL 渲染器读取）----------
    val totalRows: Int get() = history.size + rows
    fun getLine(absRow: Int): Array<Cell>? =
        if (absRow in 0 until history.size) history[absRow]
        else if (absRow in history.size until history.size + rows) grid[absRow - history.size]
        else null

    /** 喂入一批字节，驱动解析 */
    fun append(data: ByteArray, offset: Int = 0, length: Int = data.size - offset) {
        parser.execute(data, offset, length)
        onUpdate()
    }

    // ---------- 写原语 ----------
    fun putChar(code: Int) {
        val w = wcwidth(code)
        if (w <= 0) return
        if (autoWrap && cursorCol >= cols) { newline(); cursorCol = 0 }
        if (cursorCol + w > cols) { newline(); cursorCol = 0 }
        val cell = grid[cursorRow][cursorCol]
        cell.char = code.toChar()
        cell.fg = curFg; cell.bg = curBg
        cell.bold = curBold; cell.underline = curUnderline; cell.inverse = curInverse
        cell.widePad = false
        cursorCol++
        if (w == 2) {
            if (cursorCol >= cols) { newline(); cursorCol = 0 }
            else {
                val pad = grid[cursorRow][cursorCol]
                pad.resetStyle(); pad.char = ' '; pad.widePad = true
                cursorCol++
            }
        }
        if (cursorCol >= cols && autoWrap) { newline(); cursorCol = 0 }
    }

    fun newline() {
        cursorCol = 0
        if (cursorRow == scrollBottom) scrollUp(1)
        else if (cursorRow < rows - 1) cursorRow++
    }

    fun carriageReturn() { cursorCol = 0 }
    fun backspace() { if (cursorCol > 0) cursorCol-- }
    fun tab() {
        cursorCol = ((cursorCol / 8) + 1) * 8
        if (cursorCol >= cols) cursorCol = cols - 1
    }

    fun moveCursor(dx: Int, dy: Int) {
        cursorCol = (cursorCol + dx).coerceIn(0, cols - 1)
        cursorRow = (cursorRow + dy).coerceIn(0, rows - 1)
    }

    fun setCursor(row: Int, col: Int) {
        cursorRow = row.coerceIn(0, rows - 1)
        cursorCol = col.coerceIn(0, cols - 1)
    }

    fun reverseIndex() {
        if (cursorRow == scrollTop) scrollDown(1)
        else if (cursorRow > 0) cursorRow--
    }

    fun eraseInDisplay(mode: Int) {
        when (mode) {
            0 -> { eraseRect(cursorRow, cursorCol, cursorRow, cols - 1); eraseRect(cursorRow + 1, 0, scrollBottom, cols - 1) }
            1 -> eraseRect(scrollTop, 0, cursorRow, cursorCol)
            2 -> eraseRect(scrollTop, 0, scrollBottom, cols - 1)
        }
    }

    fun eraseInLine(mode: Int) {
        when (mode) {
            0 -> eraseRect(cursorRow, cursorCol, cursorRow, cols - 1)
            1 -> eraseRect(cursorRow, 0, cursorRow, cursorCol)
            2 -> eraseRect(cursorRow, 0, cursorRow, cols - 1)
        }
    }

    fun eraseChars(n: Int) {
        val nn = n.coerceAtLeast(1)
        for (c in cursorCol until minOf(cursorCol + nn, cols)) grid[cursorRow][c].resetStyle()
    }

    fun insertChars(n: Int) {
        val nn = n.coerceAtLeast(1)
        val line = grid[cursorRow]
        for (c in (cols - 1) downTo (cursorCol + nn)) line[c].copyFrom(line[c - nn])
        for (c in cursorCol until minOf(cursorCol + nn, cols)) line[c].resetStyle()
    }

    fun deleteChars(n: Int) {
        val nn = n.coerceAtLeast(1)
        val line = grid[cursorRow]
        for (c in cursorCol until cols - nn) line[c].copyFrom(line[c + nn])
        for (c in (cols - nn) until cols) line[c].resetStyle()
    }

    fun insertLines(n: Int) {
        val nn = n.coerceAtLeast(1)
        for (i in 0 until nn) {
            System.arraycopy(grid, cursorRow, grid, cursorRow + 1, scrollBottom - cursorRow)
            grid[cursorRow] = Array(cols) { Cell() }
        }
    }

    fun deleteLines(n: Int) {
        val nn = n.coerceAtLeast(1)
        for (i in 0 until nn) {
            System.arraycopy(grid, cursorRow + 1, grid, cursorRow, scrollBottom - cursorRow)
            grid[scrollBottom] = Array(cols) { Cell() }
        }
    }

    private fun eraseRect(r0: Int, c0: Int, r1: Int, c1: Int) {
        for (r in r0.coerceIn(0, rows - 1)..r1.coerceIn(0, rows - 1))
            for (c in c0.coerceIn(0, cols - 1)..c1.coerceIn(0, cols - 1))
                grid[r][c].resetStyle()
    }

    fun scrollUp(n: Int) {
        val nn = n.coerceAtLeast(1)
        for (i in 0 until nn) {
            if (scrollTop == 0) {
                history.add(copyLine(grid[0]))
                while (history.size > HISTORY_MAX) history.removeAt(0)
            }
            System.arraycopy(grid, scrollTop + 1, grid, scrollTop, scrollBottom - scrollTop)
            grid[scrollBottom] = Array(cols) { Cell() }
        }
    }

    fun scrollDown(n: Int) {
        val nn = n.coerceAtLeast(1)
        for (i in 0 until nn) {
            System.arraycopy(grid, scrollTop, grid, scrollTop + 1, scrollBottom - scrollTop)
            grid[scrollTop] = Array(cols) { Cell() }
        }
    }

    fun setScrollRegion(top: Int, bottom: Int) {
        scrollTop = top.coerceIn(0, rows - 1)
        scrollBottom = bottom.coerceIn(0, rows - 1)
        if (scrollBottom < scrollTop) { scrollTop = 0; scrollBottom = rows - 1 }
        setCursor(0, 0)
    }

    fun clearScreen() {
        for (r in 0 until rows) for (c in 0 until cols) grid[r][c].resetStyle()
        cursorRow = 0; cursorCol = 0
    }

    // ---------- SGR ----------
    fun setGraphics(params: IntArray) {
        if (params.isEmpty()) { applySgr(0); return }
        var i = 0
        while (i < params.size) {
            val p = params[i]
            when {
                p == 0 -> applySgr(0)
                p == 1 -> curBold = true
                p == 4 -> curUnderline = true
                p == 7 -> curInverse = true
                p == 22 -> curBold = false
                p == 24 -> curUnderline = false
                p == 27 -> curInverse = false
                p == 39 -> curFg = Cell.DEFAULT_COLOR
                p == 49 -> curBg = Cell.DEFAULT_COLOR
                p in 30..37 -> curFg = AnsiColors.fg(p - 30)
                p in 40..47 -> curBg = AnsiColors.fg(p - 40)
                p in 90..97 -> curFg = AnsiColors.brightFg(p - 90)
                p in 100..107 -> curBg = AnsiColors.brightFg(p - 100)
                p == 38 -> { val (col, ni) = parseExt(params, i); curFg = col; i = ni }
                p == 48 -> { val (col, ni) = parseExt(params, i); curBg = col; i = ni }
            }
            i++
        }
    }

    private fun applySgr(p: Int) {
        if (p == 0) {
            curFg = Cell.DEFAULT_COLOR; curBg = Cell.DEFAULT_COLOR
            curBold = false; curUnderline = false; curInverse = false
        }
    }

    private fun parseExt(params: IntArray, start: Int): Pair<Int, Int> {
        if (start + 1 >= params.size) return Cell.DEFAULT_COLOR to start
        return when (params[start + 1]) {
            5 -> AnsiColors.palette(params.getOrElse(start + 2) { 0 }) to start + 2
            2 -> {
                val r = params.getOrElse(start + 2) { 0 }
                val g = params.getOrElse(start + 3) { 0 }
                val b = params.getOrElse(start + 4) { 0 }
                ((0xFF shl 24) or (r shl 16) or (g shl 8) or b) to start + 4
            }
            else -> Cell.DEFAULT_COLOR to start + 1
        }
    }

    // ---------- 光标保存/恢复 ----------
    fun saveCursor() {
        val s = if (useAltScreen) savedAlt else savedMain
        s.row = cursorRow; s.col = cursorCol
        s.fg = curFg; s.bg = curBg; s.bold = curBold; s.underline = curUnderline; s.inverse = curInverse
    }

    fun restoreCursor() {
        val s = if (useAltScreen) savedAlt else savedMain
        cursorRow = s.row; cursorCol = s.col
        curFg = s.fg; curBg = s.bg; curBold = s.bold; curUnderline = s.underline; curInverse = s.inverse
    }

    fun setCursorVisible(v: Boolean) { cursorVisible = v }
    fun setAutoWrap(v: Boolean) { autoWrap = v }

    fun setAltScreen(on: Boolean) {
        if (on == useAltScreen) return
        if (on) {
            saveCursor()
            altGrid = createGrid(cols, rows)
            grid = altGrid!!
            clearScreen()
            useAltScreen = true
        } else {
            grid = mainGrid
            useAltScreen = false
            restoreCursor()
        }
    }

    fun resize(newCols: Int, newRows: Int) {
        if (newCols == cols && newRows == rows) return
        mainGrid = rebuild(mainGrid, newCols, newRows)
        altGrid?.let { altGrid = rebuild(it, newCols, newRows) }
        grid = if (useAltScreen) (altGrid ?: mainGrid) else mainGrid
        cols = newCols; rows = newRows
        scrollTop = 0; scrollBottom = newRows - 1
        cursorRow = cursorRow.coerceIn(0, newRows - 1)
        cursorCol = cursorCol.coerceIn(0, newCols - 1)
        history.clear()
        onUpdate()
    }

    private fun rebuild(old: Array<Array<Cell>>, nc: Int, nr: Int): Array<Array<Cell>> {
        val ng = createGrid(nc, nr)
        val cr = minOf(old.size, nr)
        val cc = minOf(old[0].size, nc)
        for (r in 0 until cr) for (c in 0 until cc) ng[r][c].copyFrom(old[r][c])
        return ng
    }

    /** 粗略 Unicode 显示宽度（中文/全角=2，其余=1） */
    fun wcwidth(c: Int): Int {
        if (c == 0) return 0
        if (c in 0x1100..0x115F) return 2
        if (c in 0x2E80..0x303E) return 2
        if (c in 0x3041..0x33FF) return 2
        if (c in 0x3400..0x4DBF) return 2
        if (c in 0x4E00..0x9FFF) return 2
        if (c in 0xA000..0xA4CF) return 2
        if (c in 0xAC00..0xD7A3) return 2
        if (c in 0xF900..0xFAFF) return 2
        if (c in 0xFF00..0xFF60) return 2
        if (c in 0xFFE0..0xFFE6) return 2
        if (c >= 0x20000 && c <= 0x3FFFD) return 2
        return 1
    }
}
