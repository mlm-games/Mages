package org.mlm.mages.ui.components.core

import mages.shared.generated.resources.Res
import mages.shared.generated.resources.ic_sentiment_satisfied
import org.jetbrains.compose.resources.painterResource
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.InlineTextContent
import androidx.compose.foundation.text.appendInlineContent
import androidx.compose.material3.Icon
import androidx.compose.material3.LocalContentColor
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.mutableStateSetOf
import androidx.compose.runtime.remember
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.luminance
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.platform.LocalUriHandler
import androidx.compose.ui.text.AnnotatedString
import androidx.compose.ui.text.LinkAnnotation
import androidx.compose.ui.text.ParagraphStyle
import androidx.compose.ui.text.Placeholder
import androidx.compose.ui.text.PlaceholderVerticalAlign
import androidx.compose.ui.text.SpanStyle
import androidx.compose.ui.text.TextLinkStyles
import androidx.compose.ui.text.TextMeasurer
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontStyle
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.rememberTextMeasurer
import androidx.compose.ui.text.style.BaselineShift
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.text.style.TextDecoration
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.Density
import androidx.compose.ui.unit.TextUnit
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.compose.ui.unit.takeOrElse
import coil3.compose.AsyncImage
import coil3.compose.LocalPlatformContext
import coil3.request.ImageRequest
import coil3.request.crossfade
import com.fleeksoft.ksoup.Ksoup
import com.fleeksoft.ksoup.nodes.Element
import com.fleeksoft.ksoup.nodes.Node
import com.fleeksoft.ksoup.nodes.TextNode
import org.mlm.mages.LocalMessageFontSize
import org.mlm.mages.nav.MatrixLink
import org.mlm.mages.nav.isMatrixPermalink
import org.mlm.mages.nav.matrixUserIdFromLink
import org.mlm.mages.nav.parseMatrixLink

private val MXC_SRC_ATTR = Regex("""\ssrc\s*=\s*["']?(mxc://[^"'\s>]+)""", RegexOption.IGNORE_CASE)

/** Bounds the work a single hostile message can ask a client to do. */
private const val MAX_EMOTES_PER_MESSAGE = 32

private const val MAX_LINK_MENTIONS = 32
private const val MAX_LINK_MENTION_LABEL = 128

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

private val mentionAvatarSize = 16.dp
private val mentionAvatarGap = 4.dp
private val mentionPaddingHorizontal = 6.dp
private val mentionPaddingVertical = 1.dp
private val mentionCornerRadius = 6.dp

private val mentionChromeHeight = mentionAvatarSize + mentionPaddingVertical * 2

/** The layouter floors a placeholder width back to whole pixels, so a reserved box is a pixel wider. */
private const val MENTION_PLACEHOLDER_SLACK_PX = 1f

/** Matches the tint the file-attachment box uses, so both inset elements agree. */
private const val MENTION_TINT_ALPHA = 0.15f

private val LINK_ON_LIGHT = Color(0xFF0B57D0)
private val LINK_ON_DARK = Color(0xFF8AB4F8)

/** The link blue that stays legible on [background]. */
fun linkColorOn(background: Color): Color =
    if (background.luminance() > 0.24f) LINK_ON_LIGHT else LINK_ON_DARK

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

/** Block elements that start on their own line. Lists break lines per item instead. */
private val BLOCK_TAGS = setOf("p", "div", "h1", "h2", "h3", "h4", "h5", "h6")

/** Heading sizes relative to the message font size, matching the plain-markdown renderer. */
private val HEADING_SCALES = mapOf(
    "h1" to 2.0f,
    "h2" to 1.8f,
    "h3" to 1.6f,
    "h4" to 1.4f,
    "h5" to 1.2f,
    "h6" to 1.1f,
)

/** Sub- and superscripts shrink as well as shift, which is what HTML and Android do. */
private const val SCRIPT_SCALE = 0.75f

/** Leading for a heading line, since the body's line height is too short for it. */
private const val HEADING_LINE_HEIGHT = 1.15f

/** The bar that marks a quoted line; one per level of nesting. */
private const val QUOTE_RAIL = "▏"

/** Bounds the indentation a hostile message can ask a quote for. */
private const val MAX_QUOTE_DEPTH = 3

/** Table parts that only group rows or cells, so they contribute no text of their own. */
private val TABLE_CONTAINER_TAGS = setOf("thead", "tbody", "tfoot")

/** Separates the cells of one row once a table is flattened to text. */
private const val TABLE_CELL_SEPARATOR = " · "

/** A cell holds one row of text, so even its source line breaks collapse. */
private val TABLE_CELL_WHITESPACE = Regex("[ \t\r\n\u000B\u000C]+")

