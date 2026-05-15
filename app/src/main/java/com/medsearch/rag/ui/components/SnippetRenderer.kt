package com.medsearch.rag.ui.components

import androidx.compose.material3.MaterialTheme
import androidx.compose.runtime.Composable
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.AnnotatedString
import androidx.compose.ui.text.SpanStyle
import androidx.compose.ui.text.buildAnnotatedString
import androidx.compose.ui.text.font.FontWeight

@Composable
fun rememberHighlightedSnippet(raw: String): AnnotatedString {
    val highlight = MaterialTheme.colorScheme.primaryContainer
    val onHighlight = MaterialTheme.colorScheme.onPrimaryContainer
    return buildHighlighted(raw, highlight, onHighlight)
}

private fun buildHighlighted(raw: String, bg: Color, fg: Color): AnnotatedString =
    buildAnnotatedString {
        var i = 0
        while (i < raw.length) {
            val open = raw.indexOf("[[HIT]]", i)
            if (open < 0) {
                append(raw.substring(i))
                return@buildAnnotatedString
            }
            append(raw.substring(i, open))
            val close = raw.indexOf("[[/HIT]]", open)
            if (close < 0) {
                append(raw.substring(open + "[[HIT]]".length))
                return@buildAnnotatedString
            }
            val hit = raw.substring(open + "[[HIT]]".length, close)
            withStyle(SpanStyle(background = bg, color = fg, fontWeight = FontWeight.SemiBold)) {
                append(hit)
            }
            i = close + "[[/HIT]]".length
        }
    }

private inline fun AnnotatedString.Builder.withStyle(
    style: SpanStyle,
    block: AnnotatedString.Builder.() -> Unit
) {
    val s = pushStyle(style)
    try { block() } finally { pop(s) }
}
