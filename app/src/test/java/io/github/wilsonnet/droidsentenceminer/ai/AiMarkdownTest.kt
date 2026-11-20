package io.github.wilsonnet.droidsentenceminer.ai

import io.github.wilsonnet.droidsentenceminer.ai.AiMarkdown.Kind
import io.github.wilsonnet.droidsentenceminer.ai.AiMarkdown.Span
import io.github.wilsonnet.droidsentenceminer.ai.AiMarkdown.Style
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class AiMarkdownTest {
    @Test
    fun `inline bold italic and code`() {
        val spans = AiMarkdown.inline("**食べられない** is *potential* + `ない`, not a*b*c")
        assertEquals(Span("食べられない", Style.BOLD), spans[0])
        assertEquals(Span("potential", Style.ITALIC), spans[2])
        assertEquals(Span("ない", Style.CODE), spans[4])
        assertEquals(Span(", not a*b*c", Style.PLAIN), spans.last())
    }

    @Test
    fun `blocks headings lists and joined paragraphs`() {
        val markdown =
            listOf(
                "## Breakdown",
                "1. **食べ** stem",
                "2) られ potential",
                "- note",
                "  - nested",
                "",
                "First line",
                "second line",
            ).joinToString("\n")
        val blocks = AiMarkdown.parse(markdown)
        assertEquals(
            listOf(Kind.HEADING, Kind.NUMBERED, Kind.NUMBERED, Kind.BULLET, Kind.BULLET, Kind.PARAGRAPH),
            blocks.map {
                it.kind
            },
        )
        assertEquals("2)", blocks[2].marker)
        assertEquals(1, blocks[4].level)
        assertEquals("First line second line", blocks.last().spans.joinToString("") { it.text })
    }

    @Test
    fun `card html is escaped and grouped into lists`() {
        val html = AiMarkdown.toHtml(listOf("Use <b> carefully", "- **one**", "- two", "1. three").joinToString("\n"))
        assertTrue(html.startsWith("""<div class="dsm-ai"><p>Use &lt;b&gt; carefully</p><ul><li><b>one</b></li><li>two</li></ul><ol>"""))
        assertTrue(html.endsWith("</ol></div>"))
        assertFalse(html.contains("<b> carefully"))
    }
}
