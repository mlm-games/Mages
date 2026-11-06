package org.mlm.mages.ui.components.composer

import com.fleeksoft.ksoup.Ksoup
import com.fleeksoft.ksoup.nodes.Element
import com.fleeksoft.ksoup.nodes.Node
import com.fleeksoft.ksoup.nodes.TextNode
import org.mlm.mages.MessageEvent
import org.mlm.mages.nav.matrixUserIdFromLink
import org.mlm.mages.ui.mediaCaption

/**
 * The composer text an edit starts from.
 *
 * `body` is only the flat fallback: mentions, emotes, spoilers and all block
 * formatting live in `formatted_body` alone, and the send path re-derives
 * `m.mentions` from the anchors. Rebuilding the draft from the HTML therefore
 * keeps both what the user sees and what the next send produces.
 */
fun MessageEvent.editComposerText(): String = draftFrom(body, formattedBody)

fun MessageEvent.captionEditComposerText(): String =
    draftFrom(mediaCaption() ?: "", formattedBody)

private fun draftFrom(fallback: String, html: String?): String {
    if (html.isNullOrBlank()) return fallback
    return draftFromHtml(html).ifBlank { fallback }
}

private fun draftFromHtml(html: String): String = blocks(Ksoup.parse(html).body()).trim()

/**
 * Renders a sequence of blocks and inline nodes. Adjacent inline nodes form one
 * paragraph; every block is separated from its neighbours by a blank line,
 * which is what CommonMark needs to keep two paragraphs apart.
 */
private fun blocks(parent: Node): String {
    val out = StringBuilder()
    val inline = StringBuilder()

    fun flushInline() {
        val text = finishInline(inline.toString())
        inline.clear()
        if (text.isEmpty()) return
        if (out.isNotEmpty()) out.append("\n\n")
        out.append(text)
    }

    parent.childNodes().forEach { child ->
        val element = child as? Element
        if (element != null && element.normalName() in DRAFT_BLOCK_TAGS) {
            flushInline()
            val block = draftBlock(element)
            if (block.isEmpty()) return@forEach
            if (out.isNotEmpty()) out.append("\n\n")
            out.append(block)
        } else {
            inline.append(draftInline(child))
        }
    }
    flushInline()
    return out.toString()
}

private fun draftBlock(element: Element): String {
    val tag = element.normalName()
    val block = when {
        tag.length == 2 && tag[0] == 'h' && tag[1] in '1'..'6' ->
            "#".repeat(tag[1] - '0') + " " + finishInline(draftInlines(element))
        tag == "blockquote" -> quote(blocks(element))
        tag == "pre" -> fencedCode(element)
        tag == "ul" || tag == "ol" -> draftList(element)
        tag == "hr" -> "---"
        tag == "table" -> draftTable(element)
        else -> blocks(element)
    }
    return block.trim()
}

private fun quote(content: String): String {
    if (content.isBlank()) return ""
    return content.lineSequence().joinToString("\n") { line ->
        if (line.isEmpty()) ">" else "> $line"
    }
}

private fun draftList(element: Element): String {
    val ordered = element.normalName() == "ol"
    var number = element.attr("start").trim().toIntOrNull() ?: 1
    val items = element.children().filter { it.normalName() == "li" }
    return items.joinToString("\n") { item ->
        val marker = if (ordered) "${number++}. " else "- "
        val body = blocks(item).trim().replace(NESTED_LIST_BREAK, "\n")
        if (body.isEmpty()) {
            marker.trimEnd()
        } else {
            val lines = body.lineSequence().toList()
            val indent = " ".repeat(marker.length)
            marker + lines.first() + lines.drop(1).joinToString("") { line ->
                if (line.isEmpty()) "\n" else "\n$indent$line"
            }
        }
    }
}

private fun fencedCode(element: Element): String {
    val code = element.selectFirst("code")
    val language = (code ?: element).attr("class")
        .split(WHITESPACE_RUN)
        .firstOrNull { it.startsWith("language-") || it.startsWith("lang-") }
        ?.substringAfter('-')
        .orEmpty()
    val text = element.wholeText().trimEnd('\n')
    val fence = "`".repeat((longestRun(text, '`') + 1).coerceAtLeast(3))
    return buildString {
        append(fence)
        append(language)
        append('\n')
        append(text)
        append('\n')
        append(fence)
    }
}

private fun draftTable(element: Element): String {
    val rows = tableRows(element)
    if (rows.isEmpty()) return blocks(element)
    return rows.joinToString("\n") { row ->
        row.children()
            .filter { it.normalName() == "td" || it.normalName() == "th" }
            .joinToString(" | ") { draftTableCell(it) }
    }
}

private fun tableRows(element: Element): List<Element> {
    val direct = element.children().filter { it.normalName() == "tr" }
    if (direct.isNotEmpty()) return direct
    return element.children()
        .filter { it.normalName() in TABLE_SECTIONS }
        .flatMap { section -> section.children().filter { it.normalName() == "tr" } }
}

private fun draftTableCell(cell: Element): String =
    draftInlines(cell).replace("\\\n", " ").replace('\n', ' ').trim()

private fun draftInlines(parent: Node): String = buildString {
    parent.childNodes().forEach { append(draftInline(it)) }
}

private fun draftInline(node: Node): String = when (node) {
    is TextNode -> escapeDraftText(node.getWholeText())
    is Element -> draftInlineElement(node)
    else -> ""
}

