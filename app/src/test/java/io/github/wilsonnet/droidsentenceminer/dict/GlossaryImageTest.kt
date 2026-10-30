package io.github.wilsonnet.droidsentenceminer.dict

import io.github.wilsonnet.droidsentenceminer.anki.CardHtml
import io.github.wilsonnet.droidsentenceminer.anki.GlossaryMedia
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/** Image rendering against Yomitan's `createDefinitionImage` (node shapes taken from real dictionaries). */
class GlossaryImageTest {

    private fun render(json: String, inline: Boolean = false, url: (String) -> String? = { "https://dict-media.invalid/7/$it" }) =
        GlossaryHtml.render(GlossaryParser.parse(json), imageUrl = url, inlineImageStyles = inline, dictionary = "Pixiv [2026-10-29]")

    /** The footer of every Pixiv [2026-10-29] (ピクシブ百科事典) entry, verbatim. */
    private val pixivFooter = """
        {"type":"structured-content","content":[{"tag":"div","data":{"pixiv":"footer"},"content":[{"tag":"span","content":[
          {"tag":"img","path":"assets/pixiv-logo.png","alt":"pixiv","collapsed":false,"collapsible":false,"height":1,"width":1,"sizeUnits":"em","verticalAlign":"middle"},
          " ",{"tag":"a","href":"https://dic.pixiv.net/a/ ルキア(QMA)","content":"pixivで読む"}],"data":{"pixiv":"read-more-link"}}]}]}
    """.trimIndent()

    @Test
    fun `pixiv logo is a 1em square aligned to the middle of the link text`() {
        val html = render(pixivFooter)
        assertTrue(html, html.contains("""<a class="gloss-image-link" data-path="assets/pixiv-logo.png" data-dictionary="Pixiv [2026-10-29]" data-has-aspect-ratio="true" data-image-rendering="auto" data-appearance="auto" data-background="true" data-collapsed="false" data-collapsible="false" data-vertical-align="middle" data-size-units="em">"""))
        assertTrue(html, html.contains("""<span class="gloss-image-container" style="width:1em">"""))
        assertTrue(html, html.contains("""<span class="gloss-image-sizer" style="padding-top:100%"></span>"""))
        assertTrue(html, html.contains("""<img class="gloss-image" src="https://dict-media.invalid/7/assets/pixiv-logo.png" alt="pixiv" decoding="async">"""))
        assertTrue(html, html.contains("""<span class="gloss-image-link-text">Image</span></a>"""))
        assertTrue(html, html.contains("""<span data-sc-pixiv="read-more-link">"""))
    }