/** Trailing whitespace that runs into a block boundary, which HTML treats as nothing. */
private val TRAILING_BLOCK_WS = Regex("""[ \t\r]*(?:\r?\n[ \t\r]*)+\z""")

/** What an [`ol`][Element] counts from, restarted for every nested list. */
private sealed interface ListContext {
    data object Unordered : ListContext
    class Ordered(var next: Int = 1) : ListContext
}

/** The attribute the spec marks a spoiler span with. The value is the reason. */
private const val SPOILER_ATTR = "data-mx-spoiler"

/** The link tag a concealed spoiler is annotated with, so a tap can reveal it. */
private const val SPOILER_TAG = "mages-spoiler"

/** Inline-content key prefix for a mention pill, which is keyed by user ID. */
private const val MENTION_KEY_PREFIX = "mention:"

/** Inline-content key prefix for a room pill, which is keyed by target and label. */
private const val ROOM_KEY_PREFIX = "mention:room:"

/** Bounds the room pills one message can ask a client to measure and lay out. */
private const val MAX_ROOM_PILLS = 32

/** Inline-content key for the `@room` pill, which has no user behind it. */
private const val EVERYONE_KEY = "mention:@room"

/** What `@room` is labelled with, which the spec fixes. */
private const val EVERYONE_LABEL = "@room"

/** Schemes a link may not point at (anything else renders as plain text) */
private val BLOCKED_SCHEMES = listOf("javascript:", "data:", "vbscript:", "file:")

private val WHITESPACE_RUN = Regex("[ \\t\\u000B\\u000C\\r]+")

/** `@room` as a standalone word, which is what `m.mentions.room` refers to. */
private val EVERYONE_MENTION = Regex("(?<![\\w@])@room(?![\\w@])")

/** A room alias in plain text, which Element X also renders as a pill. */
private val ROOM_ALIAS = Regex(
    """(?<![\w#/])#[A-Za-z0-9._=\-/+]+:[A-Za-z0-9](?:[A-Za-z0-9.\-]*[A-Za-z0-9])?(?::[0-9]{2,5})?"""
)

/** An inline `<img src="mxc://…">`. [path] is the resolved local file, if fetched. */
data class EmoteRef(
    val mxcUri: String,
    val alt: String?,
    val title: String?,
    val path: String? = null,
    val mime: String? = null,
    val isAnimated: Boolean? = null
) {
    val label: String get() = alt?.takeIf { it.isNotBlank() } ?: title?.takeIf { it.isNotBlank() } ?: ""
}

data class MentionRef(
    val userId: String,
    val label: String,
    val avatarPath: String? = null,
)

/** A room link or alias shown as a pill rather than as a link. */
data class RoomMentionRef(
    val target: String,
    val label: String,
    val permalink: String,
)

class MentionPillLayout(
    val widthByUserId: Map<String, TextUnit>,
    val widthByRoomLabel: Map<String, TextUnit> = emptyMap(),
    val height: TextUnit,
    val background: Color,
    val content: Color,
    val fontSize: TextUnit,
    val everyoneWidth: TextUnit? = null,
)

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
    onReveal: (Int) -> Unit = {},
    mentionRefs: Map<String, MentionRef> = emptyMap(),
    roomRefs: Map<String, RoomMentionRef> = emptyMap(),
    pillLayout: MentionPillLayout? = null,
    onMentionClick: (String) -> Unit = {},
    quoteRailColor: Color? = null,
    baseFontSize: TextUnit? = null,
    linkColor: Color = LINK_ON_LIGHT,
): FormattedBody {
    val builder = Builder(
        emotePaths, conceal, revealed, onReveal, mentionRefs, roomRefs, pillLayout, onMentionClick,
        quoteRailColor, baseFontSize, linkColor
    )
    builder.walk(Ksoup.parse(html).body())
    return builder.build()
}

/** A pill found in a text node: `@room` when [roomAlias] is null, else a room alias. */
private class TextPillMatch(val range: IntRange, val roomAlias: String?)

private fun roomAliasRef(alias: String) = RoomMentionRef(
    target = alias,
    label = alias,
    permalink = roomPermalink(alias),
)

private fun roomPermalink(target: String, via: List<String> = emptyList()): String {
    val base = "https://matrix.to/#/" + target
    if (via.isEmpty()) return base
    return base + "?via=" + via.joinToString("&via=")
}

private const val ROOM_REF_SEPARATOR = '\u0000'

private fun isMatrixHref(href: String): Boolean =
    href.startsWith("matrix:", ignoreCase = true) || href.contains("://")