private fun draftInlineElement(element: Element): String {
    val tag = element.normalName()
    if (tag in DRAFT_DROPPED_TAGS) return ""
    return when (tag) {
        "br" -> "\\\n"
        "strong", "b" -> wrapInline(element, "**")
        "em", "i" -> wrapInline(element, "*")
        "del", "s", "strike" -> wrapInline(element, "~~")
        "code" -> codeSpan(element.wholeText())
        "a" -> draftLink(element)
        "img" -> draftImage(element)
        "span" -> if (element.hasAttr(SpoilerSyntax.SPOILER_ATTR)) {
            draftSpoiler(element)
        } else {
            draftInlines(element)
        }
        else -> draftInlines(element)
    }
}

private fun wrapInline(element: Element, delimiter: String): String =
    wrapDelimited(draftInlines(element), delimiter)

/**
 * Markdown delimiters have to touch their content, so whitespace inside the
 * tag is moved outside the delimiters rather than swallowing them.
 */
private fun wrapDelimited(content: String, delimiter: String): String {
    if (content.isBlank()) return content
    val leading = content.takeWhile { it.isWhitespace() }
    val trailing = content.takeLastWhile { it.isWhitespace() }
    val core = content.substring(leading.length, content.length - trailing.length)
    if (core.isEmpty()) return content
    return leading + delimiter + core + delimiter + trailing
}

private fun codeSpan(text: String): String {
    if (text.isEmpty()) return ""
    val fence = "`".repeat(longestRun(text, '`') + 1)
    val pad = if (text.startsWith("`") || text.endsWith("`") ||
        (text.startsWith(" ") && text.endsWith(" ") && text.isNotBlank())
    ) {
        " "
    } else {
        ""
    }
    return "$fence$pad$text$pad$fence"
}

private fun draftLink(element: Element): String {
    val href = element.attr("href").trim()
    if (href.isEmpty()) return draftInlines(element)

    val userId = matrixUserIdFromLink(href)
    if (userId != null) {
        val label = element.text().replace(WHITESPACE_RUN, " ").trim()
        if (label.isNotEmpty()) return mentionMarkdown(label, userId)
    }

    val label = draftInlines(element).ifBlank { escapeDraftText(href) }
    return "[$label](${draftHref(href)})"
}

private fun draftImage(element: Element): String {
    val src = element.attr("src").trim()
    if (src.isEmpty()) return ""

    val alt = element.attr("alt")
    val shortcode = element.attr("title").trim().ifBlank { alt.trim() }
    val emote = src.startsWith("mxc://") && shortcode.isNotEmpty()
    return if (emote) {
        "![${escapeDraftAlt(alt)}]($src \"${escapeDraftAlt(shortcode)}\")"
    } else {
        "![${escapeDraftAlt(alt)}](${draftHref(src)})"
    }
}

private fun draftSpoiler(element: Element): String {
    val content = draftInlines(element)
    if (content.isEmpty()) return ""
    return wrapDelimited(content, "|")
}

private fun draftHref(href: String): String {
    if (href.none { it.isWhitespace() || it == '(' || it == ')' || it == '<' || it == '>' }) {
        return href
    }
    return "<" + href.replace("<", "%3C").replace(">", "%3E") + ">"
}

/**
 * A bullet, ATX heading, quote or rule can interrupt or close a paragraph even
 * on a continuation line, so every line of the finished run is escaped.
 */
private fun finishInline(raw: String): String {
    var text = raw.trim()
    if (text.endsWith("\\") && !text.endsWith("\\\\")) text = text.dropLast(1).trimEnd()
    return text.lineSequence().joinToString("\n") { escapeLine(it) }
}

private fun escapeLine(line: String): String =
    LEADING_RULE.replace(
        LEADING_ORDERED.replace(
            LEADING_MARKER.replace(line) { "${it.groupValues[1]}\\${it.groupValues[2]}" }
        ) { "${it.groupValues[1]}\\${it.groupValues[2]}" }
    ) { "${it.groupValues[1]}\\${it.groupValues[2]}" }

private fun escapeDraftText(text: String): String =
    DRAFT_ESCAPE.replace(text) { "\\" + it.groupValues[1] }

private fun escapeDraftAlt(text: String): String = text
    .replace("\\", "\\\\")
    .replace("\"", "\\\"")
    .replace("[", "\\[")
    .replace("]", "\\]")
    .replace("(", "\\(")
    .replace(")", "\\)")

private fun longestRun(text: String, char: Char): Int {
    var longest = 0
    var current = 0
    for (c in text) {
        if (c == char) {
            current += 1
            if (current > longest) longest = current
        } else {
            current = 0
        }
    }
    return longest
}

private val DRAFT_BLOCK_TAGS = setOf(
    "address", "article", "aside", "blockquote", "caption", "dd", "details",
    "div", "dl", "dt", "fieldset", "figcaption", "figure", "footer", "form",
    "h1", "h2", "h3", "h4", "h5", "h6", "header", "hr", "li", "main", "nav",
    "ol", "p", "pre", "section", "summary", "table", "tbody", "tfoot",
    "thead", "tr", "ul"
)

private val DRAFT_DROPPED_TAGS =
    setOf("head", "script", "style", "template", "svg", "math", "mx-reply")

/** ASCII punctuation that would otherwise start markup, plus the raw-HTML pair. */
private val DRAFT_ESCAPE = Regex("""([\\`*_\[\]|~<>])""")

private val LEADING_MARKER = Regex("""^(\s*)(#{1,6}|[-+>])(?=\s|$)""")
private val LEADING_ORDERED = Regex("""^(\s*\d{1,9})([.)])(?=\s|$)""")
private val LEADING_RULE = Regex("""^(\s*)(-{3,})(?=\s|$)""")
private val NESTED_LIST_BREAK = Regex("""\n\n(?=(?:[-+*]|\d{1,9}[.)]) )""")
private val WHITESPACE_RUN = Regex("[ \\t\\u000B\\u000C\\r\\n]+")
private val TABLE_SECTIONS = setOf("thead", "tbody", "tfoot")
