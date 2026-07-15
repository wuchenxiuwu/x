package com.proterm.app.terminal.gl

import android.graphics.Typeface
import android.opengl.GLES20
import android.opengl.GLSurfaceView
import android.opengl.Matrix
import com.proterm.app.terminal.TerminalEmulator
import java.nio.ByteBuffer
import java.nio.ByteOrder
import java.nio.FloatBuffer

/**
 * OpenGL ES 2.0 终端渲染器（GLSurfaceView.Renderer）。
 * 每帧构建两套顶点缓冲：
 *  1) 背景层：每个 cell 一个纯色 quad（顶点色 = 背景色，真彩直接上色）。
 *  2) 字形层：每个非空 cell 一个带字形纹理的 quad（顶点色 = 前景色，做 tint）。
 * 字形纹理来自 BitmapFont（ASCII/DEC 静态图集 + 中文动态缓存）。
 */
class TerminalRenderer(
    private val em: TerminalEmulator,
    fontSizePx: Float
) : GLSurfaceView.Renderer {

    private var font: BitmapFont? = null
    private val fontSize = fontSizePx

    private var prog = 0
    private var aPos = 0
    private var aUv = 0
    private var aColor = 0
    private var uProj = 0
    private var uTex = 0
    private var uUseTex = 0

    /** 渲染表面尺寸就绪后回调给视图层，用于计算 cols/rows 并通知会话 resize */
    var sizeListener: SizeListener? = null

    /** GLTerminalView 实现此接口接收渲染表面尺寸 */
    interface SizeListener {
        fun onSize(w: Int, h: Int)
    }

    private val proj = FloatArray(16)
    private var mWidth = 1
    private var mHeight = 1

    var viewOffset = 0
    var themeFg = 0xFFE6E6E6.toInt()
    var themeBg = 0xFF0E1116.toInt()
    var cursorColor = 0xFF6AD6A0.toInt()

    private var bgCap = 0
    private var glyphCap = 0
    private lateinit var bgData: FloatBuffer
    private lateinit var glyphData: FloatBuffer
    private val cursorData = fbuf(1)
    private val scratch = fbuf(1)

    private val VERT = """
        attribute vec2 a_pos;
        attribute vec2 a_uv;
        attribute vec4 a_color;
        uniform mat4 u_proj;
        varying vec2 v_uv;
        varying vec4 v_color;
        void main() {
            gl_Position = u_proj * vec4(a_pos, 0.0, 1.0);
            v_uv = a_uv;
            v_color = a_color;
        }
    """

    private val FRAG = """
        precision mediump float;
        uniform sampler2D u_tex;
        uniform float u_useTex;
        varying vec2 v_uv;
        varying vec4 v_color;
        void main() {
            if (u_useTex > 0.5) {
                vec4 t = texture2D(u_tex, v_uv);
                gl_FragColor = vec4(v_color.rgb, 1.0) * t.a;
            } else {
                gl_FragColor = v_color;
            }
        }
    """

    override fun onSurfaceCreated(gl: javax.microedition.khronos.opengles.GL10?, config: javax.microedition.khronos.egl.EGLConfig?) {
        GLES20.glEnable(GLES20.GL_BLEND)
        GLES20.glBlendFunc(GLES20.GL_SRC_ALPHA, GLES20.GL_ONE_MINUS_SRC_ALPHA)
        font = BitmapFont(Typeface.MONOSPACE, fontSize)
        buildProgram()
    }

    override fun onSurfaceChanged(gl: javax.microedition.khronos.opengles.GL10?, width: Int, height: Int) {
        mWidth = width.coerceAtLeast(1)
        mHeight = height.coerceAtLeast(1)
        GLES20.glViewport(0, 0, mWidth, mHeight)
        sizeListener?.onSize(mWidth, mHeight)
    }

    fun getCellW(): Float = font?.cellW ?: 1f
    fun getCellH(): Float = font?.cellH ?: 1f

    private fun buildProgram() {
        val vs = compile(GLES20.GL_VERTEX_SHADER, VERT)
        val fs = compile(GLES20.GL_FRAGMENT_SHADER, FRAG)
        prog = GLES20.glCreateProgram()
        GLES20.glAttachShader(prog, vs)
        GLES20.glAttachShader(prog, fs)
        GLES20.glLinkProgram(prog)
        aPos = GLES20.glGetAttribLocation(prog, "a_pos")
        aUv = GLES20.glGetAttribLocation(prog, "a_uv")
        aColor = GLES20.glGetAttribLocation(prog, "a_color")
        uProj = GLES20.glGetUniformLocation(prog, "u_proj")
        uTex = GLES20.glGetUniformLocation(prog, "u_tex")
        uUseTex = GLES20.glGetUniformLocation(prog, "u_useTex")
    }

    private fun compile(type: Int, src: String): Int {
        val sh = GLES20.glCreateShader(type)
        GLES20.glShaderSource(sh, src)
        GLES20.glCompileShader(sh)
        return sh
    }

    override fun onDrawFrame(gl: javax.microedition.khronos.opengles.GL10?) {
        GLES20.glClear(GLES20.GL_COLOR_BUFFER_BIT)
        GLES20.glClearColor(r(themeBg), g(themeBg), b(themeBg), 1f)
        Matrix.orthoM(proj, 0, 0f, mWidth.toFloat(), mHeight.toFloat(), 0f, -1f, 1f)

        val font = this.font ?: return
        val cellW = font.cellW
        val cellH = font.cellH
        val rows = em.rows
        val cols = em.cols
        val total = em.totalRows
        val startRow = (total - rows - viewOffset).coerceIn(0, (total - rows).coerceAtLeast(0))

        GLES20.glUseProgram(prog)
        GLES20.glEnableVertexAttribArray(aPos)
        GLES20.glEnableVertexAttribArray(aUv)
        GLES20.glEnableVertexAttribArray(aColor)
        GLES20.glUniform1i(uTex, 0)
        GLES20.glActiveTexture(GLES20.GL_TEXTURE0)

        // ---- 背景层 ----
        ensureBg(rows * cols + 4)
        bgData.clear()
        for (r in 0 until rows) {
            val line = em.getLine(startRow + r) ?: continue
            val y = r * cellH
            for (c in 0 until cols) {
                val cell = line[c]
                val bg = resolveBg(cell)
                putQuad(bgData, c * cellW, y, (c + 1) * cellW, y + cellH, 0f, 0f, 1f, 1f, bg)
            }
        }
        drawBuffer(bgData, 0, false)

        // ---- 字形层 ----
        // 静态图集（ASCII/DEC）走一次批绘；中文等动态字形逐张即时绘制（各自独立纹理）
        GLES20.glEnable(GLES20.GL_BLEND)
        ensureGlyph(rows * cols + rows + 4)
        glyphData.clear()
        val atlasId = font.atlasTexId
        for (r in 0 until rows) {
            val line = em.getLine(startRow + r) ?: continue
            val y = r * cellH
            for (c in 0 until cols) {
                val cell = line[c]
                if (cell.widePad) continue
                val code = cell.char.code
                if (code == 32) continue
                val g = font.getGlyph(code)
                val fg = resolveFg(cell)
                val x0 = c * cellW
                val w = if (g.wide) cellW * 2f else cellW
                if (g.texId == atlasId) {
                    putQuad(glyphData, x0, y, x0 + w, y + cellH, g.u0, g.v0, g.u1, g.v1, fg)
                    if (cell.underline) {
                        val uy = y + cellH - 2f
                        putQuad(glyphData, x0, uy, x0 + w, uy + 2f, 0f, 0f, 1f, 1f, fg)
                    }
                } else {
                    drawGlyphNow(g, x0, y, w, cellH, fg, cell.underline)
                }
            }
        }
        GLES20.glBindTexture(GLES20.GL_TEXTURE_2D, atlasId)
        drawBuffer(glyphData, 0, true)
        GLES20.glBindTexture(GLES20.GL_TEXTURE_2D, 0)
        GLES20.glDisable(GLES20.GL_BLEND)

        // ---- 光标 ----
        if (viewOffset == 0 && em.cursorVisible) {
            val blinkOn = (System.currentTimeMillis() / 500) % 2 == 0L
            if (blinkOn) {
                cursorData.clear()
                val cx = em.cursorCol * cellW
                val cy = em.cursorRow * cellH
                putQuad(cursorData, cx, cy, cx + cellW, cy + cellH, 0f, 0f, 1f, 1f, cursorColor)
                drawBuffer(cursorData, 0, false)
            }
        }
    }

    private fun resolveFg(cell: com.proterm.app.terminal.Cell): Int {
        val fg = if (cell.fg == com.proterm.app.terminal.Cell.DEFAULT_COLOR) themeFg else cell.fg
        val bg = if (cell.bg == com.proterm.app.terminal.Cell.DEFAULT_COLOR) themeBg else cell.bg
        val out = if (cell.inverse) bg else fg
        return if (cell.bold) brighten(out) else out
    }

    private fun resolveBg(cell: com.proterm.app.terminal.Cell): Int {
        val fg = if (cell.fg == com.proterm.app.terminal.Cell.DEFAULT_COLOR) themeFg else cell.fg
        val bg = if (cell.bg == com.proterm.app.terminal.Cell.DEFAULT_COLOR) themeBg else cell.bg
        return if (cell.inverse) fg else bg
    }

    private fun brighten(c: Int): Int {
        val r = (r(c) * 1.3f).coerceAtMost(1f)
        val g = (g(c) * 1.3f).coerceAtMost(1f)
        val bl = (b(c) * 1.3f).coerceAtMost(1f)
        return ((1f * 255).toInt() shl 24) or
            ((r * 255).toInt() shl 16) or
            ((g * 255).toInt() shl 8) or (bl * 255).toInt()
    }

    private fun r(c: Int) = ((c shr 16) and 0xFF) / 255f
    private fun g(c: Int) = ((c shr 8) and 0xFF) / 255f
    private fun b(c: Int) = (c and 0xFF) / 255f

    private fun ensureBg(q: Int) {
        if (q > bgCap) { bgCap = q; bgData = fbuf(q) }
    }
    private fun ensureGlyph(q: Int) {
        if (q > glyphCap) { glyphCap = q; glyphData = fbuf(q) }
    }

    private fun putQuad(buf: FloatBuffer, x0: Float, y0: Float, x1: Float, y1: Float,
                        u0: Float, v0: Float, u1: Float, v1: Float, color: Int) {
        val cr = r(color); val cg = g(color); val cb = b(color); val ca = 1f
        // TL, BL, BR, TL, BR, TR
        buf.put(x0); buf.put(y0); buf.put(u0); buf.put(v0); buf.put(cr); buf.put(cg); buf.put(cb); buf.put(ca)
        buf.put(x0); buf.put(y1); buf.put(u0); buf.put(v1); buf.put(cr); buf.put(cg); buf.put(cb); buf.put(ca)
        buf.put(x1); buf.put(y1); buf.put(u1); buf.put(v1); buf.put(cr); buf.put(cg); buf.put(cb); buf.put(ca)
        buf.put(x0); buf.put(y0); buf.put(u0); buf.put(v0); buf.put(cr); buf.put(cg); buf.put(cb); buf.put(ca)
        buf.put(x1); buf.put(y1); buf.put(u1); buf.put(v1); buf.put(cr); buf.put(cg); buf.put(cb); buf.put(ca)
        buf.put(x1); buf.put(y0); buf.put(u1); buf.put(v0); buf.put(cr); buf.put(cg); buf.put(cb); buf.put(ca)
    }

    private fun drawGlyphNow(g: Glyph, x0: Float, y: Float, w: Float, cellH: Float, fg: Int, underline: Boolean) {
        scratch.clear()
        putQuad(scratch, x0, y, x0 + w, y + cellH, g.u0, g.v0, g.u1, g.v1, fg)
        GLES20.glBindTexture(GLES20.GL_TEXTURE_2D, g.texId)
        drawBuffer(scratch, 0, true)
        if (underline) {
            val uy = y + cellH - 2f
            scratch.clear()
            putQuad(scratch, x0, uy, x0 + w, uy + 2f, 0f, 0f, 1f, 1f, fg)
            drawBuffer(scratch, 0, true)
        }
    }

    private fun drawBuffer(buf: FloatBuffer, quadOffset: Int, useTex: Boolean) {
        buf.position(0)
        val stride = 8 * 4
        GLES20.glVertexAttribPointer(aPos, 2, GLES20.GL_FLOAT, false, stride, 0)
        GLES20.glVertexAttribPointer(aUv, 2, GLES20.GL_FLOAT, false, stride, 2 * 4)
        GLES20.glVertexAttribPointer(aColor, 4, GLES20.GL_FLOAT, false, stride, 4 * 4)
        GLES20.glUniform1i(uUseTex, if (useTex) 1 else 0)
        GLES20.glUniformMatrix4fv(uProj, 1, false, proj, 0)
        val quads = buf.position() / (8 * 6)
        if (quads > 0) GLES20.glDrawArrays(GLES20.GL_TRIANGLES, 0, quads * 6)
    }

    private fun fbuf(capQuads: Int): FloatBuffer =
        ByteBuffer.allocateDirect(capQuads.coerceAtLeast(1) * 6 * 8 * 4)
            .order(ByteOrder.nativeOrder()).asFloatBuffer()

    /** surface 销毁时释放 GL 字体纹理（必须在 GL 线程调用） */
    fun release() {
        font?.dispose()
        font = null
    }
}
