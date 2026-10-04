package org.mlm.mages.ui.components.core

import androidx.compose.foundation.layout.size
import androidx.compose.foundation.text.InlineTextContent
import androidx.compose.foundation.text.appendInlineContent
import androidx.compose.material3.LocalContentColor
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.mutableStateSetOf
import androidx.compose.runtime.remember
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.text.AnnotatedString
import androidx.compose.ui.text.LinkAnnotation
import androidx.compose.ui.text.Placeholder
import androidx.compose.ui.text.PlaceholderVerticalAlign
import androidx.compose.ui.text.SpanStyle
import androidx.compose.ui.text.TextLinkStyles
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontStyle
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextDecoration
import androidx.compose.ui.unit.TextUnit
import androidx.compose.ui.unit.sp
import coil3.compose.AsyncImage
import coil3.compose.LocalPlatformContext
import coil3.request.ImageRequest
import coil3.request.crossfade
import com.fleeksoft.ksoup.Ksoup
import com.fleeksoft.ksoup.nodes.Element
import com.fleeksoft.ksoup.nodes.Node
import com.fleeksoft.ksoup.nodes.TextNode
import org.mlm.mages.LocalMessageFontSize
import org.mlm.mages.nav.isMatrixPermalink

private val MXC_SRC_ATTR = Regex("""\ssrc\s*=\s*["']?(mxc://[^"'\s>]+)""", RegexOption.IGNORE_CASE)

/** Bounds the work a single hostile message can ask a client to do. */
private const val MAX_EMOTES_PER_MESSAGE = 32

/**
 * The mxc URIs of the inline images a `formatted_body` references.
 *
 * Keyed on `src` rather than `data-mx-emoticon`: matrix-sdk sanitizes every
 * timeline message, and the spec's attribute allow-list for `img` is
 * `width|height|alt|title|src`, so `data-mx-emoticon` is dropped before the
 * event ever reaches a client. The mxc scheme is part of the pattern itself, so
 * a crafted body cannot make a client prefetch a non-mxc URI.
 */
fun emoteMxcUrisFrom(formattedBody: String?): List<String> {
    if (formattedBody == null || !formattedBody.contains("mxc://")) return emptyList()

    val out = LinkedHashSet<String>()
    for (match in MXC_SRC_ATTR.findAll(formattedBody)) {
        out += match.groupValues[1]
        if (out.size >= MAX_EMOTES_PER_MESSAGE) break
    }
    return out.toList()
}

/** The spec's recommended emote height, scaled to the user's message font size. */
private val EMOTE_HEIGHT: TextUnit = 32.sp

private val LINK_COLOR = Color(0xFF1A73E8)

private val LINK_STYLE = SpanStyle(color = LINK_COLOR, textDecoration = TextDecoration.Underline)

private val BARE_URL = Regex(
    """(?:https?://|www\.)[0-9A-Za-z][^\s<>"']*""",
    RegexOption.IGNORE_CASE
)

private val TRAILING_PUNCTUATION = ".,;:!?*_~`"

private val UNBALANCED_CLOSERS = mapOf(')' to '(', ']' to '[', '}' to '{')

private val CODE_TAGS = setOf("code", "pre")

/** Never displayed. `head` is dropped so document metadata cannot leak. */
private val DROPPED_TAGS = setOf("head", "script", "style", "template", "svg", "math")

/** `<br>` is not an HTML5 element, so jsoup keeps it as unknown inline markup. */
private val BREAK_TAGS = setOf("br")

private val BOLD_TAGS = setOf("b", "strong")
private val ITALIC_TAGS = setOf("i", "em")
private val UNDERLINE_TAGS = setOf("u", "ins")
private val STRIKE_TAGS = setOf("s", "del", "strike")

/** The attribute the spec marks a spoiler span with. The value is the reason. */
private const val SPOILER_ATTR = "data-mx-spoiler"

/** The link tag a concealed spoiler is annotated with, so a tap can reveal it. */
private const val SPOILER_TAG = "mages-spoiler"

