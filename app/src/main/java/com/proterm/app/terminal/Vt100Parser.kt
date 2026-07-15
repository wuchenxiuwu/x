package com.proterm.app.terminal

/**
 * VT100 / ANSI 转义序列解析状态机，逐字节消费并把语义动作分发到 [TerminalEmulator]。
 *
 * 支持实用子集：
 *  - C0 控制字符（BS/HT/LF/VT/FF/CR/NUL/BEL/SO/SI）
 *  - ESC 引入序列：字符集声明（ESC ( / ) / * / + 后跟 B/0）、RI/D/IND/NEL/DECSC/DECRC/RIS、SO=G1/SI=G0
 *  - CSI：光标移动、ED/EL、CUP/HPA/VPA、IL/DL/ICH/DCH/ECH、DECSTBM、SGR、DECSET/DECRST（?7/?25/?1049 等）
 *  - OSC（标题等，忽略内容）
 *  - UTF-8 多字节解码（中文/全角宽度由 emulator.wcwidth 决定，经字符集映射后上屏）
 *  - 可打印字节（0x20..0x7E）先经 emulator.mapByte 做字符集映射，使 G0=DEC 时正确输出画线字符
 */
class Vt100Parser(private val em: TerminalEmulator) {

    private val STATE_GROUND = 0
    private val STATE_ESC = 1
    private val STATE_CSI = 2
    private val STATE_OSC = 3
    private val STATE_CHARSET = 4

    private var state = STATE_GROUND
    private val params = mutableListOf<Int>()
    private var curParam = 0
    private var privateMarker = 0
    private var finalByte = 0
    private val oscBuf = StringBuilder()
    private var charsetTarget = 0

    private var utf8Need = 0
    private var utf8Code = 0

    fun execute(data: ByteArray, offset: Int, length: Int) {
        val end = offset + length
        var i = offset
        while (i < end) {
            handle(data[i].toInt() and 0xFF)
            i++
        }
    }

    private fun handle(b: Int) {
        // 1) UTF-8 续字节 / 多字节起始
        if (utf8Need > 0 && b and 0xC0 == 0x80) {
            utf8Code = (utf8Code shl 6) or (b and 0x3F)
            if (--utf8Need == 0) feedChar(utf8Code)
            return
        }
        if (b >= 0x80) {
            when {
                b and 0xE0 == 0xC0 -> { utf8Need = 1; utf8Code = b and 0x1F }
                b and 0xF0 == 0xE0 -> { utf8Need = 2; utf8Code = b and 0x0F }
                b and 0xF8 == 0xF0 -> { utf8Need = 3; utf8Code = b and 0x07 }
                else -> feedChar(b)
            }
            return
        }

        // 2) ASCII 范围，按状态分发
        when (state) {
            STATE_GROUND -> ground(b)
            STATE_ESC -> esc(b)
            STATE_CSI -> csi(b)
            STATE_OSC -> osc(b)
            STATE_CHARSET -> charset(b)
        }
    }

    private fun feedChar(code: Int) {
        if (state == STATE_GROUND) {
            if (code < 0x20) control(code)
            else em.putChar(code)
        }
    }

    private fun control(code: Int) {
        when (code) {
            0x00 -> {}
            0x07 -> {}
            0x08 -> em.backspace()
            0x09 -> em.tab()
            0x0A, 0x0B, 0x0C -> { em.carriageReturn(); em.newline() }
            0x0D -> em.carriageReturn()
            0x0E -> em.setGL(1) // SO: 切 GL=G1
            0x0F -> em.setGL(0) // SI: 切 GL=G0
            0x1B -> { state = STATE_ESC; params.clear(); curParam = 0; privateMarker = 0 }
        }
    }

    private fun ground(b: Int) {
        if (b == 0x1B) {
            state = STATE_ESC; params.clear(); curParam = 0; privateMarker = 0
        } else if (b < 0x20) {
            control(b)
        } else if (b in 0x20..0x7E) {
            feedChar(em.mapByte(b))
        } else {
            em.putChar(b.toChar())
        }
    }

