package io.github.wilsonnet.droidsentenceminer.update

import io.github.wilsonnet.droidsentenceminer.ui.components.ChangelogBlocks
import org.junit.Assert.assertEquals
import org.junit.Test

class ChangelogTest {
    private val md =
        listOf(
            "# Changelog",
            "",
            "Intro text.",
            "",
            "## [Unreleased]",
            "",
            "### Added",
            "- Not released yet.",
            "",
            "## [0.7.0] — 2026-11-10",
            "",
            "### Added",
            "- **German.** Words and",
            "  phrases.",
            "",
            "## [0.6.1] — 2026-11-09",
            "",
            "### Fixed",
            "- A fix.",
            "",
            "## [0.6.0] — 2026-11-08",
            "",
            "### Added",
            "- English.",
        ).joinToString(separator = "\n")

    @Test
    fun `sections skip unreleased and keep the file order`() {
        val sections = Changelog.sections(md)
        assertEquals(listOf("0.7.0", "0.6.1", "0.6.0"), sections.map { it.version })
        assertEquals("2026-11-10", sections.first().date)
    }

    @Test
    fun `an update shows every release since the seen one`() {
        assertEquals(
            listOf("0.7.0", "0.6.1"),
            Changelog.forUpdate(md, seen = "0.6.0", current = "0.7.0").map {
                it.version
            },
        )
        assertEquals(listOf("0.6.1"), Changelog.forUpdate(md, seen = "0.6.0", current = "0.6.1").map { it.version })
    }

    @Test
    fun `without a seen version only the current notes`() {
        assertEquals(listOf("0.7.0"), Changelog.forUpdate(md, seen = null, current = "0.7.0").map { it.version })
    }

    @Test
    fun `a version missing from the changelog shows the newest notes`() {
        assertEquals(listOf("0.7.0"), Changelog.forUpdate(md, seen = "0.1.0", current = "0.1.0").map { it.version })
        assertEquals(emptyList<ChangelogSection>(), Changelog.forUpdate("", seen = null, current = "1.0.0"))
    }

    @Test
    fun `blocks join wrapped bullets and style inline markdown`() {
        val blocks = ChangelogBlocks.parse(Changelog.sections(md).first().body)
        assertEquals(
            listOf(ChangelogBlocks.Block.Heading("Added"), ChangelogBlocks.Block.Bullet("**German.** Words and phrases.")),
            blocks,
        )
        val pieces = ChangelogBlocks.inline("**Bold** then *Settings*, `code` and [#1](https://x)")
        assertEquals(
            listOf(
                ChangelogBlocks.Piece("Bold", ChangelogBlocks.Inline.BOLD),
                ChangelogBlocks.Piece(" then ", ChangelogBlocks.Inline.PLAIN),
                ChangelogBlocks.Piece("Settings", ChangelogBlocks.Inline.ITALIC),
                ChangelogBlocks.Piece(", ", ChangelogBlocks.Inline.PLAIN),
                ChangelogBlocks.Piece("code", ChangelogBlocks.Inline.CODE),
                ChangelogBlocks.Piece(" and ", ChangelogBlocks.Inline.PLAIN),
                ChangelogBlocks.Piece("#1", ChangelogBlocks.Inline.PLAIN),
            ),
            pieces,
        )
    }

    @Test
    fun `link reference definitions at the end are not notes`() {
        val markdown = listOf("## [0.1.0] — 2026-01-01", "", "- First.", "", "[0.1.0]: https://example.com/v0.1.0").joinToString("\n")
        assertEquals("- First.", Changelog.sections(markdown).single().body)
    }
}
