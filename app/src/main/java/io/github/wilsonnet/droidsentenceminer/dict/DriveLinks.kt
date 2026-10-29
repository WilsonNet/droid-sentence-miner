package io.github.wilsonnet.droidsentenceminer.dict

import java.net.URLDecoder

/**
 * Google Drive share links, without the Drive API or a login: public files download from
 * `drive.usercontent.google.com` and public folders list through `embeddedfolderview`
 * (the HTML Drive serves for folder embeds). Private files go through Android's file picker
 * instead, where the Google Drive app is a document provider.
 */
object DriveLinks {

    sealed class Link {
        abstract val id: String

        data class File(override val id: String) : Link()
        data class Folder(override val id: String) : Link()
    }

    /** One row of a public folder listing. */
    data class Entry(val id: String, val name: String, val isFolder: Boolean, val modified: String? = null) {
        val isZip: Boolean get() = !isFolder && name.endsWith(".zip", ignoreCase = true)
    }

    private val ID = "[A-Za-z0-9_-]{10,}"
    private val folderPattern = Regex("""/folders/($ID)""")
    private val filePattern = Regex("""/file/(?:u/\d+/)?d/($ID)""")
    private val idParamPattern = Regex("""[?&]id=($ID)""")

    /** Parses any Drive file or folder link (share, open, uc, usercontent). Null if it isn't one. */
    fun parse(text: String): Link? {
        val url = text.trim()
        if (!url.contains("drive.google.com") && !url.contains("drive.usercontent.google.com") &&
            !url.contains("docs.google.com")
        ) {
            return null
        }
        folderPattern.find(url)?.let { return Link.Folder(it.groupValues[1]) }
        filePattern.find(url)?.let { return Link.File(it.groupValues[1]) }
        idParamPattern.find(url)?.let { return Link.File(it.groupValues[1]) }
        return null
    }

    /** Direct download that skips Drive's "can't scan for viruses" page for big files. */
    fun downloadUrl(fileId: String): String =
        "https://drive.usercontent.google.com/download?id=$fileId&export=download&confirm=t"

    fun folderListUrl(folderId: String): String = "https://drive.google.com/embeddedfolderview?id=$folderId"

    fun folderUrl(folderId: String): String = "https://drive.google.com/drive/folders/$folderId"

    private val entryPattern = Regex(
        """<div class="flip-entry" id="entry-($ID)".*?<a href="([^"]+)".*?<div class="flip-entry-title">(.*?)</div>""" +
            """(?:.*?<div class="flip-entry-last-modified"><div>(.*?)</div>)?""",
        RegexOption.DOT_MATCHES_ALL,
    )

    /**
     * Rows of an `embeddedfolderview` page, folders first then by name. Null when the page has no
     * listing at all (folder not shared publicly: Drive answers with a sign-in page).
     */
    fun parseFolder(html: String): List<Entry>? {
        if (!html.contains("flip-entries")) return null
        // Split per entry so a lazy match can't run into the next row's title.
        val chunks = html.split("<div class=\"flip-entry\" ").drop(1).map { "<div class=\"flip-entry\" $it" }
        return chunks.mapNotNull { chunk ->
            val match = entryPattern.find(chunk) ?: return@mapNotNull null
            val (id, href, title, modified) = match.destructured
            Entry(
                id = id,
                name = unescape(title),
                isFolder = href.contains("/folders/"),
                modified = modified.takeIf { it.isNotBlank() }?.let(::unescape),
            )
        }.sortedWith(compareBy<Entry> { !it.isFolder }.thenBy { it.name.lowercase() })
    }

    /** The title of an `embeddedfolderview` page (the folder's name). */
    fun folderTitle(html: String): String? =
        Regex("""<title>(.*?)</title>""", RegexOption.DOT_MATCHES_ALL).find(html)
            ?.groupValues?.get(1)?.let(::unescape)?.trim()?.takeIf { it.isNotEmpty() }

    /**
     * Drive's "Download anyway" page is a GET form with hidden inputs; returns the URL it submits
     * to, or null if the page isn't one (e.g. a sign-in or quota page).
     */
    fun confirmFormUrl(html: String): String? {
        val form = Regex("""<form[^>]*id="download-form"[^>]*action="([^"]+)"[^>]*>(.*?)</form>""", RegexOption.DOT_MATCHES_ALL)
            .find(html) ?: return null
        val action = unescape(form.groupValues[1])
        val params = Regex("""<input[^>]*type="hidden"[^>]*>""").findAll(form.groupValues[2]).mapNotNull { input ->
            val name = Regex("""name="([^"]*)"""").find(input.value)?.groupValues?.get(1) ?: return@mapNotNull null
            val value = Regex("""value="([^"]*)"""").find(input.value)?.groupValues?.get(1).orEmpty()
            "$name=${unescape(value)}"
        }.toList()
        if (params.isEmpty()) return null
        return action + (if (action.contains('?')) "&" else "?") + params.joinToString("&")
    }

    /** File name from `Content-Disposition` (`filename*=UTF-8''…` preferred over `filename="…"`). */
    fun fileName(contentDisposition: String?): String? {
        if (contentDisposition == null) return null
        Regex("""filename\*=UTF-8''([^;]+)""", RegexOption.IGNORE_CASE).find(contentDisposition)?.let {
            return runCatching { URLDecoder.decode(it.groupValues[1].trim(), "UTF-8") }.getOrNull()
        }
        return Regex("""filename="([^"]*)"""", RegexOption.IGNORE_CASE).find(contentDisposition)?.groupValues?.get(1)
    }

    private fun unescape(text: String): String = text
        .replace("&amp;", "&")
        .replace("&lt;", "<")
        .replace("&gt;", ">")
        .replace("&quot;", "\"")
        .replace("&#39;", "'")
        .replace(Regex("""&#(\d+);""")) { it.groupValues[1].toIntOrNull()?.let { c -> String(Character.toChars(c)) } ?: it.value }
        .replace(Regex("""&#x([0-9a-fA-F]+);""")) { it.groupValues[1].toIntOrNull(16)?.let { c -> String(Character.toChars(c)) } ?: it.value }
}
