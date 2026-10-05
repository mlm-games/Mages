package org.mlm.mages.ui.components.composer

import org.intellij.markdown.ast.ASTNode
import org.intellij.markdown.html.HtmlGenerator
import org.intellij.markdown.parser.MarkdownParser
import org.mlm.mages.matrix.ImagePackImageEntry

/**
 * Converts composer text into the `body` and `formatted_body` pair that a
 * message is sent with. Shared by the room and thread composers so both send
 * identical markup.
 */
private val markdownFlavour = SpoilerFlavour()

/** A mention stands in as a markdown link while composing. */
val MENTION_MARKDOWN = Regex("""\[([^\]]+)]\(https://matrix\.to/#/(@[^)]+)\)""")

/**
 * The same mention, after [MENTION_MARKDOWN] has turned it into a real anchor
 * so the markdown pass leaves it alone.
 */
private val MENTION_ANCHOR = Regex(
    """<a href="https://matrix\.to/#/[^"]+">([\s\S]*?)</a>"""
)

private val EMOTE_MARKDOWN = Regex("""!\[((?:\\.|[^\]\\])*)]\([^)]*\)""")

/** A backslash before ASCII punctuation, which markdown treats as an escape. */
private val MARKDOWN_ESCAPE = Regex("""\\([!"#$%&'()*+,\-./:;<=>?@\[\\\]^_`{|}~])""")

private val IMG_TAG = Regex("""<img\b[^>]*>""", RegexOption.IGNORE_CASE)
private val IMG_SRC = Regex("""\ssrc\s*=\s*["']([^"']+)["']""", RegexOption.IGNORE_CASE)

/**
 * A parsed composer message.
 *
 * [text] is the mention-substituted source, which is the string [spoilerRanges]
 * are offsets into; the plaintext fallback is built from it rather than from the
 * raw composer text so that the spoiler ranges still line up.
 */
class ComposerMarkdown(
    val text: String,
    val formattedBody: String?,
    val spoilerRanges: List<IntRange>
) {
    val hasSpoilers: Boolean get() = spoilerRanges.isNotEmpty()
}

/**
 * Parses composer text, or returns null when there is nothing to send.
 *
 * [emoteImages] is the set of pack images the room currently has loaded; an
 * `<img>` is only treated as a custom emote when its `src` resolves to one of
 * them, so an image from any other origin is left as an ordinary image.
 */
fun parseComposerMarkdown(text: String, emoteImages: List<ImagePackImageEntry>): ComposerMarkdown? {
    if (text.isBlank()) return null

    val processed = MENTION_MARKDOWN.replace(text) { match ->
        val label = match.groupValues[1]
        val userId = match.groupValues[2]
        val display = if (label.startsWith("@")) escapeHtml(label) else "@${escapeHtml(label)}"
        "<a href=\"https://matrix.to/#/${escapeHtmlAttribute(userId)}\">$display</a>"
    }

    val tree = MarkdownParser(markdownFlavour).buildMarkdownTreeFromString(processed)
    val spoilers = spoilerRanges(tree)

    // The tree is rooted at MARKDOWN_FILE, which the CommonMark flavour maps to
    // a <body> tag, and each top-level paragraph is wrapped in <p>. Neither
    // belongs in formatted_body.
    var html = HtmlGenerator(processed, tree, markdownFlavour, false).generateHtml()
    html = html.removeSurrounding("<body>", "</body>")
    html = html.removeSurrounding("<p>", "</p>")

    val formatted = markEmoteImages(html, emoteImages)?.ifBlank { null } ?: html.ifBlank { null }
    return ComposerMarkdown(processed, formatted, spoilers)
}

/** The `body` and `formatted_body` a message is sent with. */
class OutgoingText(val body: String, val formattedBody: String?)

/**
 * The plaintext `body`. Mentions collapse to a bare `@label` and emotes to
 * their alt text, so a client that cannot render `formatted_body` still shows
 * something meaningful.
 *
 * Spoilered text is dropped rather than shown: the spec asks for it to be
 * absent from `body`, which is what notifications and plaintext-only clients
 * read. [spoilerImage] is the uploaded placeholder the spec's example points
 * the fallback link at; without it the fallback degrades to a bare label.
 */
fun composerToPlainBody(parsed: ComposerMarkdown, spoilerImage: String? = null): String =
    MENTION_ANCHOR
        .replace(redactSpoilers(parsed.text, parsed.spoilerRanges, spoilerImage)) {
            unescapeHtml(it.groupValues[1])
        }
        .replace(EMOTE_MARKDOWN) { unescapeMarkdown(it.groupValues[1]) }

private fun redactSpoilers(text: String, ranges: List<IntRange>, image: String?): String {
    if (ranges.isEmpty()) return text
    val label = image?.let { "[Spoiler]($it)" } ?: "[Spoiler]"
    val out = StringBuilder(text.length)
    var pos = 0
    for (range in ranges.sortedBy { it.first }) {
        if (range.first < pos) continue
        out.append(text, pos, range.first)
        out.append(label)
        pos = range.last + 1
    }
    out.append(text, pos, text.length)
    return out.toString()
}

/** The source spans the spoiler delimiters consumed, outermost first. */
private fun spoilerRanges(tree: ASTNode): List<IntRange> {
    val out = mutableListOf<IntRange>()
    fun walk(node: ASTNode) {
        if (node.type == SpoilerSyntax.SPOILER) out += node.startOffset until node.endOffset
        node.children.forEach { walk(it) }
    }
    walk(tree)
    return out.sortedBy { it.first }
}

/**
 * Rewrites the `<img>` elements the markdown pass produced into the form the
 * spec defines for custom emotes: the presence of `data-mx-emoticon` is what
 * marks an image as an emote, and `height` is mandatory for clients that do not
 * understand them.
 *
 * The rewritten tag always quotes the pack's own URI rather than the parsed
 * one, so nothing from the message reaches the output verbatim.
 */
private fun markEmoteImages(html: String, emoteImages: List<ImagePackImageEntry>): String? {
    if (emoteImages.isEmpty()) return null
    val known = emoteImages.associateBy({ it.mxcUrl }, { it })

    var changed = false
    val out = IMG_TAG.replace(html) { match ->
        val raw = IMG_SRC.find(match.value)?.groupValues?.get(1) ?: return@replace match.value
        val image = known[raw] ?: return@replace match.value
        changed = true
        val alt = escapeHtmlAttribute(image.body?.takeIf { it.isNotBlank() } ?: image.shortcode)
        "<img data-mx-emoticon src=\"${escapeHtmlAttribute(image.mxcUrl)}\" alt=\"$alt\" " +
            "title=\"${escapeHtmlAttribute(image.shortcode)}\" height=\"32\">"
    }
    return if (changed) out else null
}

private fun escapeHtml(text: String): String = text
    .replace("&", "&amp;")
    .replace("<", "&lt;")
    .replace(">", "&gt;")
    .replace("\"", "&quot;")

private fun unescapeHtml(text: String): String = text
    .replace("&quot;", "\"")
    .replace("&gt;", ">")
    .replace("&lt;", "<")
    .replace("&amp;", "&")

/** Drops the escapes [EmoteSuggestion.markdown] adds, recovering the alt text. */
private fun unescapeMarkdown(text: String): String = MARKDOWN_ESCAPE.replace(text) { it.groupValues[1] }

private fun escapeHtmlAttribute(text: String): String = escapeHtml(text)
