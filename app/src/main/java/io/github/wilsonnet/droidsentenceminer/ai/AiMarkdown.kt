package io.github.wilsonnet.droidsentenceminer.ai

import io.github.wilsonnet.droidsentenceminer.dict.GlossaryHtml

/**
 * The Markdown subset AI answers use: headings, bullet and numbered lists, paragraphs, and
 * **bold**, *italic* and `code` inside them. Parsed once into [Block]s, drawn by the popup
 * (Compose) and by [toHtml] (cards). Anything else stays plain text. Unit tested.
 */
object AiMarkdown {
    /** Inline style of a [Span]. */
    enum class Style {
        /** Plain text. */
        PLAIN,

        /** `**bold**` / `__bold__`. */
        BOLD,

        /** `*italic*` / `_italic_`. */
        ITALIC,

        /** `` `code` ``. */
        CODE,
    }

    /**
     * A run of text in one [Style].
     *
     * @property text The characters, markers removed.
     * @property style How they are drawn.
     */
    data class Span(val text: String, val style: Style)

    /** Kind of a [Block]. */
    enum class Kind {
        /** `# …` to `###### …`. */
        HEADING,

        /** `- …`, `* …`, `• …`. */
        BULLET,

        /** `1. …` ([Block.marker] holds "1."). */
        NUMBERED,

        /** Any other line run. */
        PARAGRAPH,
    }

    /**
     * One line-level element.
     *
     * @property kind Heading, list item or paragraph.
     * @property spans Its inline text.
     * @property marker A numbered item's "1." (empty otherwise).
     * @property level List nesting depth (0 = top level).
     */
    data class Block(val kind: Kind, val spans: List<Span>, val marker: String = "", val level: Int = 0)

    private val HEADING = Regex("""^(#{1,6})\s+(.*)$""")
    private val BULLET = Regex("""^(\s*)[-*•+]\s+(.*)$""")
    private val NUMBERED = Regex("""^(\s*)(\d{1,3}[.)])\s+(.*)$""")
    private val INLINE =
        Regex(
            """\*\*(.+?)\*\*|__(.+?)__|`([^`]+)`|(?<![\w*])\*(?!\s)(.+?)(?<!\s)\*(?!\*)|(?<!\w)_(?!\s)(.+?)(?<!\s)_(?!\w)""",
        )
    private const val INDENT_PER_LEVEL = 2
    private const val GROUP_BOLD_STARS = 1
    private const val GROUP_BOLD_UNDERSCORES = 2
    private const val GROUP_CODE = 3
    private const val GROUP_ITALIC_STAR = 4
    private const val GROUP_ITALIC_UNDERSCORE = 5

    /** [markdown] as blocks; consecutive plain lines join into one paragraph, blank lines end it. */
    fun parse(markdown: String): List<Block> {
        val blocks = mutableListOf<Block>()
        val paragraph = StringBuilder()

        fun flush() {
            if (paragraph.isNotBlank()) blocks += Block(Kind.PARAGRAPH, inline(paragraph.toString().trim()))
            paragraph.clear()
        }
        for (raw in markdown.lines()) {
            val line = raw.trimEnd()
            val block = lineBlock(line)
            when {
                block != null -> {
                    flush()
                    blocks += block
                }

                line.isBlank() -> {
                    flush()
                }

                else -> {
                    if (paragraph.isNotEmpty()) paragraph.append(' ')
                    paragraph.append(line.trim())
                }
            }
        }
        flush()
        return blocks
    }

    /** [text]'s inline spans. */
    fun inline(text: String): List<Span> {
        val spans = mutableListOf<Span>()
        var last = 0
        for (match in INLINE.findAll(text)) {
            if (match.range.first > last) spans += Span(text.substring(startIndex = last, endIndex = match.range.first), Style.PLAIN)
            spans += spanOf(match)
            last = match.range.last + 1
        }
        if (last < text.length) spans += Span(text.substring(last), Style.PLAIN)
        return spans
    }

    /** [markdown] as card HTML (escaped; `<b>`, `<i>`, `<code>`, lists, small headings). */
    fun toHtml(markdown: String): String {
        val html = StringBuilder("""<div class="dsm-ai">""")
        var openList: String? = null
        for (block in parse(markdown)) {
            val list =
                when (block.kind) {
                    Kind.BULLET -> "ul"
                    Kind.NUMBERED -> "ol"
                    Kind.HEADING, Kind.PARAGRAPH -> null
                }
            if (list != openList) {
                openList?.let { html.append("</").append(it).append('>') }
                list?.let { html.append('<').append(it).append('>') }
                openList = list
            }
            val content = block.spans.joinToString("") { spanHtml(it) }
            when (block.kind) {
                Kind.HEADING -> html.append("<b>").append(content).append("</b><br>")
                Kind.BULLET, Kind.NUMBERED -> html.append("<li>").append(content).append("</li>")
                Kind.PARAGRAPH -> html.append("<p>").append(content).append("</p>")
            }
        }
        openList?.let { html.append("</").append(it).append('>') }
        return html.append("</div>").toString()
    }

    private fun lineBlock(line: String): Block? {
        val heading = HEADING.matchEntire(line)
        val bullet = BULLET.matchEntire(line)
        val numbered = NUMBERED.matchEntire(line)
        val block =
            when {
                heading != null -> {
                    Block(Kind.HEADING, inline(heading.groupValues[2]))
                }

                bullet != null -> {
                    Block(Kind.BULLET, inline(bullet.groupValues[2]), level = level(bullet.groupValues[1]))
                }

                numbered != null -> {
                    val values = numbered.groupValues
                    Block(kind = Kind.NUMBERED, spans = inline(values[3]), marker = values[2], level = level(values[1]))
                }

                else -> {
                    null
                }
            }
        return block
    }

    private fun level(indent: String): Int = indent.length / INDENT_PER_LEVEL

    private fun spanOf(match: MatchResult): Span {
        val styles =
            listOf(
                GROUP_BOLD_STARS to Style.BOLD,
                GROUP_BOLD_UNDERSCORES to Style.BOLD,
                GROUP_CODE to Style.CODE,
                GROUP_ITALIC_STAR to Style.ITALIC,
                GROUP_ITALIC_UNDERSCORE to Style.ITALIC,
            )
        return styles.firstNotNullOfOrNull { (group, style) ->
            match.groups[group]?.let { Span(it.value, style) }
        } ?: Span(match.value, Style.PLAIN)
    }

    private fun spanHtml(span: Span): String {
        val text = GlossaryHtml.escape(span.text)
        val html =
            when (span.style) {
                Style.PLAIN -> text
                Style.BOLD -> "<b>$text</b>"
                Style.ITALIC -> "<i>$text</i>"
                Style.CODE -> "<code>$text</code>"
            }
        return html
    }
}
