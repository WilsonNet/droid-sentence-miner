package io.github.wilsonnet.droidsentenceminer.dict

/** Renders parsed glossary entries to sanitized HTML (used in the popup WebView and in Anki cards). */
object GlossaryHtml {

    private val camelBoundary = Regex("([a-z0-9])([A-Z])")
    private val nonDataKeyChars = Regex("[^a-z0-9_-]")
    private val nonCssKeyChars = Regex("[^a-z-]")

    // Structured content repeats the same few keys (class, content, fontSize, ...) on every node.
    private val dataKeys = java.util.concurrent.ConcurrentHashMap<String, String>()
    private val cssKeys = java.util.concurrent.ConcurrentHashMap<String, String>()

    /**
     * Convert content nodes to HTML. [imageUrl] maps a dictionary-internal path to a displayable URL
     * (null = not available: the image becomes its `[alt]` placeholder). [inlineImageStyles] writes
     * the image layout as style attributes, like Yomitan's Anki output (`structured-content-style.json`),
     * for Anki cards where [IMAGE_CSS] is not on the page. [dictionary] goes into `data-dictionary`.
     */
    fun render(
        entries: List<GlossaryEntry>,
        imageUrl: (String) -> String? = { it },
        includeDeinflectionForms: Boolean = false,
        includeImages: Boolean = true,
        inlineImageStyles: Boolean = false,
        dictionary: String? = null,
    ): String {
        val sb = StringBuilder()
        renderTo(sb, entries, imageUrl, includeDeinflectionForms, includeImages, inlineImageStyles, dictionary)
        return sb.toString()
    }