/** Schemes a link may not point at (anything else renders as plain text) */
private val BLOCKED_SCHEMES = listOf("javascript:", "data:", "vbscript:", "file:")

private val WHITESPACE_RUN = Regex("[ \\t\\u000B\\u000C\\r]+")

/** An inline `<img src="mxc://…">`. [path] is the resolved local file, if fetched. */
data class EmoteRef(
    val mxcUri: String,
    val alt: String?,
    val title: String?,
    val path: String? = null
) {
    val label: String get() = alt?.takeIf { it.isNotBlank() } ?: title?.takeIf { it.isNotBlank() } ?: ""
}

/** A run of text the sender marked as hidden, addressed by its position in the body. */
class FormattedBody(
    val text: AnnotatedString,
    val inlineContent: Map<String, InlineTextContent>
)

/**
 * Parses a `formatted_body` into styled text plus the emotes it references.
 *
 * Only `mxc://` sources are accepted. The spec requires the emote `src` to be an
 * mxc URI precisely so a client cannot be made to fetch an attacker-chosen
 * remote URL, and ruma's `OwnedMxcUri` does not validate its input, so bridged
 * content really does arrive carrying `https://` sources. Any other scheme is
 * dropped and the emote degrades to its alt text.
 *
 * A spoiler is concealed by painting over its glyphs rather than replacing them,
 * so the text keeps its metrics and revealing it does not reflow the message.
 * Revealing is driven by [onReveal], which the parse calls when a still
 * concealed spoiler is tapped, so [revealed] only has to be a snapshot of the
 * ones already tapped.
 */
fun parseFormattedBody(
    html: String,
    emotePaths: Map<String, String> = emptyMap(),
    conceal: Color? = null,
    revealed: Set<Int> = emptySet(),
    onReveal: (Int) -> Unit = {}
): FormattedBody {
    val builder = Builder(emotePaths, conceal, revealed, onReveal)
    builder.walk(Ksoup.parse(html).body())
    return builder.build()
}

private fun trimUrlTail(url: String): String {
    var end = url.length
    while (end > 0) {
        val last = url[end - 1]
        when {
            last in TRAILING_PUNCTUATION -> end--
            last in UNBALANCED_CLOSERS -> {
                val opener = UNBALANCED_CLOSERS.getValue(last)
                val head = url.substring(0, end)
                if (head.count { it == last } > head.count { it == opener }) end-- else break
            }
            else -> break
        }
    }
    return url.substring(0, end)
}

private fun absoluteUrl(url: String): String =
    if (url.startsWith("www.", ignoreCase = true)) "http://$url" else url

private fun isPreviewableUrl(url: String): Boolean =
    url.startsWith("http://", ignoreCase = true) || url.startsWith("https://", ignoreCase = true)

private fun isPreviewCandidate(url: String): Boolean =
    isPreviewableUrl(url) && !isMatrixPermalink(url)

private fun firstAnchorHref(node: Node): String? {
    if (node !is Element) return null
    val tag = node.normalName()
    if (tag in DROPPED_TAGS) return null
    if (tag == "a") {
        val href = absoluteUrl(node.attr("href").trim())
        if (isPreviewCandidate(href)) return href
    }
    for (child in node.childNodes()) {
        firstAnchorHref(child)?.let { return it }
    }
    return null
}

/** The first link a message offers for previewing: the formatted anchor when there is one, else the first bare URL. */
fun firstLinkIn(body: String?, formattedBody: String?): String? {
    val fromHtml = formattedBody
        ?.takeIf { it.contains("href", ignoreCase = true) }
        ?.let { firstAnchorHref(Ksoup.parse(it).body()) }
    if (fromHtml != null) return fromHtml

    return body?.let { text ->
        BARE_URL.findAll(text)
            .mapNotNull { absoluteUrl(trimUrlTail(it.value)).takeIf(::isPreviewCandidate) }
            .firstOrNull()
    }
}

