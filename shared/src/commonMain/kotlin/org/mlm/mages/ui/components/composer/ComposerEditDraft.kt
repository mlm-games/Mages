package org.mlm.mages.ui.components.composer

import com.fleeksoft.ksoup.Ksoup
import com.fleeksoft.ksoup.nodes.Element
import com.fleeksoft.ksoup.nodes.Node
import com.fleeksoft.ksoup.nodes.TextNode
import org.mlm.mages.MessageEvent
import org.mlm.mages.nav.matrixUserIdFromLink
import org.mlm.mages.ui.mediaCaption

/**
 * A mention, an emote and a spoiler all survive only in `formatted_body`, while
 * `body` is their flat fallback. Editing from `body` would drop them, along with
 * the `m.mentions` the send path derives from the anchors, so the fallback is
 * turned back into composer text before the editor sees it.
 */
fun MessageEvent.editComposerText(): String = restoreComposerMarkup(body, formattedBody)

fun MessageEvent.captionEditComposerText(): String =
    restoreComposerMarkup(mediaCaption() ?: "", formattedBody)

private sealed class InlineAnchor {
    class Mention(val label: String, val userId: String) : InlineAnchor()
    class Emote(val alt: String, val mxcUri: String, val shortcode: String) : InlineAnchor()
    class Spoiler(val markdown: String) : InlineAnchor()
}

private fun restoreComposerMarkup(text: String, html: String?): String {
    val anchors = inlineAnchors(html)
    if (anchors.isEmpty()) return text

    val out = StringBuilder(text.length + anchors.size * 48)
    var cursor = 0
    for (anchor in anchors) {
        val range = when (anchor) {
            is InlineAnchor.Mention -> findLabel(text, listOf(anchor.label), cursor)
            is InlineAnchor.Emote -> findLabel(text, listOf(anchor.alt, anchor.shortcode), cursor)
            is InlineAnchor.Spoiler -> findSpoiler(text, cursor)
        } ?: continue
        out.append(text, cursor, range.first)
        out.append(
            when (anchor) {
                is InlineAnchor.Mention -> mentionMarkdown(text.substring(range), anchor.userId)
                is InlineAnchor.Emote ->
                    "![${escapeEmoteAlt(anchor.alt)}](${anchor.mxcUri} \"${anchor.shortcode}\")"
                is InlineAnchor.Spoiler -> "|${anchor.markdown}|"
            }
        )
        cursor = range.last + 1
    }
    out.append(text, cursor, text.length)
    return out.toString()
}

private fun inlineAnchors(html: String?): List<InlineAnchor> {
    if (html == null) return emptyList()
    if (!html.contains("matrix.to/", ignoreCase = true) &&
        !html.contains("matrix:", ignoreCase = true) &&
        !html.contains(SpoilerSyntax.SPOILER_ATTR, ignoreCase = true) &&
        !html.contains("<img", ignoreCase = true)
    ) {
        return emptyList()
    }
    val root = Ksoup.parse(html).body()
    val anchors = mutableListOf<InlineAnchor>()
    walkAnchors(root, anchors)
    return anchors
}

private fun walkAnchors(node: Node, out: MutableList<InlineAnchor>) {
    if (node is Element) {
        when {
            node.hasAttr(SpoilerSyntax.SPOILER_ATTR) -> {
                val markdown = inlineMarkdown(node)
                if (markdown.isNotBlank()) out += InlineAnchor.Spoiler(markdown)
                return
            }
            node.tagName().equals("a", ignoreCase = true) -> {
                val userId = matrixUserIdFromLink(node.attr("href"))
                val label = node.text().replace(WHITESPACE_RUN, " ").trim()
                if (userId != null && label.isNotEmpty()) {
                    out += InlineAnchor.Mention(unescapeMarkdown(label), userId)
                }
                return
            }
            node.tagName().equals("img", ignoreCase = true) -> {
                val mxcUri = node.attr("src")
                val shortcode = node.attr("title").trim()
                val alt = node.attr("alt").ifBlank { shortcode }
                if (mxcUri.startsWith("mxc://") && shortcode.isNotEmpty() && alt.isNotEmpty()) {
                    out += InlineAnchor.Emote(alt, mxcUri, shortcode)
                }
                return
            }
        }
    }
    node.childNodes().forEach { walkAnchors(it, out) }
}

/**
 * The inline markup inside a spoiler, so its hidden text survives an edit.
 * Only the tags the composer can spell are mapped; anything else keeps its text.
 */
private fun inlineMarkdown(parent: Node): String = buildString {
    for (child in parent.childNodes()) appendInline(child)
}

private fun StringBuilder.appendInline(node: Node) {
    when {
        node is TextNode -> append(node.text().replace("\\", "\\\\").replace("|", "\\|"))
        node is Element -> when (node.tagName().lowercase()) {
            "br" -> append('\n')
            "strong", "b" -> append("**").append(inlineMarkdown(node)).append("**")
            "em", "i" -> append('*').append(inlineMarkdown(node)).append('*')
            "del", "s", "strike" -> append("~~").append(inlineMarkdown(node)).append("~~")
            "code" -> append('`').append(node.text()).append('`')
            "a" -> {
                val userId = matrixUserIdFromLink(node.attr("href"))
                val label = unescapeMarkdown(node.text())
                if (userId != null) append(mentionMarkdown(label, userId))
                else append('[').append(label).append("](").append(node.attr("href")).append(')')
            }
            "img" -> append(node.attr("alt"))
            else -> append(inlineMarkdown(node))
        }
    }
}

/**
 * Finds the label in the plaintext, preferring an occurrence that is a whole
 * word. When a candidate carries no `@` but the text does, the `@` is part of
 * the returned range so the composer keeps a single one.
 */
private fun findLabel(text: String, labels: List<String>, from: Int): IntRange? {
    for (label in labels) {
        val trimmed = label.trim()
        if (trimmed.isEmpty()) continue
        val bare = trimmed.removePrefix("@")
        for (candidate in listOf(trimmed, "@$bare", bare).distinct()) {
            var start = text.indexOf(candidate, from)
            while (start >= 0) {
                val end = start + candidate.length
                val rangeStart = if (candidate == bare && start > 0 && text[start - 1] == '@') start - 1 else start
                val beforeOk = rangeStart == 0 || !text[rangeStart - 1].isLabelChar()
                val afterOk = end == text.length || !text[end].isLabelChar()
                if (beforeOk && afterOk) return rangeStart until end
                start = text.indexOf(candidate, start + 1)
            }
        }
    }
    return null
}

private val SPOILER_PLACEHOLDER = Regex("""\[Spoiler]\([^)\s]+\)|\[Spoiler]""", RegexOption.IGNORE_CASE)

private fun findSpoiler(text: String, from: Int): IntRange? =
    SPOILER_PLACEHOLDER.find(text, from)?.range

private fun escapeEmoteAlt(text: String): String = text
    .replace("\\", "\\\\")
    .replace("[", "\\[")
    .replace("]", "\\]")
    .replace("(", "\\(")
    .replace(")", "\\)")

private fun Char.isLabelChar(): Boolean =
    this in 'a'..'z' || this in 'A'..'Z' || this in '0'..'9' || this == '_'

private val WHITESPACE_RUN = Regex("[ \t\u000B\u000C\r\n]+")