    @Test
    fun `aspect ratio sizer follows the given size`() {
        val html = render("""{"type":"structured-content","content":{"tag":"img","path":"img/a.png","width":300,"height":150}}""")
        assertTrue(html, html.contains("""style="width:300px""""))
        assertTrue(html, html.contains("""padding-top:50%"""))
        assertFalse(html, html.contains("data-size-units"))
    }

    @Test
    fun `only a width keeps the file's own aspect ratio`() {
        val html = render("""{"type":"image","path":"pics/犬.jpg","width":250,"description":"柴犬\n(しばいぬ)"}""")
        assertTrue(html, html.contains("""data-has-aspect-ratio="false""""))
        assertTrue(html, html.contains("""<span class="gloss-image-container" style="width:250px">"""))
        assertTrue(html, html.contains("""style="width:100%;height:auto""""))
        assertFalse(html, html.contains("gloss-image-sizer"))
        assertTrue(html, html.contains("""<span class="gloss-image-description">柴犬""" + "\n" + """(しばいぬ)</span>"""))
    }

    @Test
    fun `only a height sizes the image by height`() {
        val html = render("""{"type":"structured-content","content":{"tag":"img","path":"a.svg","height":2,"sizeUnits":"em"}}""")
        assertTrue(html, html.contains("""style="height:2em;width:auto""""))
        assertTrue(html, html.contains("""data-size-units="em""""))
        assertTrue(html, html.contains("""<span class="gloss-image-container">"""))
    }

    @Test
    fun `no size shows the image at its natural size`() {
        val html = render("""{"type":"image","path":"a.png"}""")
        assertTrue(html, html.contains("""data-has-aspect-ratio="false""""))
        assertTrue(html, html.contains("""<span class="gloss-image-container">"""))
        assertTrue(html, html.contains("""<img class="gloss-image" src="https://dict-media.invalid/7/a.png" alt="" decoding="async">"""))
    }

    @Test
    fun `database format uses preferred size and real aspect ratio`() {
        val image = GlossaryParser.parse("""{"type":"image","path":"a.png","width":800,"height":600,"preferredWidth":200}""")
            .first().content.first() as GlossaryContent.Image
        assertEquals(200.0, image.displayWidth!!, 0.0)
        assertEquals(150.0, image.displayHeight!!, 0.0)
    }

    @Test
    fun `rendering appearance background border and title`() {
        val html = render(
            """{"type":"structured-content","content":{"tag":"img","path":"k.png","width":32,"height":32,"pixelated":true,
               "appearance":"monochrome","background":false,"border":"1px solid red","borderRadius":"4px","title":"kanji \"stroke\""}}""",
        )
        assertTrue(html, html.contains("""data-image-rendering="pixelated""""))
        assertTrue(html, html.contains("""data-appearance="monochrome""""))
        assertTrue(html, html.contains("""data-background="false""""))
        assertTrue(html, html.contains("""style="width:32px;border:1px solid red;border-radius:4px" title="kanji &quot;stroke&quot;""""))
        assertTrue(html, html.contains("""<span class="gloss-image-background" style="--image:url(&quot;https://dict-media.invalid/7/k.png&quot;)"></span>"""))
        val crisp = render("""{"type":"image","path":"k.png","imageRendering":"crisp-edges","pixelated":true}""")
        assertTrue(crisp, crisp.contains("""data-image-rendering="crisp-edges""""))
    }

    @Test
    fun `collapsed image opens on tap`() {
        val html = render("""{"type":"structured-content","content":{"tag":"img","path":"a.png","width":10,"height":10,"collapsed":true}}""")
        assertTrue(html, html.startsWith("""<details class="gloss-image-details"><summary class="gloss-image-summary">[Image]</summary><a class="gloss-image-link""""))
        assertTrue(html, html.contains("""data-collapsed="true""""))
        assertTrue(html, html.endsWith("</a></details>"))
    }

    @Test
    fun `anki cards get inline styles and the media name`() {
        val html = render(pixivFooter, inline = true) { "dsm_dict_0123456789abcdef.png" }
        assertTrue(html, html.contains("""src="dsm_dict_0123456789abcdef.png""""))
        assertTrue(html, html.contains("""style="display:inline-block;position:relative;line-height:1;max-width:100%;color:inherit;vertical-align:middle""""))
        assertTrue(html, html.contains("overflow:hidden;width:1em"))
        assertTrue(html, html.contains("""style="display:inline-block;width:0;vertical-align:top;font-size:0;padding-top:100%""""))
        assertTrue(html, html.contains("position:absolute;left:0;top:0;width:100%;height:100%"))
        assertFalse(html, html.contains("loading="))
        assertFalse(html, html.contains("gloss-image-link-text"))

        val mono = render("""{"type":"image","path":"m.png","width":20,"height":20,"appearance":"monochrome"}""", inline = true) { "m1.png" }
        assertTrue(mono, mono.contains("mask-image:url(&quot;m1.png&quot;)"))
        assertTrue(mono, mono.contains("background-color:currentColor"))
        assertTrue(mono, mono.contains(";opacity:0"))
    }

    @Test
    fun `real monolingual dictionary image shapes`() {
        // 広辞苑 第七版: photo without a size (shown at its own 487x309), and a height-only monochrome formula.
        val photo = render("""{"type":"structured-content","content":{"tag":"img","path":"media/200001.jpg","title":"media/200001.jpg"}}""")
        assertTrue(photo, photo.contains("""data-has-aspect-ratio="false""""))
        assertTrue(photo, photo.contains("""<span class="gloss-image-container" title="media/200001.jpg">"""))
        val formula = render(
            """{"type":"structured-content","content":{"tag":"img","path":"media/elinea001.png","height":1.21333,"background":false,
               "appearance":"monochrome","collapsible":false,"collapsed":false,"sizeUnits":"em","title":"media/elinea001.png"}}""",
        )
        assertTrue(formula, formula.contains("""style="height:1.2133em;width:auto""""))
        assertTrue(formula, formula.contains("gloss-image-background\" style=\"--image:url("))
        // 三省堂国語辞典 第八版: 0.5em accent mark, and a collapsed picture.
        val accent = render(
            """{"type":"structured-content","content":{"tag":"img","height":1.0,"width":0.5,"sizeUnits":"em","appearance":"auto",
               "collapsible":false,"collapsed":false,"background":false,"path":"sankoku8/svg-accent/アクセント.svg"}}""",
        )
        assertTrue(accent, accent.contains("""style="width:0.5em""""))
        assertTrue(accent, accent.contains("padding-top:200%"))
        val picture = render("""{"type":"structured-content","content":{"tag":"img","title":"","collapsible":true,"collapsed":true,"path":"sankoku8/graphics/003artichoke.png"}}""")
        assertTrue(picture, picture.startsWith("<details class=\"gloss-image-details\">"))
        assertFalse(picture, picture.contains("title=\"\""))
    }

    @Test
    fun `missing image becomes its alt placeholder`() {
        val html = render(pixivFooter) { null }
        assertTrue(html, html.contains("""<span class="gloss-image-placeholder">[pixiv]</span>"""))
        assertFalse(html.contains("<img"))
    }

    @Test
    fun `paths are escaped in attributes and urls`() {
        val html = render("""{"type":"image","path":"a\"b.png","width":1,"height":2}""") { "x\"y" }
        assertTrue(html, html.contains("""data-path="a&quot;b.png""""))
        assertTrue(html, html.contains("""src="x&quot;y""""))
        assertTrue(html, html.contains("padding-top:200%"))
    }

    @Test
    fun `media urls round-trip any path`() {
        val path = "画像/a b+c%.png"
        val url = DictMedia.urlFor(42, path)
        assertEquals("https://dict-media.invalid/42/%E7%94%BB%E5%83%8F%2Fa%20b%2Bc%25.png", url)
        assertEquals(42L to path, DictMedia.parseUrl(url))
        assertNull(DictMedia.parseUrl("https://example.com/42/a.png"))
        assertEquals("png", DictMedia.extensionOf("dir.v2/IMG.PNG"))
        assertEquals("bin", DictMedia.extensionOf("noext"))
        assertEquals("image/svg+xml", DictMedia.mimeOf("svg"))
    }

    @Test
    fun `stored media names replace the placeholders`() {
        val media = listOf(GlossaryMedia("dsm_dict_a.png", ByteArray(1), "image/png"), GlossaryMedia("dsm_dict_b.png", ByteArray(1), "image/png"))
        val html = """<img src="dsm_dict_a.png"><span style="mask-image:url(&quot;dsm_dict_a.png&quot;)"></span><img src="dsm_dict_b.png">"""
        val stored = CardHtml.withStoredMedia(html, media) { if (it.name == "dsm_dict_a.png") "dsm_dict_a.png_1234.png" else null }
        assertEquals("""<img src="dsm_dict_a.png_1234.png"><span style="mask-image:url(&quot;dsm_dict_a.png_1234.png&quot;)"></span><img src="dsm_dict_b.png">""", stored)
        assertEquals("x_1.png", CardHtml.mediaNameOf("<img src=\"x_1.png\" />"))
        assertEquals("y.mp3", CardHtml.mediaNameOf("[sound:y.mp3]"))
        assertNull(CardHtml.mediaNameOf(null))
    }
}