private class Builder(
    private val emotePaths: Map<String, String>,
    private val conceal: Color?,
    private val revealed: Set<Int>,
    private val onReveal: (Int) -> Unit
) {
    private val text = AnnotatedString.Builder()
    private val inlineContent = mutableMapOf<String, InlineTextContent>()
    private var spoilers = 0

    fun build() = FormattedBody(text.toAnnotatedString(), inlineContent)

    fun walk(node: Node, linkify: Boolean = true) {
        when (node) {
            is TextNode -> appendText(node.getWholeText(), linkify)
            is Element -> walkElement(node, linkify)
            else -> Unit
        }
    }

    private fun walkElement(element: Element, linkify: Boolean) {
        val tag = element.normalName()

        if (tag in DROPPED_TAGS) return
        if (tag in BREAK_TAGS) {
            text.append('\n')
            return
        }
        if (tag == "img") {
            appendEmote(element)
            return
        }
        if (isSpoiler(element)) {
            walkSpoiler(element, linkify)
            return
        }

        val link = if (tag == "a") linkFor(element) else null
        val style = styleFor(tag, link != null)

        if (style != null) text.pushStyle(style)
        if (link != null) {
            text.pushLink(
                LinkAnnotation.Url(
                    url = link,
                    styles = TextLinkStyles(SpanStyle(color = LINK_COLOR))
                )
            )
        }
        val childLinkify = linkify && tag != "a" && tag !in CODE_TAGS
        element.childNodes().forEach { walk(it, childLinkify) }
        if (link != null) text.pop()
        if (style != null) text.pop()
    }

    private fun isSpoiler(element: Element): Boolean =
        element.normalName() == "span" && element.hasAttr(SPOILER_ATTR)

    private fun walkSpoiler(element: Element, linkify: Boolean) {
        val index = spoilers++
        val concealed = conceal != null && index !in revealed

        if (concealed) {
            text.pushStyle(SpanStyle(color = conceal))
            // A link annotation with no styles of its own inherits the ambient
            // link style, which recolours and underlines the range and so would
            // undo the concealment. The styles have to be spelled out here.
            text.pushLink(
                LinkAnnotation.Clickable(
                    tag = SPOILER_TAG,
                    styles = TextLinkStyles(
                        SpanStyle(
                            color = conceal,
                            textDecoration = TextDecoration.None
                        )
                    ),
                    linkInteractionListener = { onReveal(index); it }
                )
            )
        }

        element.childNodes().forEach { walk(it, linkify && !concealed) }

        if (concealed) {
            text.pop()
            text.pop()
        }
    }

    private fun linkFor(element: Element): String? {
        val href = element.attr("href").trim()
        if (href.isEmpty()) return null
        if (BLOCKED_SCHEMES.any { href.startsWith(it, ignoreCase = true) }) return null
        return href
    }

    private fun styleFor(tag: String, isLink: Boolean): SpanStyle? = when {
        tag in BOLD_TAGS -> SpanStyle(fontWeight = FontWeight.Bold)
        tag in ITALIC_TAGS -> SpanStyle(fontStyle = FontStyle.Italic)
        tag in UNDERLINE_TAGS -> SpanStyle(textDecoration = TextDecoration.Underline)
        tag in STRIKE_TAGS -> SpanStyle(textDecoration = TextDecoration.LineThrough)
        tag == "code" -> SpanStyle(fontFamily = FontFamily.Monospace)
        isLink -> LINK_STYLE
        else -> null
    }

    private fun appendText(raw: String, linkify: Boolean) {
        val collapsed = raw.replace(WHITESPACE_RUN, " ")
        if (collapsed.isEmpty()) return
        if (!linkify) {
            text.append(collapsed)
            return
        }

        var cursor = 0
        for (match in BARE_URL.findAll(collapsed)) {
            val start = match.range.first
            if (start > 0 && isAsciiLetterOrDigit(collapsed[start - 1])) continue
            val url = trimUrlTail(match.value)
            if (start > cursor) text.append(collapsed.substring(cursor, start))
            text.pushStyle(LINK_STYLE)
            text.pushLink(
                LinkAnnotation.Url(
                    url = absoluteUrl(url),
                    styles = TextLinkStyles(SpanStyle(color = LINK_COLOR))
                )
            )
            text.append(url)
            text.pop()
            text.pop()
            cursor = start + url.length
        }
        if (cursor < collapsed.length) text.append(collapsed.substring(cursor))
    }

    private fun isAsciiLetterOrDigit(c: Char): Boolean =
        c in 'a'..'z' || c in 'A'..'Z' || c in '0'..'9'

    private fun appendEmote(element: Element) {
        val mxcUri = element.attr("src").trim()
        if (!mxcUri.startsWith("mxc://")) {
            element.attr("alt").trim().ifEmpty { null }?.let { text.append(it) }
            return
        }

        val ref = EmoteRef(
            mxcUri = mxcUri,
            alt = element.attr("alt").trim().ifEmpty { null },
            title = element.attr("title").trim().ifEmpty { null },
            path = emotePaths[mxcUri]
        )

        val path = ref.path
        if (path == null) {
            if (ref.label.isNotEmpty()) text.append(ref.label)
            return
        }

        inlineContent.getOrPut(mxcUri) {
            InlineTextContent(
                placeholder = Placeholder(
                    width = EMOTE_HEIGHT,
                    height = EMOTE_HEIGHT,
                    placeholderVerticalAlign = PlaceholderVerticalAlign.Center
                )
            ) {
                EmoteImage(ref)
            }
        }
        text.appendInlineContent(mxcUri, alternateText = ref.label)
    }
}

