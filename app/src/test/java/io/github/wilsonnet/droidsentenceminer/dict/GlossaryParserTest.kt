package io.github.wilsonnet.droidsentenceminer.dict

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class GlossaryParserTest {

    @Test
    fun `parses simple string glossary`() {
        val entries = GlossaryParser.parse("\"to eat\"")
        assertEquals(1, entries.size)
        assertEquals("to eat", (entries[0].content[0] as GlossaryContent.Text).text)
    }

    @Test
    fun `parses array of strings`() {
        val entries = GlossaryParser.parse("""["to eat","to consume"]""")
        assertEquals(2, entries.size)
    }

    @Test
    fun `parses deinflection entries`() {
        val entries = GlossaryParser.parse("""[["食べる",["past","polite"]]]""")
        assertEquals(1, entries.size)
        val entry = entries.first()
        assertTrue(entry.isDeinflection)
        assertEquals(listOf("past", "polite"), entry.deinflectionForms)
    }

    @Test
    fun `parses structured content`() {
        val raw = """
            {"type":"structured-content","content":[
              {"tag":"div","style":{"fontSize":"1.2em"},"content":"to eat"},
              {"tag":"ul","content":[{"tag":"li","content":"ichidan verb"},"plain text"]},
              {"tag":"br"}
            ]}
        """.trimIndent()
        val entries = GlossaryParser.parse(raw)
        assertEquals(1, entries.size)
        val content = entries.first().content
        assertTrue(content[0] is GlossaryContent.Element)
        val div = content[0] as GlossaryContent.Element
        assertEquals("div", div.tag)
        assertEquals("1.2em", div.style["fontSize"])
        val ul = content[1] as GlossaryContent.Element
        assertEquals(2, ul.children.size)
        assertTrue(ul.children[0] is GlossaryContent.Element)
        assertTrue(ul.children[1] is GlossaryContent.Text)
        assertTrue(content[2] is GlossaryContent.LineBreak)
    }

    @Test
    fun `parses image definitions`() {
        val entries = GlossaryParser.parse("""{"type":"image","path":"images/ring.png","alt":"ring"}""")
        val image = entries.first().content.first() as GlossaryContent.Image
        assertEquals("images/ring.png", image.path)
        assertEquals("ring", image.alt)
    }

    @Test
    fun `renders html with escaping`() {
        val entries = GlossaryParser.parse("""["<script>alert(1)</script>"]""")
        val html = GlossaryHtml.render(entries)
        assertTrue(html.contains("&lt;script&gt;"))
        assertTrue(!html.contains("<script>"))
    }

    @Test
    fun `renders structured content to html`() {
        val raw = """
            {"type":"structured-content","content":[
              {"tag":"div","content":[{"tag":"span","content":"食べる"}]}
            ]}
        """.trimIndent()
        val html = GlossaryHtml.render(GlossaryParser.parse(raw))
        assertTrue(html.contains("<div>"))
        assertTrue(html.contains("<span>食べる</span>"))
    }

    @Test
    fun `image placeholder when images disabled`() {
        val entries = GlossaryParser.parse("""{"type":"image","path":"images/ring.png","alt":"ring"}""")
        val html = GlossaryHtml.render(entries, includeImages = false)
        assertTrue(html.contains("[ring]"))
        assertTrue(!html.contains("<img"))
    }

    @Test
    fun `invalid json yields empty result`() {
        assertTrue(GlossaryParser.parse("not json").isEmpty())
        assertTrue(GlossaryParser.parse(null).isEmpty())
    }

    @Test
    fun `index parser reads metadata`() {
        val index = YomitanIndexParser.parse(
            """
            {
              "title": "Jitendex",
              "revision": "2026.01.01",
              "format": 3,
              "author": "stephenmk",
              "sourceLanguage": "ja",
              "targetLanguage": "en",
              "frequencyMode": "rank-based"
            }
            """.trimIndent(),
        )
        assertEquals("Jitendex", index.title)
        assertEquals("2026.01.01", index.revision)
        assertEquals(3, index.format)
        assertEquals("ja", index.sourceLanguage)
        assertEquals("en", index.targetLanguage)
        assertEquals("rank-based", index.frequencyMode)
    }

    @Test
    fun `index parser returns null for missing optional fields`() {
        val index = YomitanIndexParser.parse("""{"title":"T","revision":"1"}""")
        assertNull(index.author)
        assertEquals(3, index.format)
    }

    @Test(expected = YomitanImporter.ImportException::class)
    fun `index parser throws without title`() {
        YomitanIndexParser.parse("""{"revision":"1"}""")
    }
}
