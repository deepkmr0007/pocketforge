package dev.pocketforge.ide.ui

import android.os.Build
import androidx.compose.foundation.isSystemInDarkTheme
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.darkColorScheme
import androidx.compose.material3.dynamicDarkColorScheme
import androidx.compose.material3.dynamicLightColorScheme
import androidx.compose.material3.lightColorScheme
import androidx.compose.runtime.Composable
import androidx.compose.runtime.Immutable
import androidx.compose.runtime.staticCompositionLocalOf
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalContext
import dev.pocketforge.engine.editor.TokenType
import dev.pocketforge.ide.platform.ThemeMode

private val Ember = Color(0xFFFFB74D)
private val Steel = Color(0xFF80DEEA)

private val DarkColors = darkColorScheme(primary = Ember, secondary = Steel, tertiary = Color(0xFFB39DDB))
private val LightColors = lightColorScheme(primary = Color(0xFFB45309), secondary = Color(0xFF00838F), tertiary = Color(0xFF5E35B1))

/** Syntax colours, tuned separately for light and dark backgrounds (WCAG AA contrast on surface). */
@Immutable
data class SyntaxColors(val map: Map<TokenType, Color>, val bracketMatch: Color, val gutter: Color, val gutterActive: Color)

private val darkSyntax = SyntaxColors(
    mapOf(
        TokenType.KEYWORD to Color(0xFFFF9E64), TokenType.TYPE to Color(0xFF7DCFFF), TokenType.STRING to Color(0xFF9ECE6A),
        TokenType.NUMBER to Color(0xFFE0AF68), TokenType.COMMENT to Color(0xFF8B93B5), TokenType.ANNOTATION to Color(0xFFBB9AF7),
        TokenType.TAG to Color(0xFFF7768E), TokenType.ATTRIBUTE to Color(0xFF7AA2F7), TokenType.HEADING to Color(0xFFFF9E64),
        TokenType.EMPHASIS to Color(0xFFC0CAF5), TokenType.CODE to Color(0xFF9ECE6A), TokenType.PUNCTUATION to Color(0xFFA9B1D6),
    ),
    bracketMatch = Color(0x5580DEEA), gutter = Color(0xFF6B7089), gutterActive = Color(0xFFE6E6E6),
)
private val lightSyntax = SyntaxColors(
    mapOf(
        TokenType.KEYWORD to Color(0xFFA0410D), TokenType.TYPE to Color(0xFF00658F), TokenType.STRING to Color(0xFF2E7D32),
        TokenType.NUMBER to Color(0xFF8F5B00), TokenType.COMMENT to Color(0xFF5F6368), TokenType.ANNOTATION to Color(0xFF6A1B9A),
        TokenType.TAG to Color(0xFFB00040), TokenType.ATTRIBUTE to Color(0xFF1A4FBF), TokenType.HEADING to Color(0xFFA0410D),
        TokenType.EMPHASIS to Color(0xFF37474F), TokenType.CODE to Color(0xFF2E7D32), TokenType.PUNCTUATION to Color(0xFF455A64),
    ),
    bracketMatch = Color(0x5500838F), gutter = Color(0xFF8A8F98), gutterActive = Color(0xFF202124),
)

val LocalSyntax = staticCompositionLocalOf { darkSyntax }

@Composable
fun PocketForgeTheme(mode: ThemeMode, content: @Composable () -> Unit) {
    val dark = when (mode) { ThemeMode.SYSTEM -> isSystemInDarkTheme(); ThemeMode.DARK -> true; ThemeMode.LIGHT -> false }
    val ctx = LocalContext.current
    val colors = when {
        Build.VERSION.SDK_INT >= 31 && dark -> dynamicDarkColorScheme(ctx).copy(primary = Ember)
        Build.VERSION.SDK_INT >= 31 -> dynamicLightColorScheme(ctx)
        dark -> DarkColors
        else -> LightColors
    }
    androidx.compose.runtime.CompositionLocalProvider(LocalSyntax provides if (dark) darkSyntax else lightSyntax) {
        MaterialTheme(colorScheme = colors, content = content)
    }
}
