package io.github.wilsonnet.droidsentenceminer.dict

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class LookupPerfHelpersTest {

    @Test
    fun `lru cache evicts the least recently used entry`() {
        val cache = LruCache<String, Int>(2)
        cache["a"] = 1
        cache["b"] = 2
        assertEquals(1, cache["a"]) // a is now the most recent
        cache["c"] = 3
        assertNull(cache["b"])
        assertEquals(1, cache["a"])
        assertEquals(3, cache["c"])
        cache.clear()
        assertEquals(0, cache.size())
    }

    @Test
    fun `nested structured content renders exactly as before the single-builder rewrite`() {
        val raw = """
            {"type":"structured-content","content":[
              {"tag":"ul","data":{"content":"sense-group","dataFooBar":"x"},"content":[
                {"tag":"li","style":{"listStyleType":"\"＊\"","fontSize":"1.2em"},"content":[
                  {"tag":"span","data":{"class":"tag"},"title":"noun","content":"n"},
                  " a <b> & \"c\"",
                  {"tag":"br"},
                  {"tag":"a","href":"?query=%E9%A3%9F","content":"食"},
                  {"tag":"script","content":"x"}
                ]}
              ]}
            ]}
        """.trimIndent()
        val html = GlossaryHtml.render(GlossaryParser.parse(raw))
        assertEquals(
            "<ul data-sc-content=\"sense-group\" data-sc-data-foo-bar=\"x\">" +
                "<li style=\"list-style-type: &#39;＊&#39;; font-size: 1.2em\">" +
                "<span data-sc-class=\"tag\" title=\"noun\">n</span>" +
                " a &lt;b&gt; &amp; &quot;c&quot;<br>" +
                "<a href=\"?query=%E9%A3%9F\">食</a><div>x</div></li></ul>",
            html,
        )
    }

    @Test
    fun `wrapped document keeps body and dictionary styles verbatim`() {
        val body = "<div>\n  indented body line\n</div>"
        val doc = GlossaryHtml.wrapDocument(body, styles = ".x {\n  color: red;\n}", darkMode = true)
        assertTrue(doc.startsWith("<!DOCTYPE html>"))
        assertTrue(doc.contains("<body><div class=\"glossary\">$body</div></body></html>"))
        assertTrue(doc.contains(".x {\n  color: red;\n}\n</style></head>"))
        assertTrue(doc.contains("color-scheme: dark"))
    }
}
