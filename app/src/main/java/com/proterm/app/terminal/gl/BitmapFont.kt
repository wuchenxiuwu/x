package com.proterm.app.terminal.gl

import android.graphics.Bitmap
import android.graphics.Canvas
import android.graphics.Color
import android.graphics.Paint
import android.graphics.PorterDuff
import android.graphics.Typeface
import android.opengl.GLES20
import android.opengl.GLUtils

/** 一个字形在纹理中的描述：纹理 id + UV 矩形 + 是否宽字符（中文占 2 cell） */
data class Glyph(
    val texId: Int,
    val u0: Float, val v0: Float, val u1: Float, val v1: Float,
    val wide: Boolean
)

/**
 * 终端字体图集：
 *  - 静态图集纹理：ASCII(0x20..0x7E) + Latin1(0xA0..0xFF) + DEC 画线/符号字符，启动时一次性栅格化上传。
 *  - 动态字形缓存：中文等不在静态图集的（宽）字符，运行时按需栅格化进独立纹理，LRU 淘汰。
 * 所有字形最终都是 OpenGL 纹理，渲染层用顶点缓冲批量绘制。
 */
class BitmapFont(typeface: Typeface, fontSize: Float) {
    var cellW = 0f
        private set
    var cellH = 0f
        private set

    private val paint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        this.typeface = typeface
        this.textSize = fontSize
        this.color = Color.WHITE
        this.textAlign = Paint.Align.LEFT
        this.isFilterBitmap = true
        this.isSubpixelText = true
    }
    private val fm = paint.fontMetrics
    private val cellPx: Int

    var atlasTexId = 0
    private val staticMap = HashMap<Int, Glyph>()
    private val dynamicCache = LinkedHashMap<Int, Glyph>(256, 0.75f, true)
    private val DYN_CAP = 1500

    init {
        cellW = paint.measureText("M")
        cellH = (fontSize * 1.2f).coerceAtLeast(cellW * 1.2f)
        cellPx = kotlin.math.ceil(cellH.toDouble()).toInt().coerceAtLeast(1)
        generateAtlas()
    }

    private fun generateAtlas() {
        val chars = mutableListOf<Int>()
        for (c in 0x20..0x7E) chars.add(c)
        for (c in 0xA0..0xFF) chars.add(c)
        val dec = intArrayOf(
            0x25C6, 0x2592, 0x2409, 0x240C, 0x240D, 0x240A, 0x00B0, 0x00B1,
            0x2424, 0x240B, 0x2518, 0x2510, 0x250C, 0x2514, 0x253C, 0x2500,
            0x251C, 0x2524, 0x2534, 0x252C, 0x2502, 0x2264, 0x2265, 0x03C0,
            0x2260, 0x00A3, 0x00B7
        )
        for (c in dec) if (c !in chars) chars.add(c)
        // 框线/符号补充
        for (c in intArrayOf(0x2501, 0x2503, 0x250F, 0x2513, 0x2517, 0x251B,
            0x2523, 0x252B, 0x2533, 0x253B, 0x254B, 0x2588, 0x2580, 0x2584)) {
            if (c !in chars) chars.add(c)
        }

        val cols = 16
        val rows = (chars.size + cols - 1) / cols
        val bmpW = cols * cellPx
        val bmpH = rows * cellPx
        val bmp = Bitmap.createBitmap(bmpW, bmpH, Bitmap.Config.ARGB_8888)
        val canvas = Canvas(bmp)
        canvas.drawColor(Color.TRANSPARENT, PorterDuff.Mode.CLEAR)
        val baseline = (cellPx - (fm.ascent + fm.descent)) / 2f - fm.ascent

        for (i in chars.indices) {
            val col = i % cols
            val row = i / cols
            val x = col * cellPx
            val y = row * cellPx
            canvas.drawText(String(Character.toChars(chars[i])), x.toFloat(), y + baseline, paint)
            staticMap[chars[i]] = Glyph(
                atlasTexId,
                x.toFloat() / bmpW, y.toFloat() / bmpH,
                (x + cellW) / bmpW, (y + cellH) / bmpH,
                false
            )
        }
        atlasTexId = upload(bmp)
        bmp.recycle()
    }

    private fun upload(bmp: Bitmap): Int {
        val tex = IntArray(1)
        GLES20.glGenTextures(1, tex, 0)
        GLES20.glBindTexture(GLES20.GL_TEXTURE_2D, tex[0])
        GLES20.glTexParameteri(GLES20.GL_TEXTURE_2D, GLES20.GL_TEXTURE_MIN_FILTER, GLES20.GL_LINEAR)
        GLES20.glTexParameteri(GLES20.GL_TEXTURE_2D, GLES20.GL_TEXTURE_MAG_FILTER, GLES20.GL_LINEAR)
        GLES20.glTexParameteri(GLES20.GL_TEXTURE_2D, GLES20.GL_TEXTURE_WRAP_S, GLES20.GL_CLAMP_TO_EDGE)
        GLES20.glTexParameteri(GLES20.GL_TEXTURE_2D, GLES20.GL_TEXTURE_WRAP_T, GLES20.GL_CLAMP_TO_EDGE)
        GLUtils.texImage2D(GLES20.GL_TEXTURE_2D, 0, bmp, 0)
        return tex[0]
    }

    /** 取一个码点的字形；中文等宽字符不在静态图集时按需栅格化并缓存 */
    fun getGlyph(code: Int): Glyph {
        staticMap[code]?.let { return it }
        dynamicCache[code]?.let { return it }

        val gw = kotlin.math.ceil(cellW * 2).toInt().coerceAtLeast(1)
        val bmp = Bitmap.createBitmap(gw, cellPx, Bitmap.Config.ARGB_8888)
        val cv = Canvas(bmp)
        cv.drawColor(Color.TRANSPARENT, PorterDuff.Mode.CLEAR)
        val baseline = (cellPx - (fm.ascent + fm.descent)) / 2f - fm.ascent
        cv.drawText(String(Character.toChars(code)), 0f, baseline, paint)
        val tex = upload(bmp)
        bmp.recycle()
        val g = Glyph(tex, 0f, 0f, 1f, 1f, true)
        dynamicCache[code] = g
        if (dynamicCache.size > DYN_CAP) {
            val oldest = dynamicCache.entries.first()
            dynamicCache.remove(oldest.key)
            GLES20.glDeleteTextures(1, intArrayOf(oldest.value.texId), 0)
        }
        return g
    }

    fun dispose() {
        if (atlasTexId != 0) GLES20.glDeleteTextures(1, intArrayOf(atlasTexId), 0)
        for (g in dynamicCache.values) GLES20.glDeleteTextures(1, intArrayOf(g.texId), 0)
        dynamicCache.clear()
    }
}
