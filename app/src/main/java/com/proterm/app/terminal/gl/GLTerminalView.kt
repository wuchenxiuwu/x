package com.proterm.app.terminal.gl

import android.content.Context
import android.opengl.GLSurfaceView
import android.view.*
import android.view.inputmethod.EditorInfo
import android.view.inputmethod.InputConnection
import android.view.inputmethod.InputMethodManager
import com.proterm.app.terminal.TerminalEmulator
import com.proterm.app.terminal.TerminalSession

/**
 * 基于 OpenGL ES 的终端视图（GLSurfaceView 子类）：
 *  - 用 [TerminalRenderer] 走 GPU 批量绘制；
 *  - 软键盘 + 硬件键盘输入统一转成 PTY 字节流（方向键走 ESC [ A/B/C/D）；
 *  - 尺寸变化按字体 cell 大小算出 cols/rows 并通知会话 resize（同步 kernel PTY + 本地 emulator）；
 *  - 触摸上下拖拽回看 scrollback 历史，轻点唤起软键盘。
 */
class GLTerminalView @JvmOverloads constructor(
    context: Context,
    attrs: AttributeSet? = null
) : GLSurfaceView(context, attrs), TerminalRenderer.SizeListener {

    private var emulator: TerminalEmulator? = null
    private var session: TerminalSession? = null
    private lateinit var renderer: TerminalRenderer

    private var lastCols = -1
    private var lastRows = -1
    private var viewOffset = 0

    private var lastTouchY = 0f
    private var touchMoved = false

    fun setup(emulator: TerminalEmulator, session: TerminalSession) {
        this.emulator = emulator
        this.session = session
        val density = resources.displayMetrics.density
        val fontSize = 14f * density
        setEGLContextClientVersion(2)
        setEGLConfigChooser(8, 8, 8, 8, 0, 0)
        renderer = TerminalRenderer(emulator, fontSize)
        renderer.sizeListener = this
        setRenderer(renderer)
        renderMode = RENDERMODE_CONTINUOUSLY
    }

    override fun onSize(w: Int, h: Int) {
        val cw = renderer.getCellW()
        val ch = renderer.getCellH()
        if (cw <= 0 || ch <= 0) return
        val cols = maxOf(1, (w / cw).toInt())
        val rows = maxOf(1, (h / ch).toInt())
        if (cols != lastCols || rows != lastRows) {
            lastCols = cols
            lastRows = rows
            session?.resize(cols, rows)
        }
    }

    // ---------- 输入 ----------
    override fun onCheckIsTextEditor(): Boolean = true

    override fun onCreateInputConnection(outAttrs: EditorInfo): InputConnection {
        outAttrs.inputType = EditorInfo.TYPE_NULL
        outAttrs.imeOptions = EditorInfo.IME_FLAG_NO_EXTRACT_UI
        return TerminalInputConnection(this)
    }

    private class TerminalInputConnection(view: GLTerminalView) : BaseInputConnection(view, true) {
        private val v = view
        override fun commitText(text: CharSequence?, newCursorPos: Int): Boolean {
            text?.let { v.sendText(it.toString()) }
            return true
        }
        override fun sendKeyEvent(event: KeyEvent?): Boolean {
            event?.let { if (it.action == KeyEvent.ACTION_DOWN) v.dispatchKey(it) }
            return true
        }
        override fun deleteSurroundingText(inStart: Int, inEnd: Int): Boolean {
            v.dispatchKey(KeyEvent(0, 0, KeyEvent.ACTION_DOWN, KeyEvent.KEYCODE_DEL, 0))
            return true
        }
        override fun deleteSurroundingTextInCodePoints(inStart: Int, inEnd: Int): Boolean {
            v.dispatchKey(KeyEvent(0, 0, KeyEvent.ACTION_DOWN, KeyEvent.KEYCODE_DEL, 0))
            return true
        }
    }

    override fun onKeyDown(keyCode: Int, event: KeyEvent): Boolean {
        if (event.action == KeyEvent.ACTION_DOWN && dispatchKey(event)) return true
        return super.onKeyDown(keyCode, event)
    }

    /** @return 是否消费了该按键 */
    fun dispatchKey(ev: KeyEvent): Boolean {
        val s = session ?: return false
        when (ev.keyCode) {
            KeyEvent.KEYCODE_ENTER -> { s.writeByte(0x0D); return true }
            KeyEvent.KEYCODE_DEL -> { s.writeByte(0x7F); return true }
            KeyEvent.KEYCODE_FORWARD_DEL -> { s.writeByte(0x04); return true }
            KeyEvent.KEYCODE_TAB -> { s.writeByte(0x09); return true }
            KeyEvent.KEYCODE_ESCAPE -> { s.writeByte(0x1B); return true }
            KeyEvent.KEYCODE_DPAD_UP -> { escSeq('A'); return true }
            KeyEvent.KEYCODE_DPAD_DOWN -> { escSeq('B'); return true }
            KeyEvent.KEYCODE_DPAD_RIGHT -> { escSeq('C'); return true }
            KeyEvent.KEYCODE_DPAD_LEFT -> { escSeq('D'); return true }
            KeyEvent.KEYCODE_HOME, KeyEvent.KEYCODE_MOVE_HOME -> { escSeq('H'); return true }
            KeyEvent.KEYCODE_END, KeyEvent.KEYCODE_MOVE_END -> { escSeq('F'); return true }
            KeyEvent.KEYCODE_PAGE_UP -> { s.write("\u001b[5~"); return true }
            KeyEvent.KEYCODE_PAGE_DOWN -> { s.write("\u001b[6~"); return true }
            else -> {
                if (ev.isPrintingKey || ev.keyCode == KeyEvent.KEYCODE_SPACE) {
                    val c = ev.getUnicodeChar(ev.metaState)
                    if (c != 0) {
                        s.write(String(Character.toChars(c)))
                        return true
                    }
                }
                return false
            }
        }
    }

    private fun escSeq(ch: Char) { session?.write("\u001b[$ch") }

    fun sendText(text: String) { session?.write(text) }

    // ---------- 触摸：滚动回看 + 唤起键盘 ----------
    override fun onTouchEvent(event: MotionEvent): Boolean {
        when (event.action) {
            MotionEvent.ACTION_DOWN -> {
                lastTouchY = event.y
                touchMoved = false
            }
            MotionEvent.ACTION_MOVE -> {
                val dy = event.y - lastTouchY
                val ch = renderer.getCellH()
                if (ch > 0 && kotlin.math.abs(dy) >= ch) {
                    val lines = (dy / ch).toInt()
                    scrollBy(lines)
                    lastTouchY = event.y - (dy - lines * ch)
                    touchMoved = true
                }
            }
            MotionEvent.ACTION_UP -> {
                if (!touchMoved) {
                    requestFocus()
                    showKeyboard()
                }
            }
        }
        return true
    }

    private fun scrollBy(lines: Int) {
        val em = emulator ?: return
        val maxOff = maxOf(0, em.totalRows - em.rows)
        viewOffset = (viewOffset - lines).coerceIn(0, maxOff)
        renderer.viewOffset = viewOffset
        requestRender()
    }

    private fun showKeyboard() {
        val imm = context.getSystemService(Context.INPUT_METHOD_SERVICE) as InputMethodManager
        imm.showSoftInput(this, InputMethodManager.SHOW_IMPLICIT)
    }

    override fun onDetachedFromWindow() {
        queueEvent { renderer.release() }
        session = null
        super.onDetachedFromWindow()
    }
}
