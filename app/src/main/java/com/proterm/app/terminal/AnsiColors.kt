package com.proterm.app.terminal

/**
 * ANSI 16 色基础调色板 + 256 色（6x6x6 立方体 + 24 级灰度）映射。
 * 颜色以 0xAARRGGBB 的 Int 表示，DEFAULT_COLOR(-1) 表示跟随主题默认色。
 */
object AnsiColors {
    private val BASE = intArrayOf(
        0x000000, 0xCD0000, 0x00CD00, 0xCDCD00,
        0x0000CD, 0xCD00CD, 0x00CDCD, 0xE5E5E5,
        0x7F7F7F, 0xFF0000, 0x00FF00, 0xFFFF00,
        0x5C5CFF, 0xFF00FF, 0x00FFFF, 0xFFFFFF
    )

    fun fg(i: Int): Int = BASE[i.coerceIn(0, 15)]
    fun brightFg(i: Int): Int = BASE[(i + 8).coerceIn(8, 15)]

    fun palette(idx: Int): Int = when {
        idx < 16 -> BASE[idx]
        idx in 16..231 -> {
            val n = idx - 16
            val r = n / 36
            val g = (n / 6) % 6
            val b = n % 6
            val rr = if (r == 0) 0 else 55 + r * 40
            val gg = if (g == 0) 0 else 55 + g * 40
            val bb = if (b == 0) 0 else 55 + b * 40
            (0xFF shl 24) or (rr shl 16) or (gg shl 8) or bb
        }
        idx in 232..255 -> {
            val v = 8 + (idx - 232) * 10
            (0xFF shl 24) or (v shl 16) or (v shl 8) or v
        }
        else -> 0xFFFFFFFF.toInt()
    }
}
