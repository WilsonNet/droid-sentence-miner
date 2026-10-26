package io.github.wilsonnet.droidsentenceminer.dict

/** Renders parsed glossary entries to sanitized HTML (used in the popup WebView and in Anki cards). */
object GlossaryHtml {

    /** Convert content nodes to HTML. [imageUrl] maps a dictionary-internal path to a displayable URL. */
    fun render(
        entries: List<GlossaryEntry>,
        imageUrl: (String) -> String? = { null },
        includeDeinflectionForms: Boolean = false,
        includeImages: Boolean = true,
    ): String {
        val sb = StringBuilder()
        for (entry in entries) {
            if (entry.isDeinflection) {
                sb.append("<div class=\"gloss-deinflection\">")
                sb.append(escape(entry.content.joinToString("") { (it as? GlossaryContent.Text)?.text ?: "" }))
                if (includeDeinflectionForms && entry.deinflectionForms.isNotEmpty()) {
                    sb.append("<span class=\"gloss-deinflection-forms\">")
                    sb.append(escape(entry.deinflectionForms.joinToString(", ")))
                    sb.append("</span>")
                }
                sb.append("</div>")
            } else {
                entry.content.forEach { sb.append(renderNode(it, imageUrl, includeImages)) }
            }
        }
        return sb.toString()
    }

    private fun renderNode(node: GlossaryContent, imageUrl: (String) -> String?, includeImages: Boolean): String = when (node) {
        is GlossaryContent.Text -> escape(node.text)
        is GlossaryContent.LineBreak -> "<br>"
        is GlossaryContent.Image -> {
            if (!includeImages) {
                val label = node.alt ?: node.title ?: "image"
                "<span class=\"gloss-image-placeholder\">[${escape(label)}]</span>"
            } else {
                val url = imageUrl(node.path) ?: node.path
                val styleParts = buildList {
                    val unit = node.sizeUnits ?: "px"
                    node.width?.let { add("width:${formatDim(it)}$unit") }
                    node.height?.let { add("height:${formatDim(it)}$unit") }
                }
                val styleAttr = if (styleParts.isNotEmpty()) " style=\"${escapeAttr(styleParts.joinToString("; "))}\"" else ""
                val titleAttr = node.title?.takeIf { it.isNotEmpty() }?.let { " title=\"${escapeAttr(it)}\"" } ?: ""
                "<span class=\"gloss-image-container\"><img src=\"${escapeAttr(url)}\"$styleAttr$titleAttr alt=\"${escapeAttr(node.alt ?: "")}\"></span>"
            }
        }
        is GlossaryContent.Element -> renderElement(node, imageUrl, includeImages)
    }

    private fun formatDim(v: Double): String =
        if (v == v.toLong().toDouble()) v.toLong().toString() else v.toString()

    private fun renderElement(node: GlossaryContent.Element, imageUrl: (String) -> String?, includeImages: Boolean): String {
        val tag = when (node.tag.lowercase()) {
            "div", "span", "ul", "ol", "li", "table", "thead", "tbody", "tfoot", "tr", "td", "th",
            "b", "strong", "i", "em", "u", "s", "sub", "sup", "ruby", "rt", "rp", "small", "p", "a",
            "details", "summary",
            -> node.tag.lowercase()
            "br" -> return "<br>"
            else -> "div"
        }
        val sb = StringBuilder("<$tag")
        for ((k, v) in node.data) {
            val dataKey = dataAttrKey(k)
            if (dataKey.isNotEmpty()) {
                sb.append(" data-sc-$dataKey=\"${escapeAttr(v)}\"")
            }
        }
        for ((k, v) in node.properties) {
            val attrName = when (k) {
                "title" -> "title"
                "lang" -> "lang"
                "colSpan" -> "colspan"
                "rowSpan" -> "rowspan"
                "href" -> if (tag == "a" && isSafeHref(v)) "href" else null
                "open" -> if (tag == "details") "open" else null
                else -> null
            } ?: continue
            sb.append(" $attrName=\"${escapeAttr(v)}\"")
        }
        val css = if (node.style.isNotEmpty()) {
            node.style.entries.joinToString("; ") { (k, v) -> "${cssKey(k)}: ${cssValue(v)}" }
        } else {
            ""
        }
        if (css.isNotEmpty()) {
            sb.append(" style=\"${escapeAttr(css)}\"")
        }
        sb.append(">")
        node.children.forEach { sb.append(renderNode(it, imageUrl, includeImages)) }
        sb.append("</$tag>")
        return sb.toString()
    }

