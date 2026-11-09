package io.github.wilsonnet.droidsentenceminer.ui.components

/** The small Markdown subset CHANGELOG.md uses, split into blocks and styled runs. Pure Kotlin, unit tested. */
object ChangelogBlocks {
    private val INLINE = Regex("""\*\*(.+?)\*\*|`([^`]+)`|\[([^]]+)]\([^)]*\)|\*([^*\s][^*]*?)\*""")
    private const val BULLET_MARK_LENGTH = 2

    /** A block of a changelog section; [text] keeps its inline Markdown. */
    sealed interface Block {
        /** The block's text. */
        val text: String

        /** `### Added`. */
        data class Heading(override val text: String) : Block

        /** `- item`, wrapped lines joined. */
        data class Bullet(override val text: String) : Block

        /** Any other text. */
        data class Paragraph(override val text: String) : Block
    }

    /** Inline style of a run. */
    enum class Inline {
        /** Unstyled text (links show their text). */
        PLAIN,

        /** `**bold**`. */
        BOLD,

        /** `*italic*`. */
        ITALIC,

        /** `` `code` ``. */
        CODE,
    }

    /**
     * A styled run of text.
     *
     * @property text The run's characters, markup removed.
     * @property style How it is shown.
     */
    data class Piece(val text: String, val style: Inline)

    /** Blocks of [body]: headings, bullets (wrapped lines joined) and paragraphs. */
    fun parse(body: String): List<Block> {
        val blocks = mutableListOf<Block>()
        var current: StringBuilder? = null
        var isBullet = false
        for (raw in body.lines() + "") {
            val line = raw.trim()
            val isNewBlock = line.isEmpty() || line.startsWith("#") || line.startsWith("- ") || line.startsWith("* ")
            if (isNewBlock) {
                current?.let { text -> blocks += blockOf(text = text.toString().trim(), isBullet = isBullet) }
                current = null
            }
            when {
                line.isEmpty() -> {
                    // A blank line only ends the block.
                }

                line.startsWith("#") -> {
                    blocks += Block.Heading(line.trimStart('#').trim())
                }

                isNewBlock -> {
                    isBullet = true
                    current = StringBuilder(line.drop(BULLET_MARK_LENGTH))
                }

                current == null -> {
                    isBullet = false
                    current = StringBuilder(line)
                }

                else -> {
                    current.append(' ').append(line)
                }
            }
        }
        return blocks
    }

    private fun blockOf(text: String, isBullet: Boolean): Block =
        if (isBullet) Block.Bullet(text) else Block.Paragraph(text)

    /** Styled runs of one block's [text]. */
    fun inline(text: String): List<Piece> {
        val pieces = mutableListOf<Piece>()
        var at = 0
        for (match in INLINE.findAll(text)) {
            if (match.range.first > at) pieces += Piece(text.substring(startIndex = at, endIndex = match.range.first), Inline.PLAIN)
            pieces += pieceOf(match.groupValues)
            at = match.range.last + 1
        }
        if (at < text.length) pieces += Piece(text.substring(at), Inline.PLAIN)
        return pieces
    }

    // The regex groups after the whole match: bold, code, link text, italic.
    private fun pieceOf(groups: List<String>): Piece {
        val styles = listOf(Inline.BOLD, Inline.CODE, Inline.PLAIN, Inline.ITALIC)
        val index = groups.drop(1).indexOfFirst { it.isNotEmpty() }.coerceAtLeast(0)
        return Piece(groups[index + 1], styles[index])
    }
}
