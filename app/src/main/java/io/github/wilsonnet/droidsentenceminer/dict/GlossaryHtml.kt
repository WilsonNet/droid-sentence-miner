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
                val width = node.width?.let { " width=\"$it\"" } ?: ""
                val height = node.height?.let { " height=\"$it\"" } ?: ""
                "<img src=\"${escapeAttr(url)}\"$width$height alt=\"${escapeAttr(node.alt ?: "")}\">"
            }
        }
        is GlossaryContent.Element -> renderElement(node, imageUrl, includeImages)
    }

    private fun renderElement(node: GlossaryContent.Element, imageUrl: (String) -> String?, includeImages: Boolean): String {
        val tag = when (node.tag.lowercase()) {
            "div", "span", "ul", "ol", "li", "table", "thead", "tbody", "tr", "td", "th",
            "b", "strong", "i", "em", "u", "s", "sub", "sup", "ruby", "rt", "rp", "small", "p", "a",
            -> node.tag.lowercase()
            "br" -> "br"
            else -> "div"
        }
        val sb = StringBuilder("<$tag")
        if (node.style.isNotEmpty()) {
            val css = node.style.entries.joinToString("; ") { (k, v) -> "${cssKey(k)}: ${cssValue(v)}" }
            sb.append(" style=\"${escapeAttr(css)}\"")
        } else if (node.data.isNotEmpty()) {
            val css = dataToCss(node)
            if (css.isNotEmpty()) sb.append(" style=\"${escapeAttr(css)}\"")
        }
        sb.append(">")
        node.children.forEach { sb.append(renderNode(it, imageUrl, includeImages)) }
        sb.append("</$tag>")
        return sb.toString()
    }

    private fun dataToCss(node: GlossaryContent.Element): String {
        val sb = StringBuilder()
        if (node.data["class"]?.contains("tag") == true) {
            sb.append("display:inline-block; padding:0 4px; margin-right:4px; border-radius:4px; background:rgba(128,128,128,0.2); font-size:0.85em;")
        }
        if (node.data.containsKey("frequency")) {
            sb.append("display:inline-block; padding:0 4px; margin-right:4px; border-radius:4px; background:rgba(128,128,128,0.2); font-size:0.85em;")
        }
        return sb.toString()
    }

    /** Very small CSS value sanitizer: strips anything that could break out of a style attribute. */
    private fun cssValue(value: String): String = value.replace("\"", "'").replace("<", "").replace(">", "")

    private fun cssKey(key: String): String = key.replace(Regex("[^a-zA-Z-]"), "")

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
            :root { color-scheme: ${if (darkMode) "dark" else "light"}; }
            html, body { margin:0; padding:0; background:$bg; color:$fg;
                font-family: -apple-system, 'Noto Sans JP', 'Hiragino Sans', sans-serif;
                font-size:15px; line-height:1.55; word-wrap:break-word; }
            .glossary { padding: 4px 0; }
            .gloss-dictionary { margin: 10px 0 2px; font-weight:600; font-size:0.85em; color:$accent; letter-spacing:0.02em; }
            .gloss-header { display:flex; flex-wrap:wrap; gap:4px; align-items:center; font-size:0.85em; margin:2px 0; }
            .gloss-tag { display:inline-block; padding:1px 6px; border-radius:6px; background:rgba(128,128,128,0.18); }
            .gloss-frequency { display:inline-block; padding:1px 6px; border-radius:6px; background:rgba(27,94,74,0.15); }
            .gloss-deinflection { color: $accent; font-size:0.9em; margin-top:4px; }
            .gloss-deinflection-forms { opacity:0.7; margin-left:6px; }
            img, svg { max-width:100%; height:auto; }
            ul, ol { margin:4px 0; padding-left:20px; }
            li { margin:2px 0; }
            table { border-collapse: collapse; }
            td, th { padding: 2px 6px; }
            $styles
            </style></head>
            <body><div class="glossary">$bodyHtml</div></body></html>
        """.trimIndent()
    }
}
