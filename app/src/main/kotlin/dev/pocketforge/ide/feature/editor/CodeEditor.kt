package dev.pocketforge.ide.feature.editor

import androidx.compose.foundation.background
import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.offset
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.text.BasicTextField
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.SolidColor
import androidx.compose.ui.input.key.KeyEvent
import androidx.compose.ui.input.key.onPreviewKeyEvent
import androidx.compose.ui.platform.LocalConfiguration
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.AnnotatedString
import androidx.compose.ui.text.SpanStyle
import androidx.compose.ui.text.TextLayoutResult
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.input.KeyboardCapitalization
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.text.input.OffsetMapping
import androidx.compose.ui.text.input.TextFieldValue
import androidx.compose.ui.text.input.TransformedText
import androidx.compose.ui.text.input.VisualTransformation
import androidx.compose.ui.unit.IntOffset
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import dev.pocketforge.engine.editor.Span
import dev.pocketforge.ide.ui.LocalSyntax
import dev.pocketforge.ide.ui.SyntaxColors

/** Applies syntax colours and the matching-bracket highlight without changing text or offsets. */
private class SyntaxTransformation(
    private val spans: List<Span>,
    private val colors: SyntaxColors,
    private val bracket: Pair<Int, Int>?,
) : VisualTransformation {
    override fun filter(text: AnnotatedString): TransformedText {
        val len = text.length
        val b = AnnotatedString.Builder(text.text)
        for (s in spans) {
            if (s.start >= len) break
            val c = colors.map[s.type] ?: continue
            b.addStyle(SpanStyle(color = c, fontWeight = if (s.type == dev.pocketforge.engine.editor.TokenType.HEADING) FontWeight.Bold else null), s.start, minOf(s.end, len))
        }
        bracket?.let { (a, z) ->
            if (a < len) b.addStyle(SpanStyle(background = colors.bracketMatch), a, a + 1)
            if (z < len) b.addStyle(SpanStyle(background = colors.bracketMatch), z, z + 1)
        }
        return TransformedText(b.toAnnotatedString(), OffsetMapping.Identity)
    }

    override fun equals(other: Any?) = other is SyntaxTransformation && other.spans === spans && other.bracket == bracket && other.colors == colors
    override fun hashCode() = System.identityHashCode(spans) * 31 + (bracket?.hashCode() ?: 0)
}

/**
 * Code editor: monospace BasicTextField with a line-number gutter that follows soft-wrapped lines
 * (positions come from the real text layout), syntax colouring, bracket matching and error marks.
 * Only the visible slice of the gutter is composed, so long files stay smooth.
 */
@Composable
fun CodeEditor(
    value: TextFieldValue,
    onValueChange: (TextFieldValue) -> Unit,
    spans: List<Span>,
    bracket: Pair<Int, Int>?,
    errorLines: Set<Int>,
    readOnly: Boolean,
    fontSizeSp: Int,
    wrap: Boolean,
    onKey: (KeyEvent) -> Boolean,
    modifier: Modifier = Modifier,
) {
    val syntax = LocalSyntax.current
    val vScroll = rememberScrollState()
    val hScroll = rememberScrollState()
    var layout by remember { mutableStateOf<TextLayoutResult?>(null) }
    val density = LocalDensity.current
    val screenPx = with(density) { LocalConfiguration.current.screenHeightDp.dp.toPx() }
    val text = value.text
    val lineStarts = remember(text) {
        val l = ArrayList<Int>(text.length / 30 + 1); l += 0
        for (i in text.indices) if (text[i] == '\n') l += i + 1
        l.toIntArray()
    }
    val digits = lineStarts.size.toString().length.coerceAtLeast(2)
    val gutterWidth = (digits * fontSizeSp * 0.62f + 14).dp
    val style = TextStyle(fontFamily = FontFamily.Monospace, fontSize = fontSizeSp.sp, lineHeight = (fontSizeSp * 1.45f).sp, color = MaterialTheme.colorScheme.onSurface)
    val transformation = remember(spans, bracket, syntax) { SyntaxTransformation(spans, syntax, bracket) }
    val cursorLine = remember(value.selection.start, lineStarts) {
        val i = lineStarts.binarySearch(value.selection.start.coerceAtMost(text.length)); if (i >= 0) i else -i - 2
    }

    Box(modifier.fillMaxSize().verticalScroll(vScroll)) {
        Row(Modifier.fillMaxWidth()) {
            // Gutter
            val lay = layout
            val heightPx = lay?.size?.height ?: 0
            Box(
                Modifier.width(gutterWidth).height(with(density) { heightPx.toDp() }.coerceAtLeast(48.dp))
                    .background(MaterialTheme.colorScheme.surfaceVariant.copy(alpha = 0.35f)),
            ) {
                if (lay != null && text.isNotEmpty()) {
                    val top = (vScroll.value - screenPx).coerceAtLeast(0f)
                    val bottom = vScroll.value + 2 * screenPx
                    val firstVisual = lay.getLineForVerticalPosition(top)
                    val firstOffset = lay.getLineStart(firstVisual)
                    var li = lineStarts.binarySearch(firstOffset).let { if (it >= 0) it else -it - 2 }.coerceAtLeast(0)
                    while (li < lineStarts.size) {
                        val off = lineStarts[li].coerceAtMost(text.length)
                        val y = lay.getLineTop(lay.getLineForOffset(off))
                        if (y > bottom) break
                        val isErr = (li + 1) in errorLines
                        Text(
                            "${li + 1}",
                            style = style.copy(
                                color = when { isErr -> MaterialTheme.colorScheme.error; li == cursorLine -> syntax.gutterActive; else -> syntax.gutter },
                                fontWeight = if (isErr || li == cursorLine) FontWeight.Bold else FontWeight.Normal,
                            ),
                            modifier = Modifier.offset { IntOffset(0, y.toInt()) }.padding(start = 4.dp),
                        )
                        li++
                    }
                }
            }
            val fieldModifier = Modifier
                .padding(start = 6.dp, end = 6.dp)
                .onPreviewKeyEvent(onKey)
                .semantics { contentDescription = "Code editor" }
            Box(if (wrap) Modifier.weight(1f) else Modifier.weight(1f).horizontalScroll(hScroll)) {
                BasicTextField(
                    value = value,
                    onValueChange = onValueChange,
                    readOnly = readOnly,
                    textStyle = style,
                    cursorBrush = SolidColor(MaterialTheme.colorScheme.primary),
                    visualTransformation = transformation,
                    onTextLayout = { layout = it },
                    keyboardOptions = KeyboardOptions(capitalization = KeyboardCapitalization.None, autoCorrectEnabled = false, keyboardType = KeyboardType.Text),
                    modifier = fieldModifier,
                    decorationBox = { inner -> Box(Modifier.background(Color.Transparent)) { inner() } },
                )
            }
        }
    }
}
