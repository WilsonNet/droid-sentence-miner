package io.github.wilsonnet.droidsentenceminer.properties

import io.github.wilsonnet.droidsentenceminer.update.AppVersion
import io.github.wilsonnet.droidsentenceminer.update.Changelog
import io.kotest.property.Arb
import io.kotest.property.arbitrary.arbitrary
import io.kotest.property.arbitrary.int
import io.kotest.property.arbitrary.list
import io.kotest.property.arbitrary.orNull
import io.kotest.property.checkAll
import kotlinx.coroutines.test.runTest
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

/** Version ordering and the What's new selection over generated changelogs. */
class VersionPropertiesTest {
    private val versions = arbitrary { "${Arb.int(0..3).bind()}.${Arb.int(0..12).bind()}.${Arb.int(0..5).bind()}" }

    private fun cmp(a: String, b: String) = AppVersion.compare(a = a, b = b)

    private fun changelog(versions: List<String>): String {
        val head = listOf("# Changelog", "", "## [Unreleased]", "", "- soon", "")
        val sections =
            versions.flatMap { version ->
                listOf("## [$version] — 2026-01-01", "", "### Added", "- Change in $version.", "")
            }
        return (head + sections).joinToString(separator = "\n")
    }

    @Test
    fun `version comparison is a total order`() {
        runTest {
            checkAll(versions, versions, versions) { a, b, c ->
                assertEquals(Integer.signum(cmp(a = a, b = b)), -Integer.signum(cmp(a = b, b = a)))
                assertEquals(0, cmp(a = a, b = "v$a"))
                val isOrdered = cmp(a = a, b = b) <= 0 && cmp(a = b, b = c) <= 0
                if (isOrdered) assertTrue(cmp(a = a, b = c) <= 0)
            }
        }
    }

    @Test
    fun `what's new never shows unreleased, future or already seen versions`() {
        runTest {
            checkAll(
                genA = Arb.list(gen = versions, range = 0..8),
                genB = versions.orNull(),
                genC = versions,
            ) { released, seen, current ->
                val newestFirst = released.distinct().sortedWith { a, b -> cmp(a = b, b = a) }
                val markdown = changelog(newestFirst)
                val shown = Changelog.forUpdate(markdown = markdown, seen = seen, current = current)
                assertTrue(shown.size <= MAX_SHOWN)
                assertEquals(newestFirst, Changelog.sections(markdown).map { section -> section.version })
                val isUpdateRange =
                    shown.all { section ->
                        cmp(a = section.version, b = current) <= 0 && (seen == null || cmp(a = section.version, b = seen) > 0)
                    }
                // Either the update's sections, or the newest one as a fallback.
                assertTrue("$shown for ${seen.orEmpty()} → $current", isUpdateRange || shown == Changelog.sections(markdown).take(1))
            }
        }
    }

    private companion object {
        const val MAX_SHOWN = 5
    }
}