private fun anchorLabel(element: Element): String? =
    element.text().replace(WHITESPACE_RUN, " ").trim()
        .takeIf { it.isNotEmpty() && it.length <= MAX_LINK_MENTION_LABEL }

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
    private val onReveal: (Int) -> Unit,
    private val mentionRefs: Map<String, MentionRef> = emptyMap(),
    private val roomRefs: Map<String, RoomMentionRef> = emptyMap(),
    private val pillLayout: MentionPillLayout? = null,
    private val onMentionClick: (String) -> Unit = {},
    private val quoteRailColor: Color? = null,
    private val baseFontSize: TextUnit? = null,
    private val linkColor: Color = LINK_ON_LIGHT,
) {
    private val text = AnnotatedString.Builder()
    private val inlineContent = mutableMapOf<String, InlineTextContent>()
    private val linkStyle = SpanStyle(color = linkColor, textDecoration = TextDecoration.Underline)
    private var spoilers = 0
    private val listStack = ArrayDeque<ListContext>()
    private var atLineStart = true
    private var quoteDepth = 0
    private var quoteRails = 0

    private var tableRowStart = 0
    private var tableCellStart = 0
    private var inTableCell = false
    private var preDepth = 0
    private var roomPills = 0
    private var concealedDepth = 0
    private var fontScale = 1f

    fun build() = FormattedBody(text.toAnnotatedString(), inlineContent)

    fun walk(node: Node, linkify: Boolean = true) {
        when (node) {
            is TextNode -> appendText(node.getWholeText(), linkify)
            is Element -> walkElement(node, linkify)
            else -> Unit
        }
    }

    private fun ensureLineStart() {
        if (!atLineStart) appendLineBreak()
    }

    private fun appendLineBreak() {
        text.append('\n')
        atLineStart = true
        quoteRails = 0
    }

    /** Added lazily, so an empty blockquote leaves no rail behind. */
    private fun appendQuoteRail() {
        if (!atLineStart || quoteDepth == 0 || inTableCell) return
        val levels = quoteDepth.coerceAtMost(MAX_QUOTE_DEPTH)
        if (quoteRails >= levels) return
        if (quoteRailColor != null) text.pushStyle(SpanStyle(color = quoteRailColor))
        repeat(levels - quoteRails) { text.append(QUOTE_RAIL) }
        if (quoteRailColor != null) text.pop()
        text.append(' ')
        quoteRails = levels
    }

    /**
     * Walks a block container's children, dropping the whitespace-only text
     * nodes pretty-printed HTML leaves between the tags. Those carry no
     * content; the blocks break their own lines.
     */
    private fun walkBlockChildren(element: Element, linkify: Boolean) {
        val tag = element.normalName()
        val childLinkify = linkify && tag != "a" && tag !in CODE_TAGS
        if (preDepth > 0) {
            element.childNodes().forEach { walk(it, childLinkify) }
            return
        }
        element.childNodes().forEach { child ->
            if (child is TextNode) {
                val raw = child.getWholeText()
                if ('\n' in raw) {
                    if (raw.all { it.isWhitespace() }) return@forEach
                    val trimmed = if (inTableCell) raw else raw.replace(TRAILING_BLOCK_WS, "")
                    if (trimmed.isEmpty()) return@forEach
                    if (trimmed.length != raw.length) {
                        appendText(trimmed, childLinkify)
                        return@forEach
                    }
                }
            }
            walk(child, childLinkify)
        }
    }

    private fun walkElement(element: Element, linkify: Boolean) {
        val tag = element.normalName()

        if (tag in DROPPED_TAGS) return
        if (tag in BREAK_TAGS) {
            appendLineBreak()
            return
        }
        if (tag == "img") {
            if (concealedDepth > 0) {
                element.attr("alt").takeIf { it.isNotBlank() }?.let { appendText(it, false) }
            } else {
                appendEmote(element)
            }
            return
        }
        if (isSpoiler(element)) {
            walkSpoiler(element, linkify)
            return
        }

        if (tag == "a" && concealedDepth == 0) {
            val mention = mentionedUserOf(element)
            if (mention != null) {
                appendMention(mention)
                return
            }
            val room = roomMentionOf(element)
            if (room != null && appendRoomMention(room)) return
        }

        if (tag == "ul" || tag == "ol") {
            val context = if (tag == "ol") {
                ListContext.Ordered(element.attr("start").trim().toIntOrNull() ?: 1)
            } else {
                ListContext.Unordered
            }
            listStack.addLast(context)
            walkBlockChildren(element, linkify)
            listStack.removeLastOrNull()
            return
        }
        if (tag == "li") {
            ensureLineStart()
            appendQuoteRail()
            val depth = listStack.size.coerceAtLeast(1)
            repeat(depth - 1) { text.append("  ") }
            when (val context = listStack.lastOrNull()) {
                is ListContext.Ordered -> {
                    text.append("${context.next}. ")
                    context.next++
                }
                else -> text.append("• ")
            }
            // A loose list wraps the item in a paragraph; the item already
            // started its line, so that paragraph must not break it again.
            atLineStart = true
            walkBlockChildren(element, linkify)
            atLineStart = false
            return
        }
        if (tag == "table") {
            ensureLineStart()
            walkBlockChildren(element, linkify)
            return
        }
        if (tag == "tr") {
            ensureLineStart()
            tableRowStart = text.length
            walkBlockChildren(element, linkify)
            return
        }
        if (tag in TABLE_CONTAINER_TAGS) {
            walkBlockChildren(element, linkify)
            return
        }
        if (tag == "caption") {
            ensureLineStart()
            walkBlockChildren(element, linkify)
            return
        }
        if (tag == "td" || tag == "th") {
            if (text.length > tableRowStart) text.append(TABLE_CELL_SEPARATOR)
            tableCellStart = text.length
            val wasInTableCell = inTableCell
            inTableCell = true
            if (tag == "th") text.pushStyle(SpanStyle(fontWeight = FontWeight.Bold))
            walkBlockChildren(element, linkify)
            if (tag == "th") text.pop()
            inTableCell = wasInTableCell
            return
        }
        if (tag == "pre") {
            ensureLineStart()
            preDepth++
            walkBlockChildren(element, linkify)
            preDepth--
            return
        }
        if (tag == "blockquote") {
            if (inTableCell) {
                if (!atLineStart && text.length > tableCellStart) text.append(" ")
            } else {
                ensureLineStart()
            }
            quoteDepth++
            walkBlockChildren(element, linkify)
            quoteDepth--
            return
        }
        if (tag == "hr") {
            if (!inTableCell) {
                ensureLineStart()
                appendLineBreak()
            }
            return
        }
        if (tag in BLOCK_TAGS) {
            if (inTableCell) {
                if (!atLineStart && text.length > tableCellStart) text.append(" ")
            } else {
                ensureLineStart()
            }
            val scale = HEADING_SCALES[tag]
            val previousScale = fontScale
            val start = text.length
            val heading = scale?.let {
                fontScale *= it
                headingStyle()
            }
            if (heading != null) text.pushStyle(heading)
            walkBlockChildren(element, linkify)
            if (heading != null) text.pop()
            if (scale != null) {
                if (text.length > start) {
                    baseFontSize?.let { base ->
                        text.addStyle(
                            ParagraphStyle(lineHeight = base * fontScale * HEADING_LINE_HEIGHT),
                            start,
                            text.length,
                        )
                    }
                }
                fontScale = previousScale
            }
            return
        }
        if (tag == "sub" || tag == "sup") {
            val script = scriptStyle(tag == "sup")
            text.pushStyle(script)
            element.childNodes().forEach { walk(it, linkify) }
            text.pop()
            return
        }

        val link = if (tag == "a" && concealedDepth == 0) linkFor(element) else null
        val style = styleFor(tag, link != null)

        if (style != null) text.pushStyle(style)
        if (link != null) {
            text.pushLink(
                LinkAnnotation.Url(
                    url = link,
                    styles = TextLinkStyles(SpanStyle(color = linkColor))
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

        if (concealed) {
            concealedDepth++
        }

        element.childNodes().forEach { walk(it, linkify && !concealed) }

        if (concealed) {
            concealedDepth--
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

    private fun mentionedUserOf(element: Element): MentionRef? {
        if (mentionRefs.isEmpty() || pillLayout == null) return null
        val href = element.attr("href").trim()
        if (href.isEmpty()) return null
        val userId = matrixUserIdFromLink(href) ?: return null
        val ref = mentionRefs[userId] ?: return null
        return if (pillLayout.widthByUserId.containsKey(userId)) ref else null
    }

    private fun roomMentionOf(element: Element): RoomMentionRef? {
        if (roomRefs.isEmpty() || pillLayout == null) return null
        val href = element.attr("href").trim()
        if (href.isEmpty() || !isMatrixHref(href)) return null
        val link = parseMatrixLink(href)
        if (link !is MatrixLink.Room || link.target.eventId != null) return null
        val label = anchorLabel(element) ?: return null
        val key = link.target.roomIdOrAlias + ROOM_REF_SEPARATOR + label
        val ref = roomRefs[key] ?: return null
        return if (pillLayout.widthByRoomLabel.containsKey(label)) ref else null
    }

    private fun appendMention(ref: MentionRef) {
        val layout = pillLayout ?: return
        val width = layout.widthByUserId[ref.userId] ?: return
        val id = MENTION_KEY_PREFIX + ref.userId
        appendQuoteRail()

        inlineContent.getOrPut(id) {
            InlineTextContent(
                placeholder = Placeholder(
                    width = width,
                    height = layout.height,
                    placeholderVerticalAlign = PlaceholderVerticalAlign.Center,
                )
            ) {
                MentionPill(
                    label = ref.label,
                    avatarPath = ref.avatarPath,
                    background = layout.background,
                    content = layout.content,
                    fontSize = layout.fontSize,
                    onClick = { onMentionClick(ref.userId) },
                )
            }
        }
        text.appendInlineContent(id, alternateText = ref.label)
        atLineStart = false
    }

    private fun appendRoomMention(ref: RoomMentionRef): Boolean {
        val layout = pillLayout ?: return false
        if (roomPills >= MAX_ROOM_PILLS) return false
        val width = layout.widthByRoomLabel[ref.label] ?: return false
        roomPills++
        val id = ROOM_KEY_PREFIX + ref.target + ':' + ref.label
        appendQuoteRail()

        inlineContent.getOrPut(id) {
            InlineTextContent(
                placeholder = Placeholder(
                    width = width,
                    height = layout.height,
                    placeholderVerticalAlign = PlaceholderVerticalAlign.Center,
                )
            ) {
                MentionPill(
                    label = ref.label,
                    avatarPath = null,
                    background = layout.background,
                    content = layout.content,
                    fontSize = layout.fontSize,
                    onClick = null,
                    url = ref.permalink,
                )
            }
        }
        text.appendInlineContent(id, alternateText = ref.label)
        atLineStart = false
        return true
    }

    private fun styleFor(tag: String, isLink: Boolean): SpanStyle? = when {
        tag in BOLD_TAGS -> SpanStyle(fontWeight = FontWeight.Bold)
        tag in ITALIC_TAGS -> SpanStyle(fontStyle = FontStyle.Italic)
        tag in UNDERLINE_TAGS -> SpanStyle(textDecoration = TextDecoration.Underline)
        tag in STRIKE_TAGS -> SpanStyle(textDecoration = TextDecoration.LineThrough)
        tag == "code" -> SpanStyle(fontFamily = FontFamily.Monospace)
        isLink -> linkStyle
        else -> null
    }

    /** Headings are bold and scaled off the body size, the way markdown bodies render. */
    private fun headingStyle(): SpanStyle {
        val size = baseFontSize?.let { it * fontScale } ?: TextUnit.Unspecified
        return SpanStyle(fontSize = size, fontWeight = FontWeight.Bold)
    }

    private fun scriptStyle(superscript: Boolean): SpanStyle = SpanStyle(
        baselineShift = if (superscript) BaselineShift.Superscript else BaselineShift.Subscript,
        fontSize = baseFontSize?.let { it * fontScale * SCRIPT_SCALE } ?: TextUnit.Unspecified,
    )

    private fun appendText(raw: String, linkify: Boolean) {
        val collapsed = when {
            preDepth > 0 -> raw
            inTableCell -> raw.replace(TABLE_CELL_WHITESPACE, " ")
            else -> raw.replace(WHITESPACE_RUN, " ")
        }
        if (collapsed.isEmpty()) return
        appendQuoteRail()

        val matches = if (linkify) textPillMatches(collapsed) else emptyList()
        if (matches.isEmpty()) {
            appendPlain(collapsed, linkify)
            return
        }

        var cursor = 0
        for (match in matches) {
            if (match.range.first > cursor) {
                appendPlain(collapsed.substring(cursor, match.range.first), linkify)
            }
            if (match.roomAlias != null) {
                if (!appendRoomMention(roomAliasRef(match.roomAlias))) {
                    appendPlain(match.roomAlias, linkify)
                }
            } else {
                appendEveryonePill()
            }
            cursor = match.range.last + 1
        }
        if (cursor < collapsed.length) {
            appendPlain(collapsed.substring(cursor), linkify)
        } else {
            atLineStart = false
        }
    }

    private fun textPillMatches(text: String): List<TextPillMatch> {
        val layout = pillLayout ?: return emptyList()
        val out = mutableListOf<TextPillMatch>()
        if (layout.everyoneWidth != null) {
            EVERYONE_MENTION.findAll(text).forEach { out += TextPillMatch(it.range, null) }
        }
        if (layout.widthByRoomLabel.isNotEmpty()) {
            val urls = BARE_URL.findAll(text).map { it.range }.toList()
            ROOM_ALIAS.findAll(text).forEach { match ->
                if (urls.any { match.range.first in it }) return@forEach
                if (!layout.widthByRoomLabel.containsKey(match.value)) return@forEach
                out += TextPillMatch(match.range, match.value)
            }
        }
        if (out.size < 2) return out
        out.sortBy { it.range.first }
        return out
    }

    private fun appendEveryonePill() {
        val layout = pillLayout ?: return
        val width = layout.everyoneWidth ?: return
        val id = EVERYONE_KEY
        appendQuoteRail()
        inlineContent.getOrPut(id) {
            InlineTextContent(
                placeholder = Placeholder(
                    width = width,
                    height = layout.height,
                    placeholderVerticalAlign = PlaceholderVerticalAlign.Center,
                )
            ) {
                MentionPill(
                    EVERYONE_LABEL, null, layout.background, layout.content, layout.fontSize, null
                )
            }
        }
        text.appendInlineContent(id, alternateText = EVERYONE_LABEL)
        atLineStart = false
    }

    private fun appendPlain(collapsed: String, linkify: Boolean) {
        if (collapsed.isEmpty()) return
        if (!linkify) {
            text.append(collapsed)
            atLineStart = collapsed.endsWith('\n')
            if (atLineStart) quoteRails = 0
            return
        }

        var cursor = 0
        for (match in BARE_URL.findAll(collapsed)) {
            val start = match.range.first
            if (start > 0 && isAsciiLetterOrDigit(collapsed[start - 1])) continue
            val url = trimUrlTail(match.value)
            if (start > cursor) text.append(collapsed.substring(cursor, start))
            text.pushStyle(linkStyle)
            text.pushLink(
                LinkAnnotation.Url(
                    url = absoluteUrl(url),
                    styles = TextLinkStyles(SpanStyle(color = linkColor))
                )
            )
            text.append(url)
            text.pop()
            text.pop()
            cursor = start + url.length
        }
        if (cursor < collapsed.length) text.append(collapsed.substring(cursor))
        atLineStart = cursor < collapsed.length && collapsed.endsWith('\n')
        if (atLineStart) quoteRails = 0
    }

    private fun isAsciiLetterOrDigit(c: Char): Boolean =
        c in 'a'..'z' || c in 'A'..'Z' || c in '0'..'9'

    private fun appendEmote(element: Element) {
        appendQuoteRail()
        val mxcUri = element.attr("src").trim()
        if (!mxcUri.startsWith("mxc://")) {
            element.attr("alt").trim().ifEmpty { null }?.let { text.append(it) }
            atLineStart = false
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
            inlineContent.getOrPut(mxcUri) {
                InlineTextContent(
                    placeholder = Placeholder(
                        width = EMOTE_HEIGHT,
                        height = EMOTE_HEIGHT,
                        placeholderVerticalAlign = PlaceholderVerticalAlign.Center
                    )
                ) {
                    EmotePlaceholder()
                }
            }
            text.appendInlineContent(mxcUri, alternateText = ref.label)
            atLineStart = false
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
        atLineStart = false
    }
}

@Composable
private fun EmotePlaceholder() {
    val size = with(LocalDensity.current) { EMOTE_HEIGHT.toDp() }
    Box(
        modifier = Modifier
            .size(size)
            .clip(RoundedCornerShape(4.dp))
            .background(LocalContentColor.current.copy(alpha = 0.12f)),
        contentAlignment = Alignment.Center
    ) {
        Icon(
            painter = painterResource(Res.drawable.ic_sentiment_satisfied),
            contentDescription = null,
            tint = LocalContentColor.current.copy(alpha = 0.5f),
            modifier = Modifier.size(size * 0.6f)
        )
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

@Composable
private fun MentionPill(
    label: String,
    avatarPath: String?,
    background: Color,
    content: Color,
    fontSize: TextUnit,
    onClick: (() -> Unit)?,
    url: String? = null,
) {
    val uriHandler = LocalUriHandler.current
    val click: (() -> Unit)? = onClick ?: url?.let { target ->
        { runCatching { uriHandler.openUri(target) } }
    }
    Row(
        verticalAlignment = Alignment.CenterVertically,
        modifier = Modifier
            .clip(RoundedCornerShape(mentionCornerRadius))
            .background(background, RoundedCornerShape(mentionCornerRadius))
            .then(if (click != null) Modifier.clickable(onClick = click) else Modifier)
            .padding(horizontal = mentionPaddingHorizontal, vertical = mentionPaddingVertical),
    ) {
        avatarPath?.let { path ->
            AsyncImage(
                model = ImageRequest.Builder(LocalPlatformContext.current)
                    .data(path)
                    .crossfade(true)
                    .build(),
                contentDescription = null,
                contentScale = ContentScale.Crop,
                modifier = Modifier
                    .size(mentionAvatarSize)
                    .clip(CircleShape)
            )
            Spacer(Modifier.width(mentionAvatarGap))
        }
        Text(
            text = label,
            color = content,
            maxLines = 1,
            softWrap = false,
            overflow = TextOverflow.Ellipsis,
            style = MaterialTheme.typography.bodyMedium.copy(fontSize = fontSize)
        )
    }
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
    modifier: Modifier = Modifier,
    emotePaths: Map<String, String> = emptyMap(),
    color: Color = LocalContentColor.current,
    containerColor: Color = MaterialTheme.colorScheme.surface,
    style: TextStyle = MaterialTheme.typography.bodyMedium,
    onLinkClick: ((String) -> Unit)? = null,
    mentionRefs: Map<String, MentionRef> = emptyMap(),
    mentionsRoom: Boolean = false,
    onMentionClick: ((String) -> Unit)? = null,
    maxLines: Int = Int.MAX_VALUE,
    overflow: TextOverflow = TextOverflow.Clip,
    textAlign: TextAlign? = null,
    useMessageFontSize: Boolean = true,
) {
    val baseStyle = if (useMessageFontSize) {
        style.copy(fontSize = LocalMessageFontSize.current.sp)
    } else {
        style
    }
    val revealed = remember(formattedBody) { mutableStateSetOf<Int>() }
    val density = LocalDensity.current
    val measurer = rememberTextMeasurer()
    val linkedMentions = remember(formattedBody) { linkedMentionsFrom(formattedBody) }
    val allMentionRefs = remember(linkedMentions, mentionRefs) {
        linkedMentions.users + mentionRefs
    }
    val bodyFontSize = baseStyle.fontSize.takeOrElse { LocalMessageFontSize.current.sp }
    val rawRoomLabels = remember(fallbackBody) {
        buildSet {
            for (match in ROOM_ALIAS.findAll(fallbackBody)) {
                add(match.value)
                if (size >= MAX_ROOM_PILLS) break
            }
        }
    }

    // The pill is tinted from the bubble's own text colour, the way the file-attachment box
    // is, so it reads as an inset of the bubble instead of a themed block laid over it. That
    // keeps it legible on both the sent and received bubbles without a colour per variant.
    val pillLayout = remember(
        allMentionRefs, linkedMentions.rooms, rawRoomLabels, mentionsRoom,
        measurer, density, color, baseStyle
    ) {
        measureMentionPills(
            refs = allMentionRefs,
            roomRefs = linkedMentions.rooms,
            rawRoomLabels = rawRoomLabels,
            mentionsRoom = mentionsRoom,
            measurer = measurer,
            style = baseStyle,
            density = density,
            content = color,
            fontSize = bodyFontSize,
        )
    }

    // A concealed spoiler carries a clickable annotation that reveals it, so
    // the parse only has to be redone when the set of revealed ones changes.
    val parsed = remember(
        formattedBody, emotePaths, containerColor, color, bodyFontSize, revealed.toList(), pillLayout
    ) {
        formattedBody?.takeIf { it.isNotBlank() }?.let { html ->
            parseFormattedBody(
                html = html,
                emotePaths = emotePaths,
                conceal = containerColor,
                revealed = revealed.toSet(),
                onReveal = { revealed.add(it) },
                mentionRefs = allMentionRefs,
                roomRefs = linkedMentions.rooms,
                pillLayout = pillLayout,
                onMentionClick = { userId -> onMentionClick?.invoke(userId) },
                quoteRailColor = color.copy(alpha = 0.45f),
                baseFontSize = bodyFontSize,
                linkColor = linkColorOn(containerColor),
            )
        }
    }

    if (parsed == null) {
        if (useMessageFontSize && maxLines == Int.MAX_VALUE) {
            MarkdownText(
                text = fallbackBody,
                modifier = modifier,
                color = color,
                style = baseStyle,
                onLinkClick = onLinkClick
            )
        } else {
            Text(
                text = fallbackBody,
                modifier = modifier,
                color = color,
                style = baseStyle,
                maxLines = maxLines,
                overflow = overflow,
                textAlign = textAlign
            )
        }
        return
    }

    Text(
        text = parsed.text,
        modifier = modifier,
        color = color,
        style = baseStyle,
        inlineContent = parsed.inlineContent,
        maxLines = maxLines,
        overflow = overflow,
        textAlign = textAlign
    )
}

@Composable
fun TopicText(
    topic: String?,
    topicHtml: String?,
    modifier: Modifier = Modifier,
    style: TextStyle = MaterialTheme.typography.bodyMedium,
    color: Color = MaterialTheme.colorScheme.onSurfaceVariant,
    maxLines: Int = Int.MAX_VALUE,
    overflow: TextOverflow = TextOverflow.Clip,
    textAlign: TextAlign? = null,
) {
    if (topic.isNullOrBlank() && topicHtml.isNullOrBlank()) return
    FormattedBodyText(
        formattedBody = topicHtml?.takeIf { it.isNotBlank() },
        fallbackBody = topic.orEmpty(),
        modifier = modifier,
        color = color,
        containerColor = MaterialTheme.colorScheme.surface,
        style = style,
        maxLines = maxLines,
        overflow = overflow,
        textAlign = textAlign,
        useMessageFontSize = false
    )
}

private class LinkedMentions(
    val users: Map<String, MentionRef> = emptyMap(),
    val rooms: Map<String, RoomMentionRef> = emptyMap(),
)

private fun linkedMentionsFrom(html: String?): LinkedMentions {
    if (html == null) return LinkedMentions()
    val hasMatrixLink = html.contains("matrix.to/", ignoreCase = true) ||
        html.contains("matrix:", ignoreCase = true)
    if (!hasMatrixLink) return LinkedMentions()

    val users = LinkedHashMap<String, MentionRef>()
    val rooms = LinkedHashMap<String, RoomMentionRef>()
    for (anchor in Ksoup.parse(html).body().getElementsByTag("a")) {
        if (users.size + rooms.size >= MAX_LINK_MENTIONS) break
        val href = anchor.attr("href").trim()
        if (href.isEmpty() || !isMatrixHref(href)) continue
        val label = anchorLabel(anchor) ?: continue
        when (val link = parseMatrixLink(href)) {
            is MatrixLink.User -> if (link.mxid !in users) {
                users[link.mxid] = MentionRef(link.mxid, label)
            }

            is MatrixLink.Room -> {
                val target = link.target
                if (target.eventId == null) {
                    val key = target.roomIdOrAlias + ROOM_REF_SEPARATOR + label
                    if (key !in rooms) {
                        rooms[key] = RoomMentionRef(
                            target = target.roomIdOrAlias,
                            label = label,
                            permalink = roomPermalink(target.roomIdOrAlias, target.via),
                        )
                    }
                }
            }

            MatrixLink.Unsupported -> Unit
        }
    }
    return LinkedMentions(users, rooms)
}

private fun measureMentionPills(
    refs: Map<String, MentionRef>,
    roomRefs: Map<String, RoomMentionRef>,
    rawRoomLabels: Set<String>,
    mentionsRoom: Boolean,
    measurer: TextMeasurer,
    style: TextStyle,
    density: Density,
    content: Color,
    fontSize: TextUnit,
): MentionPillLayout? {
    val anchorLabels = roomRefs.values.map { it.label }.distinct().take(MAX_ROOM_PILLS)
    val roomLabels = (anchorLabels + rawRoomLabels.take(MAX_ROOM_PILLS)).distinct()
    if (refs.isEmpty() && !mentionsRoom && roomLabels.isEmpty()) return null

    // A placeholder is sized in sp, which the layouter converts back to px with the same
    // density and font scale the measurement used, so the reserved box matches what the
    // pill then draws.
    val widths = LinkedHashMap<String, TextUnit>(refs.size)
    val roomLabelWidths = LinkedHashMap<String, TextUnit>(roomLabels.size)
    with(density) {
        refs.forEach { (userId, ref) ->
            val labelPx = measurer.measure(ref.label, style).size.width
            val chromePx = mentionPaddingHorizontal.roundToPx() * 2 +
                if (ref.avatarPath != null) {
                    mentionAvatarSize.roundToPx() + mentionAvatarGap.roundToPx()
                } else {
                    0
                }
            widths[userId] = (labelPx + chromePx + MENTION_PLACEHOLDER_SLACK_PX).toSp()
        }

        roomLabels.forEach { label ->
            val labelPx = measurer.measure(label, style).size.width
            roomLabelWidths[label] = (
                labelPx + mentionPaddingHorizontal.roundToPx() * 2 +
                    MENTION_PLACEHOLDER_SLACK_PX
                ).toSp()
        }

        val lineHeightPx = measurer.measure("Ag", style).size.height.toFloat()
        val heightPx = maxOf(mentionChromeHeight.toPx(), lineHeightPx)
        val everyoneWidth = if (mentionsRoom) {
            val labelPx = measurer.measure(EVERYONE_LABEL, style).size.width
            (labelPx + mentionPaddingHorizontal.roundToPx() * 2 + MENTION_PLACEHOLDER_SLACK_PX).toSp()
        } else {
            null
        }
        return MentionPillLayout(
            widthByUserId = widths,
            widthByRoomLabel = roomLabelWidths,
            height = heightPx.toSp(),
            background = content.copy(alpha = MENTION_TINT_ALPHA),
            content = content,
            fontSize = fontSize,
            everyoneWidth = everyoneWidth,
        )
    }
}