    /** [render] appending to [sb], without building a string per node. */
    fun renderTo(
        sb: StringBuilder,
        entries: List<GlossaryEntry>,
        imageUrl: (String) -> String? = { it },
        includeDeinflectionForms: Boolean = false,
        includeImages: Boolean = true,
        inlineImageStyles: Boolean = false,
        dictionary: String? = null,
    ) {
        val ctx = RenderContext(imageUrl, includeImages, inlineImageStyles, dictionary)
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
                entry.content.forEach { renderNode(sb, it, ctx) }
            }
        }
    }

    private class RenderContext(
        val imageUrl: (String) -> String?,
        val includeImages: Boolean,
        val inline: Boolean,
        val dictionary: String?,
    )

    private fun renderNode(sb: StringBuilder, node: GlossaryContent, ctx: RenderContext) {
        when (node) {
            is GlossaryContent.Text -> appendEscaped(sb, node.text)
            is GlossaryContent.LineBreak -> sb.append("<br>")
            is GlossaryContent.Image -> {
                val url = if (ctx.includeImages && node.path.isNotEmpty()) ctx.imageUrl(node.path) else null
                if (url == null) {
                    val label = node.alt?.takeIf { it.isNotBlank() } ?: node.title?.takeIf { it.isNotBlank() } ?: "image"
                    sb.append("<span class=\"gloss-image-placeholder\">[").append(escape(label)).append("]</span>")
                } else {
                    sb.append(renderImage(node, url, ctx))
                }
            }
            is GlossaryContent.Element -> renderElement(sb, node, ctx)
        }
    }

    private fun formatDim(v: Double): String {
        if (v == v.toLong().toDouble()) return v.toLong().toString()
        return "%.4f".format(java.util.Locale.ROOT, v).trimEnd('0').trimEnd('.')
    }

    /**
     * Yomitan's `createDefinitionImage`: `a.gloss-image-link > span.gloss-image-container >
     * (span.gloss-image-sizer, span.gloss-image-background, span.gloss-image-container-overlay, img.gloss-image)`
     * plus `span.gloss-image-link-text`, with the same data attributes, so dictionary CSS written for
     * Yomitan applies. Differences: the size the dictionary gives (`width`/`height`, Yomitan's
     * preferred size) is used directly; with only one of them (or none) the image's real aspect
     * ratio comes from the browser instead of a padding sizer, since we don't read every file's
     * dimensions at import. Collapsed images are a `<details>` (tap to open) instead of hover.
     */
    private fun renderImage(node: GlossaryContent.Image, url: String, ctx: RenderContext): String {
        val inline = ctx.inline
        val em = node.sizeUnits == "em"
        val unit = if (em) "em" else "px"
        val width = node.displayWidth
        val height = node.displayHeight
        val hasAspectRatio = width != null && height != null
        val rendering = node.imageRendering ?: if (node.pixelated == true) "pixelated" else "auto"
        val appearance = node.appearance ?: "auto"
        val monochrome = appearance == "monochrome"
        val background = node.background ?: true
        val collapsed = node.collapsed ?: false
        val collapsible = node.collapsible ?: true
        val verticalAlign = node.verticalAlign?.takeIf { it in VERTICAL_ALIGN }

        val link = StringBuilder("<a class=\"gloss-image-link\"")
        link.append(" data-path=\"").append(escapeAttr(node.path)).append('"')
        ctx.dictionary?.let { link.append(" data-dictionary=\"").append(escapeAttr(it)).append('"') }
        link.append(" data-has-aspect-ratio=\"").append(hasAspectRatio).append('"')
        link.append(" data-image-rendering=\"").append(escapeAttr(rendering)).append('"')
        link.append(" data-appearance=\"").append(escapeAttr(appearance)).append('"')
        link.append(" data-background=\"").append(background).append('"')
        link.append(" data-collapsed=\"").append(collapsed).append('"')
        link.append(" data-collapsible=\"").append(collapsible).append('"')
        verticalAlign?.let { link.append(" data-vertical-align=\"").append(it).append('"') }
        if (node.sizeUnits != null && (width != null || height != null)) {
            link.append(" data-size-units=\"").append(escapeAttr(node.sizeUnits)).append('"')
        }
        if (inline) {
            val va = when (verticalAlign) {
                null -> null
                "text-top" -> "top"
                "text-bottom" -> "bottom"
                else -> verticalAlign
            }
            link.append(" style=\"").append(INLINE_LINK).append(va?.let { ";vertical-align:$it" } ?: "").append('"')
        }
        link.append('>')

        // Container: the image's box. Its width is the preferred width; max-width keeps it inside the popup.
        val containerStyle = buildList {
            if (inline) add(INLINE_CONTAINER)
            if (width != null) add("width:${formatDim(width)}$unit")
            node.border?.let { add("border:${cssValue(it)}") }
            node.borderRadius?.let { add("border-radius:${cssValue(it)}") }
        }.joinToString(";")
        link.append("<span class=\"gloss-image-container\"")
        if (containerStyle.isNotEmpty()) link.append(" style=\"").append(escapeAttr(containerStyle)).append('"')
        node.title?.takeIf { it.isNotEmpty() }?.let { link.append(" title=\"").append(escapeAttr(it)).append('"') }
        link.append('>')
        if (hasAspectRatio) {
            val padding = "padding-top:${formatDim(height!! / width!! * 100)}%"
            link.append("<span class=\"gloss-image-sizer\" style=\"")
                .append(if (inline) "$INLINE_SIZER;$padding" else padding).append("\"></span>")
        }
        val cssUrl = "url(\"${url.replace("\"", "%22")}\")"
        if (monochrome) {
            // Yomitan's Anki styles: the image is a mask over a currentColor box, so line art follows the text color.
            val style = if (inline) "$INLINE_BACKGROUND;-webkit-mask-image:$cssUrl;mask-image:$cssUrl" else "--image:$cssUrl"
            link.append("<span class=\"gloss-image-background\" style=\"").append(escapeAttr(style)).append("\"></span>")
        } else if (!inline) {
            link.append("<span class=\"gloss-image-background\"></span>")
        }
        if (!inline) link.append("<span class=\"gloss-image-container-overlay\"></span>")

        val imgStyle = buildList {
            if (inline) {
                add(INLINE_IMAGE)
                if (hasAspectRatio) add(INLINE_IMAGE_FILL)
                when (rendering) {
                    "pixelated" -> add("image-rendering:-webkit-optimize-contrast;image-rendering:pixelated")
                    "crisp-edges" -> add("image-rendering:-webkit-optimize-contrast;image-rendering:crisp-edges")
                }
                if (monochrome) add("opacity:0")
            }
            if (!hasAspectRatio) {
                when {
                    width != null -> add("width:100%;height:auto")
                    height != null -> add("height:${formatDim(height)}$unit;width:auto")
                    inline -> add("max-width:100%;height:auto")
                }
            }
        }.joinToString(";")
        link.append("<img class=\"gloss-image\" src=\"").append(escapeAttr(url)).append('"')
        link.append(" alt=\"").append(escapeAttr(node.alt ?: "")).append('"')
        if (imgStyle.isNotEmpty()) link.append(" style=\"").append(escapeAttr(imgStyle)).append('"')
        if (!inline) link.append(" decoding=\"async\"")
        link.append("></span>")
        if (!inline) link.append("<span class=\"gloss-image-link-text\">Image</span>")
        link.append("</a>")

        val description = node.description?.takeIf { it.isNotBlank() }?.let {
            val style = if (inline) " style=\"display:block;white-space:pre-line\"" else ""
            "<span class=\"gloss-image-description\"$style>${escape(it)}</span>"
        } ?: ""

        if (!collapsed) return link.toString() + description
        // Hover-to-show doesn't exist on touch screens (nor on AnkiDroid): tap "[Image]" to open it.
        val summaryStyle = if (inline) " style=\"cursor:pointer\"" else ""
        val detailsStyle = if (inline) " style=\"display:inline-block;vertical-align:baseline\"" else ""
        return "<details class=\"gloss-image-details\"$detailsStyle><summary class=\"gloss-image-summary\"$summaryStyle>[Image]</summary>" +
            link + "</details>" + description
    }

    private val VERTICAL_ALIGN = setOf("baseline", "sub", "super", "text-top", "text-bottom", "middle", "top", "bottom")

    // Inline equivalents of Yomitan's structured-content-style.json rules for images.
    private const val INLINE_LINK = "display:inline-block;position:relative;line-height:1;max-width:100%;color:inherit"
    private const val INLINE_CONTAINER =
        "display:inline-block;white-space:nowrap;max-width:100%;position:relative;vertical-align:top;line-height:0;overflow:hidden"
    private const val INLINE_SIZER = "display:inline-block;width:0;vertical-align:top;font-size:0"
    private const val INLINE_BACKGROUND =
        "position:absolute;left:0;top:0;width:100%;height:100%;background-color:currentColor;" +
            "-webkit-mask-repeat:no-repeat;-webkit-mask-position:center center;-webkit-mask-size:contain;" +
            "mask-repeat:no-repeat;mask-position:center center;mask-size:contain;mask-mode:alpha"
    private const val INLINE_IMAGE = "display:inline-block;vertical-align:top;object-fit:contain;border:none;outline:none"
    private const val INLINE_IMAGE_FILL = "position:absolute;left:0;top:0;width:100%;height:100%"

    /**
     * Yomitan's `structured-content.css` image rules (+ the `--gloss-image-background-color` of
     * `display.css`), adapted: sizes are written in px/em directly, and images without a full
     * preferred size keep their real aspect ratio.
     */
    val IMAGE_CSS = """
        .gloss-image-link { cursor: inherit; color: var(--accent); display: inline-block; position: relative; line-height: 1; max-width: 100%; }
        /* No Yomitan max-height:100vh: a popup WebView lays out before it has a height, so vh is 0 and
           overflow:hidden clipped every image to nothing. */
        .gloss-image-container { display: inline-block; white-space: nowrap; max-width: 100%; position: relative;
            vertical-align: top; line-height: 0; overflow: hidden; }
        .gloss-image-link[data-background=true] > .gloss-image-container { background-color: var(--gloss-image-background-color); }
        .gloss-image-container-overlay { position: absolute; left: 0; top: 0; width: 100%; height: 100%; pointer-events: none; }
        .gloss-image-background { --image: none; position: absolute; left: 0; top: 0; width: 100%; height: 100%;
            background-color: var(--text-color); display: none;
            -webkit-mask-repeat: no-repeat; -webkit-mask-position: center center; -webkit-mask-size: contain; -webkit-mask-image: var(--image);
            mask-repeat: no-repeat; mask-position: center center; mask-mode: alpha; mask-size: contain; mask-image: var(--image); }
        .gloss-image-link[data-appearance=monochrome] .gloss-image-background { display: block; }
        .gloss-image-link[data-appearance=monochrome] .gloss-image { opacity: 0; }
        .gloss-image { display: inline-block; vertical-align: top; object-fit: contain; border: none; outline: none; max-width: none; }
        .gloss-image-link[data-has-aspect-ratio=true] .gloss-image { position: absolute; left: 0; top: 0; width: 100%; height: 100%; }
        .gloss-image-link[data-has-aspect-ratio=false] .gloss-image { max-width: 100%; }
        .gloss-image-link[data-has-aspect-ratio=true] .gloss-image-sizer { display: inline-block; width: 0; vertical-align: top; font-size: 0; }
        .gloss-image-link[data-image-rendering=pixelated] .gloss-image,
        .gloss-image-link[data-image-rendering=pixelated] .gloss-image-background {
            image-rendering: auto; image-rendering: -webkit-optimize-contrast; image-rendering: pixelated; }
        .gloss-image-link[data-image-rendering=crisp-edges] .gloss-image,
        .gloss-image-link[data-image-rendering=crisp-edges] .gloss-image-background {
            image-rendering: auto; image-rendering: -webkit-optimize-contrast; image-rendering: crisp-edges; }
        .gloss-image-link-text { display: none; }
        .gloss-image-description { display: block; white-space: pre-line; }
        .gloss-image-link[data-vertical-align=baseline] { vertical-align: baseline; }
        .gloss-image-link[data-vertical-align=sub] { vertical-align: sub; }
        .gloss-image-link[data-vertical-align=super] { vertical-align: super; }
        .gloss-image-link[data-vertical-align=text-top] { vertical-align: top; }
        .gloss-image-link[data-vertical-align=text-bottom] { vertical-align: bottom; }
        .gloss-image-link[data-vertical-align=middle] { vertical-align: middle; }
        .gloss-image-link[data-vertical-align=top] { vertical-align: top; }
        .gloss-image-link[data-vertical-align=bottom] { vertical-align: bottom; }
        .gloss-image-details { display: inline-block; vertical-align: baseline; }
        .gloss-image-details > summary { color: var(--accent); list-style: none; cursor: pointer; }
        .gloss-image-details > summary::-webkit-details-marker { display: none; }
        .gloss-image-details[open] { display: block; }
        .gloss-image-placeholder { opacity: 0.7; }
    """.trimIndent()

    private fun renderElement(sb: StringBuilder, node: GlossaryContent.Element, ctx: RenderContext) {
        val tag = when (val lower = node.tag.lowercase()) {
            "div", "span", "ul", "ol", "li", "table", "thead", "tbody", "tfoot", "tr", "td", "th",
            "b", "strong", "i", "em", "u", "s", "sub", "sup", "ruby", "rt", "rp", "small", "p", "a",
            "details", "summary",
            -> lower
            "br" -> {
                sb.append("<br>")
                return
            }
            else -> "div"
        }
        sb.append('<').append(tag)
        for ((k, v) in node.data) {
            val dataKey = dataAttrKey(k)
            if (dataKey.isNotEmpty()) {
                sb.append(" data-sc-").append(dataKey).append("=\"").append(escapeAttr(v)).append('"')
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
            sb.append(' ').append(attrName).append("=\"").append(escapeAttr(v)).append('"')
        }
        if (node.style.isNotEmpty()) {
            val css = node.style.entries.joinToString("; ") { (k, v) -> "${cssKey(k)}: ${cssValue(v)}" }
            if (css.isNotEmpty()) sb.append(" style=\"").append(escapeAttr(css)).append('"')
        }
        sb.append('>')
        node.children.forEach { renderNode(sb, it, ctx) }
        sb.append("</").append(tag).append('>')
    }

    private fun isSafeHref(href: String): Boolean {
        val trimmed = href.trim().lowercase()
        return trimmed.startsWith("?") || trimmed.startsWith("http://") || trimmed.startsWith("https://") || trimmed.startsWith("#")
    }

    private fun dataAttrKey(key: String): String = dataKeys.getOrPut(key) {
        key.replace(camelBoundary, "$1-$2").lowercase().replace(nonDataKeyChars, "")
    }

    /** Very small CSS value sanitizer: strips anything that could break out of a style attribute. */
    private fun cssValue(value: String): String = value.replace("\"", "'").replace("<", "").replace(">", "")

    /** Converts camelCase structured-content style keys (`listStyleType`, `fontSize`) to CSS (`list-style-type`, `font-size`). */
    private fun cssKey(key: String): String = cssKeys.getOrPut(key) {
        key.replace(camelBoundary, "$1-$2").lowercase().replace(nonCssKeyChars, "")
    }

    fun escape(text: String): String = buildString(text.length) { appendEscaped(this, text) }

    private fun appendEscaped(sb: StringBuilder, text: String) {
        for (ch in text) {
            when (ch) {
                '&' -> sb.append("&amp;")
                '<' -> sb.append("&lt;")
                '>' -> sb.append("&gt;")
                '"' -> sb.append("&quot;")
                else -> sb.append(ch)
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
                --gloss-image-background-color: ${if (darkMode) "#2f2f2f" else "#eeeeee"};
            }
            html, body { margin:0; padding:0; background:$bg; color:$fg;
                font-family: -apple-system, 'Noto Sans JP', 'Hiragino Sans', sans-serif;
                font-size:15px; line-height:1.55; word-wrap:break-word; }
            .glossary { padding: 10px 14px 14px; }
            .gloss-dictionary { margin: 14px 0 6px; padding-top: 12px; border-top: 1px solid rgba(128,128,128,0.25);
                font-weight:600; font-size:0.85em; color:$accent; letter-spacing:0.02em; }
            .gloss-dictionary:first-child { margin-top: 0; padding-top: 0; border-top: none; }
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
            img:not(.gloss-image), svg { max-width:100%; height:auto; }
            ul, ol { margin:4px 0; padding-left:20px; }
            li { margin:2px 0; }
            table { border-collapse: collapse; }
            th, td { border: 1px solid rgba(128,128,128,0.35); padding: 2px 6px; }
        """.trimIndent() + // body and styles are appended untrimmed: trimIndent over 100s of KB of HTML is not free
            "\n" + IMAGE_CSS + "\n" + styles.orEmpty() + "\n</style></head>\n<body><div class=\"glossary\">" + bodyHtml + "</div></body></html>"
    }
}