@Composable
private fun EmoteImage(emote: EmoteRef) {
    val path = emote.path

    if (path == null) {
        if (emote.label.isNotEmpty()) {
            Text(
                text = emote.label,
                color = LocalContentColor.current.copy(alpha = 0.7f),
                style = MaterialTheme.typography.bodyMedium.copy(
                    fontSize = LocalMessageFontSize.current.sp
                )
            )
        }
        return
    }

    val height = with(LocalDensity.current) { EMOTE_HEIGHT.toDp() }
    AsyncImage(
        model = ImageRequest.Builder(LocalPlatformContext.current)
            .data(path)
            .crossfade(true)
            .build(),
        contentDescription = emote.label,
        modifier = Modifier.size(height)
    )
}

/**
 * Renders a message body, preferring `formatted_body` when the sender supplied
 * one and falling back to the plaintext `body` otherwise.
 *
 * Spoilers start concealed and are revealed by tapping them, which is the
 * disclosure the spec asks for. Concealing paints the glyphs in the container
 * colour, so the run keeps its width and the surrounding text does not reflow,
 * but the hidden words are not legible.
 */
@Composable
fun FormattedBodyText(
    formattedBody: String?,
    fallbackBody: String,
    emotePaths: Map<String, String> = emptyMap(),
    color: Color = LocalContentColor.current,
    containerColor: Color = MaterialTheme.colorScheme.surface,
    style: TextStyle = MaterialTheme.typography.bodyMedium,
    onLinkClick: ((String) -> Unit)? = null
) {
    val baseStyle = style.copy(fontSize = LocalMessageFontSize.current.sp)
    val revealed = remember { mutableStateSetOf<Int>() }

    // A concealed spoiler carries a clickable annotation that reveals it, so
    // the parse only has to be redone when the set of revealed ones changes.
    val parsed = remember(formattedBody, emotePaths, containerColor, revealed.toList()) {
        formattedBody?.takeIf { it.isNotBlank() }?.let { html ->
            parseFormattedBody(html, emotePaths, containerColor, revealed.toSet()) { revealed.add(it) }
        }
    }

    if (parsed == null) {
        MarkdownText(
            text = fallbackBody,
            color = color,
            style = baseStyle,
            onLinkClick = onLinkClick
        )
        return
    }

    Text(
        text = parsed.text,
        color = color,
        style = baseStyle,
        inlineContent = parsed.inlineContent
    )
}
