package io.github.wilsonnet.droidsentenceminer.manga

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder
import java.util.zip.ZipEntry
import java.util.zip.ZipOutputStream

class CbzArchiveTest {
    @get:Rule
    val tempFolder = TemporaryFolder()

    @Test
    fun naturalCompareSortsNumericPagesInOrder() {
        val input = listOf("page10.jpg", "page2.jpg", "page1.jpg", "page20.png", "cover.jpg")
        val sorted = input.sortedWith(CbzArchive::naturalCompare)
        assertEquals(listOf("cover.jpg", "page1.jpg", "page2.jpg", "page10.jpg", "page20.png"), sorted)
    }

    @Test
    fun inspectsSortedImagesIgnoresMacOsxAndFindsMokuro() {
        val cbz = tempFolder.newFile("sample.cbz")
        cbz.outputStream().use { out ->
            ZipOutputStream(out).use { zip ->
                val entries =
                    listOf(
                        "__MACOSX/._1.jpg" to "junk",
                        "ch1/10.jpg" to "img10",
                        "ch1/2.jpg" to "img2",
                        "ch1/1.png" to "img1",
                        "sample.mokuro" to """{"pages":[]}""",
                    )
                for ((name, body) in entries) {
                    zip.putNextEntry(ZipEntry(name))
                    zip.write(body.toByteArray())
                    zip.closeEntry()
                }
            }
        }

        val inspected = CbzArchive.inspect(cbz)
        assertEquals(listOf("ch1/1.png", "ch1/2.jpg", "ch1/10.jpg"), inspected.imageEntries)
        assertEquals("sample.mokuro", inspected.embeddedMokuroEntry)
        val mokuroText = CbzArchive.readTextEntry(cbz, inspected.embeddedMokuroEntry!!)
        assertTrue(mokuroText!!.contains("pages"))
    }
}