    private fun isSafeHref(href: String): Boolean {
        val trimmed = href.trim().lowercase()
        return trimmed.startsWith("?") || trimmed.startsWith("http://") || trimmed.startsWith("https://") || trimmed.startsWith("#")
    }

    private fun dataAttrKey(key: String): String =
        key.replace(Regex("([a-z0-9])([A-Z])"), "$1-$2").lowercase().replace(Regex("[^a-z0-9_-]"), "")

    /** Very small CSS value sanitizer: strips anything that could break out of a style attribute. */
    private fun cssValue(value: String): String = value.replace("\"", "'").replace("<", "").replace(">", "")

    /** Converts camelCase structured-content style keys (`listStyleType`, `fontSize`) to CSS (`list-style-type`, `font-size`). */
    private fun cssKey(key: String): String =
        key.replace(Regex("([a-z0-9])([A-Z])"), "$1-$2").lowercase().replace(Regex("[^a-z-]"), "")

    fun escape(text: String): String = buildString(text.length) {
        text.forEach { ch ->
            when (ch) {
                '&' -> append("&amp;")
                '<' -> append("&lt;")
                '>' -> append("&gt;")
                '"' -> append("&quot;")
                else -> append(ch)
            }
        }
    }

    private fun escapeAttr(value: String): String = escape(value).replace("'", "&#39;")

    /** Full HTML document for the popup WebView. */
    fun wrapDocument(bodyHtml: String, styles: String? = null, darkMode: Boolean = false): String {
        val fg = if (darkMode) "#e6e6e6" else "#1a1a1a"
        val bg = if (darkMode) "#1e1e1e" else "#ffffff"
        val accent = if (darkMode) "#9ad1c0" else "#1B5E4A"
        return """
            <!DOCTYPE html>
            <html><head>
            <meta name="viewport" content="width=device-width, initial-scale=1, maximum-scale=1">
            <style>
            :root {
                color-scheme: ${if (darkMode) "dark" else "light"};
                --fg: $fg;
                --text-color: $fg;
                --bg: $bg;
                --background-color: $bg;
                --canvas: $bg;
                --accent: $accent;
                --font-size-no-units: 15;
            }
            html, body { margin:0; padding:0; background:$bg; color:$fg;
                font-family: -apple-system, 'Noto Sans JP', 'Hiragino Sans', sans-serif;
                font-size:15px; line-height:1.55; word-wrap:break-word; }
            .glossary { padding: 4px 0; }
            .gloss-dictionary { margin: 10px 0 2px; font-weight:600; font-size:0.85em; color:$accent; letter-spacing:0.02em; }
            .gloss-header { display:flex; flex-wrap:wrap; gap:4px; align-items:center; font-size:0.85em; margin:2px 0; }
            .gloss-tag { display:inline-block; padding:1px 6px; border-radius:4px; font-weight:600; font-size:0.9em; background:#8a8a91; color:#fff; }
            .gloss-tag[data-category="popular"] { background:#0275d8; }
            .gloss-tag[data-category="frequent"] { background:#5bc0de; color:#111; }
            .gloss-tag[data-category="archaism"] { background:#d9534f; }
            .gloss-tag[data-category="partOfSpeech"] { background:#565656; }
            .gloss-tag[data-category="name"] { background:#5cb85c; }
            .gloss-tag[data-category="expression"] { background:#f0ad4e; color:#111; }
            .gloss-frequency { display:inline-block; padding:1px 6px; border-radius:6px; background:rgba(27,94,74,0.15); }
            .gloss-deinflection { color: $accent; font-size:0.9em; margin-top:4px; }
            .gloss-deinflection-forms { opacity:0.7; margin-left:6px; }
            a { color: ${if (darkMode) "#8ab4f8" else "#1a73e8"}; text-decoration: none; }
            img, svg { max-width:100%; height:auto; }
            ul, ol { margin:4px 0; padding-left:20px; }
            li { margin:2px 0; }
            table { border-collapse: collapse; }
            th, td { border: 1px solid rgba(128,128,128,0.35); padding: 2px 6px; }
            ${styles.orEmpty()}
            </style></head>
            <body><div class="glossary">$bodyHtml</div></body></html>
        """.trimIndent()
    }
}
