package io.github.wilsonnet.droidsentenceminer.ui.components

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.widthIn
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.remember
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.AnnotatedString
import androidx.compose.ui.text.SpanStyle
import androidx.compose.ui.text.buildAnnotatedString
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontStyle
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.withStyle
import androidx.compose.ui.unit.dp
import io.github.wilsonnet.droidsentenceminer.ai.AiMarkdown

/** An AI answer's Markdown ([AiMarkdown]) as Material text: headings, hanging-indent lists, bold, italic, code. */
@Suppress("FunctionName") // Composables are PascalCase.
@Composable
fun AiMarkdownText(markdown: String, modifier: Modifier = Modifier) {
    val blocks = remember(markdown) { AiMarkdown.parse(markdown) }
    val codeBackground = MaterialTheme.colorScheme.surfaceVariant
    Column(modifier, verticalArrangement = Arrangement.spacedBy(BLOCK_GAP)) {
        blocks.forEach { block ->
            val text = annotated(spans = block.spans, codeBackground = codeBackground)
            when (block.kind) {
                AiMarkdown.Kind.HEADING -> {
                    Text(text, style = MaterialTheme.typography.titleSmall, modifier = Modifier.padding(top = HEADING_TOP))
                }

                AiMarkdown.Kind.PARAGRAPH -> {
                    Text(text, style = MaterialTheme.typography.bodyMedium)
                }

                AiMarkdown.Kind.BULLET, AiMarkdown.Kind.NUMBERED -> {
                    val marker = if (block.kind == AiMarkdown.Kind.BULLET) "•" else block.marker
                    Row(Modifier.padding(start = LIST_INDENT * block.level)) {
                        Text(
                            text = marker,
                            style = MaterialTheme.typography.bodyMedium,
                            color = MaterialTheme.colorScheme.primary,
                            modifier = Modifier.widthIn(min = MARKER_WIDTH),
                        )
                        Text(text, style = MaterialTheme.typography.bodyMedium)
                    }
                }
            }
        }
    }
}

private fun annotated(spans: List<AiMarkdown.Span>, codeBackground: Color): AnnotatedString {
    val text =
        buildAnnotatedString {
            spans.forEach { span ->
                val style =
                    when (span.style) {
                        AiMarkdown.Style.PLAIN -> null
                        AiMarkdown.Style.BOLD -> SpanStyle(fontWeight = FontWeight.SemiBold)
                        AiMarkdown.Style.ITALIC -> SpanStyle(fontStyle = FontStyle.Italic)
                        AiMarkdown.Style.CODE -> SpanStyle(fontFamily = FontFamily.Monospace, background = codeBackground)
                    }
                if (style == null) append(span.text) else withStyle(style) { append(span.text) }
            }
        }
    return text
}

private val BLOCK_GAP = 6.dp
private val HEADING_TOP = 4.dp
private val LIST_INDENT = 12.dp
private val MARKER_WIDTH = 20.dp