    private fun esc(b: Int) {
        when (b) {
            0x1B -> { params.clear(); curParam = 0 }
            '['.code -> { state = STATE_CSI; params.clear(); curParam = 0; privateMarker = 0 }
            ']'.code -> { state = STATE_OSC; oscBuf.clear() }
            '('.code -> { charsetTarget = 0; state = STATE_CHARSET }
            ')'.code -> { charsetTarget = 1; state = STATE_CHARSET }
            '*'.code -> { charsetTarget = 2; state = STATE_CHARSET }
            '+'.code -> { charsetTarget = 3; state = STATE_CHARSET }
            '='.code -> state = STATE_GROUND // keypad/old LNM 标记，忽略
            '>'.code -> state = STATE_GROUND
            'M'.code -> em.reverseIndex()
            'D'.code -> em.newline()
            'E'.code -> { em.carriageReturn(); em.newline() }
            '7'.code -> em.saveCursor()
            '8'.code -> em.restoreCursor()
            'c'.code -> em.clearScreen()
            else -> state = STATE_GROUND
        }
    }

    private fun charset(b: Int) {
        when (b) {
            'B'.code -> em.setCharset(charsetTarget, false) // US-ASCII
            '0'.code -> em.setCharset(charsetTarget, true)  // DEC 画线
            else -> em.setCharset(charsetTarget, false)     // U/K/其它按 US
        }
        state = STATE_GROUND
    }

    private fun csi(b: Int) {
        when {
            b in 0x30..0x39 -> curParam = curParam * 10 + (b - 0x30)
            b == 0x3B -> { params.add(curParam); curParam = 0 }
            b in 0x3C..0x3F -> if (params.isEmpty() && curParam == 0) privateMarker = b
            b in 0x20..0x2F -> { /* 中间字节，忽略 */ }
            b in 0x40..0x7E -> { finalByte = b; dispatchCsi() }
            else -> state = STATE_GROUND
        }
    }

    private fun osc(b: Int) {
        when (b) {
            0x07 -> state = STATE_GROUND
            0x1B -> state = STATE_ESC
            else -> oscBuf.append(b.toChar())
        }
    }

    private fun max1(v: Int) = if (v <= 0) 1 else v

    private fun dispatchCsi() {
        if (curParam != 0 || params.isNotEmpty()) params.add(curParam)
        else if (params.isEmpty()) params.add(0)
        val p = params.toIntArray()
        val q = privateMarker == '?'.code
        val f = finalByte.toChar()
        when (f) {
            'A' -> em.moveCursor(0, -max1(p[0]))
            'B' -> em.moveCursor(0, max1(p[0]))
            'C' -> em.moveCursor(max1(p[0]), 0)
            'D' -> em.moveCursor(-max1(p[0]), 0)
            'E' -> em.setCursor(em.cursorRow + max1(p[0]), 0)
            'F' -> em.setCursor(em.cursorRow - max1(p[0]), 0)
            'G', '`' -> em.setCursor(em.cursorRow, max1(p[0]) - 1)
            'd' -> em.setCursor(max1(p[0]) - 1, em.cursorCol)
            'H', 'f' -> em.setCursor(max1(p[0]) - 1, max1(p.getOrElse(1) { 1 }) - 1)
            'J' -> em.eraseInDisplay(p[0])
            'K' -> em.eraseInLine(p[0])
            'L' -> em.insertLines(max1(p[0]))
            'M' -> em.deleteLines(max1(p[0]))
            '@' -> em.insertChars(max1(p[0]))
            'P' -> em.deleteChars(max1(p[0]))
            'X' -> em.eraseChars(max1(p[0]))
            'r' -> {
                val t = max1(p[0]) - 1
                val b = if (p.size > 1) max1(p[1]) - 1 else em.rows - 1
                em.setScrollRegion(t.coerceIn(0, em.rows - 1), b.coerceIn(0, em.rows - 1))
            }
            'm' -> em.setGraphics(p)
            'h', 'l' -> handleMode(q, f == 'h', p)
        }
    }

    private fun handleMode(q: Boolean, set: Boolean, p: IntArray) {
        if (!q) return
        for (m in p) {
            when (m) {
                7 -> em.setAutoWrap(set)        // ?7 DECAWM 自动换行
                25 -> em.setCursorVisible(set)  // ?25 光标可见
                47, 1047, 1049 -> em.setAltScreen(set)
            }
        }
    }
}
