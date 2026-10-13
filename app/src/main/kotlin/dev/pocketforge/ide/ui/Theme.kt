package dev.pocketforge.ide.ui

import android.content.Context
import android.content.res.Configuration
import dev.pocketforge.engine.editor.TokenType
import dev.pocketforge.ide.platform.ThemeMode

/** Every colour the app draws with. Two palettes, chosen per Settings → Theme (System/Light/Dark). */
data class Palette(
    val dark: Boolean,
    val bg: Int,
    val surface: Int,
    val surfaceHi: Int,
    val border: Int,
    val text: Int,
    val muted: Int,
    val accent: Int,
    val onAccent: Int,
    val danger: Int,
    val ok: Int,
    val warn: Int,
    val info: Int,
    val gutterBg: Int,
    val gutterText: Int,
    val selection: Int,
    val bracketBg: Int,
    val findBg: Int,
    val diffAdd: Int,
    val diffDel: Int,
    val tokens: Map<TokenType, Int>,
) {
    fun token(t: TokenType): Int = tokens[t] ?: text
}

object Themes {
    private fun c(hex: Long) = hex.toInt()

    val Dark = Palette(
        dark = true,
        bg = c(0xFF0E1215), surface = c(0xFF161B20), surfaceHi = c(0xFF1E252C), border = c(0xFF2B343D),
        text = c(0xFFE6EAEE), muted = c(0xFF97A4B0), accent = c(0xFFF5A524), onAccent = c(0xFF1B1203),
        danger = c(0xFFF87171), ok = c(0xFF4ADE80), warn = c(0xFFFBBF24), info = c(0xFF60A5FA),
        gutterBg = c(0xFF12171B), gutterText = c(0xFF5D6B78), selection = c(0x664D7CFE), bracketBg = c(0x55F5A524),
        findBg = c(0x66F5A524), diffAdd = c(0x332EA043), diffDel = c(0x33F85149),
        tokens = mapOf(
            TokenType.KEYWORD to c(0xFFFF9E64), TokenType.TYPE to c(0xFF7DCFFF), TokenType.STRING to c(0xFF9ECE6A),
            TokenType.NUMBER to c(0xFFE0AF68), TokenType.COMMENT to c(0xFF6B7785), TokenType.ANNOTATION to c(0xFFBB9AF7),
            TokenType.TAG to c(0xFFF7768E), TokenType.ATTRIBUTE to c(0xFFE0AF68), TokenType.HEADING to c(0xFF7AA2F7),
            TokenType.EMPHASIS to c(0xFFC0CAF5), TokenType.CODE to c(0xFF9ECE6A), TokenType.PUNCTUATION to c(0xFFA9B1D6),
        ),
    )

    val Light = Palette(
        dark = false,
        bg = c(0xFFF5F6F8), surface = c(0xFFFFFFFF), surfaceHi = c(0xFFECEFF3), border = c(0xFFD5DCE3),
        text = c(0xFF1B2229), muted = c(0xFF56636F), accent = c(0xFFB45309), onAccent = c(0xFFFFFFFF),
        danger = c(0xFFB91C1C), ok = c(0xFF047857), warn = c(0xFFA16207), info = c(0xFF1D4ED8),
        gutterBg = c(0xFFEEF1F4), gutterText = c(0xFF8592A0), selection = c(0x553B82F6), bracketBg = c(0x55B45309),
        findBg = c(0x66F59E0B), diffAdd = c(0x3316A34A), diffDel = c(0x33DC2626),
        tokens = mapOf(
            TokenType.KEYWORD to c(0xFFA626A4), TokenType.TYPE to c(0xFF0184BC), TokenType.STRING to c(0xFF2F7D32),
            TokenType.NUMBER to c(0xFF986801), TokenType.COMMENT to c(0xFF7D8793), TokenType.ANNOTATION to c(0xFFC18401),
            TokenType.TAG to c(0xFFE45649), TokenType.ATTRIBUTE to c(0xFF986801), TokenType.HEADING to c(0xFF4078F2),
            TokenType.EMPHASIS to c(0xFF383A42), TokenType.CODE to c(0xFF2F7D32), TokenType.PUNCTUATION to c(0xFF4B5563),
        ),
    )

    fun resolve(ctx: Context, mode: ThemeMode): Palette = when (mode) {
        ThemeMode.DARK -> Dark
        ThemeMode.LIGHT -> Light
        ThemeMode.SYSTEM -> if ((ctx.resources.configuration.uiMode and Configuration.UI_MODE_NIGHT_MASK) == Configuration.UI_MODE_NIGHT_YES) Dark else Light
    }
}
