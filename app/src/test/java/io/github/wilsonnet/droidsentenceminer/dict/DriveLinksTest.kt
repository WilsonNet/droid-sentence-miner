package io.github.wilsonnet.droidsentenceminer.dict

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

class DriveLinksTest {

    @Test
    fun parsesShareLinks() {
        assertEquals(
            DriveLinks.Link.Folder("1aybpEp9_-JlOpvnJ8-5c0qlfachb83nA"),
            DriveLinks.parse("https://drive.google.com/drive/folders/1aybpEp9_-JlOpvnJ8-5c0qlfachb83nA?usp=share_link"),
        )
        assertEquals(
            DriveLinks.Link.Folder("1xe0wTHq9BI08lQdouOBLUrKML1abemB5"),
            DriveLinks.parse("  https://drive.google.com/drive/u/1/folders/1xe0wTHq9BI08lQdouOBLUrKML1abemB5\n"),
        )
        assertEquals(
            DriveLinks.Link.File("1DIFD5bd_ZGURvmq5ZfVluJH6U6gUahOx"),
            DriveLinks.parse("https://drive.google.com/file/d/1DIFD5bd_ZGURvmq5ZfVluJH6U6gUahOx/view?usp=drive_link"),
        )
        assertEquals(
            DriveLinks.Link.File("137gaEvCxw54ffVadAAjzDxDWywAiTlJh"),
            DriveLinks.parse("https://drive.google.com/open?id=137gaEvCxw54ffVadAAjzDxDWywAiTlJh"),
        )
        assertEquals(
            DriveLinks.Link.File("137gaEvCxw54ffVadAAjzDxDWywAiTlJh"),
            DriveLinks.parse("https://drive.usercontent.google.com/download?id=137gaEvCxw54ffVadAAjzDxDWywAiTlJh&export=download"),
        )
        assertNull(DriveLinks.parse("https://example.com/file/d/137gaEvCxw54ffVadAAjzDxDWywAiTlJh"))
        assertNull(DriveLinks.parse("https://drive.google.com/drive/my-drive"))
    }

    @Test
    fun parsesFolderListing() {
        val html = """<html><head><title>Yomichan &amp; more</title></head><body><div class="flip-entries">""" +
            entry("1zXCHcxesRwNAFFqdoaFe57G8Bvkcan_8", "https://drive.google.com/drive/folders/1zXCHcxesRwNAFFqdoaFe57G8Bvkcan_8", "src", "8/7/21") +
            entry("137gaEvCxw54ffVadAAjzDxDWywAiTlJh", "https://drive.google.com/file/d/137gaEvCxw54ffVadAAjzDxDWywAiTlJh/view?usp=drive_web", "Words.hk C-E.zip", "4/12/23") +
            entry("1n5vzMURfkXrTSTm7fdKZeocktVbRit0K", "https://drive.google.com/file/d/1n5vzMURfkXrTSTm7fdKZeocktVbRit0K/view?usp=drive_web", "[JA] 明鏡 &#39;用例&#39;.zip", "4/12/23") +
            entry("14k4t-erVGCac0Tf9QeZ8LB5xezr7BZBU", "https://drive.google.com/file/d/14k4t-erVGCac0Tf9QeZ8LB5xezr7BZBU/view?usp=drive_web", "info-changelog.txt", "1/1/24") +
            "</div></body></html>"
        val entries = DriveLinks.parseFolder(html)!!
        assertEquals(listOf("src", "[JA] 明鏡 '用例'.zip", "info-changelog.txt", "Words.hk C-E.zip"), entries.map { it.name })
        assertEquals(listOf(true, false, false, false), entries.map { it.isFolder })
        assertEquals(listOf(false, true, false, true), entries.map { it.isZip })
        assertEquals("8/7/21", entries[0].modified)
        assertEquals("Yomichan & more", DriveLinks.folderTitle(html))
    }

    @Test
    fun privateFolderHasNoListing() {
        assertNull(DriveLinks.parseFolder("<html><title>Sign in - Google Accounts</title></html>"))
        assertEquals(emptyList<DriveLinks.Entry>(), DriveLinks.parseFolder("""<div class="flip-entries"></div>"""))
    }

    @Test
    fun followsVirusScanConfirmForm() {
        val html = """<form id="download-form" action="https://drive.usercontent.google.com/download" method="get">""" +
            """<input type="submit" id="uc-download-link" value="Download anyway"/>""" +
            """<input type="hidden" name="id" value="1E-SLhGTg_O0D2OQ0Gxzq-Dqvg_eclGnS">""" +
            """<input type="hidden" name="export" value="download">""" +
            """<input type="hidden" name="confirm" value="t">""" +
            """<input type="hidden" name="uuid" value="8137b4ae-af46-42ca-bf5d-49fc93d56d23"></form>"""
        assertEquals(
            "https://drive.usercontent.google.com/download?id=1E-SLhGTg_O0D2OQ0Gxzq-Dqvg_eclGnS&export=download&confirm=t&uuid=8137b4ae-af46-42ca-bf5d-49fc93d56d23",
            DriveLinks.confirmFormUrl(html),
        )
        assertNull(DriveLinks.confirmFormUrl("<html>Sign in</html>"))
    }

    @Test
    fun readsContentDispositionNames() {
        assertEquals("Canto CEDICT.zip", DriveLinks.fileName("attachment; filename=\"Canto CEDICT.zip\""))
        assertEquals(
            "[JA] 大辞林.zip",
            DriveLinks.fileName("attachment; filename=\"_JA_ .zip\"; filename*=UTF-8''%5BJA%5D%20%E5%A4%A7%E8%BE%9E%E6%9E%97.zip"),
        )
        assertNull(DriveLinks.fileName(null))
    }

    private fun entry(id: String, href: String, title: String, modified: String) =
        """<div class="flip-entry" id="entry-$id" tabindex="0" role="link"><div class="flip-entry-info">""" +
            """<a href="$href" target="_blank"><div class="flip-entry-visual"></div>""" +
            """<div class="flip-entry-title">$title</div></a></div>""" +
            """<div class="flip-entry-last-modified"><div>$modified</div></div></div>"""
}
