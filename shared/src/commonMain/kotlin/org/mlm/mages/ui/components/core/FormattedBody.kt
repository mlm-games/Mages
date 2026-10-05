package org.mlm.mages.ui.components.core

import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.InlineTextContent
import androidx.compose.foundation.text.appendInlineContent
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
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.text.AnnotatedString
import androidx.compose.ui.text.LinkAnnotation
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
import androidx.compose.ui.text.style.TextDecoration
import androidx.compose.ui.unit.Density
import androidx.compose.ui.unit.TextUnit
import androidx.compose.ui.unit.dp
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
import org.mlm.mages.nav.matrixUserIdFromLink

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

private val mentionAvatarSize = 16.dp
private val mentionAvatarGap = 4.dp
private val mentionPaddingHorizontal = 6.dp
private val mentionPaddingVertical = 1.dp
private val mentionCornerRadius = 6.dp

/** What a pill costs beyond its glyphs, which [measureMentionPills] adds to the measured text. */
private val mentionChromeWidth = mentionAvatarSize + mentionAvatarGap + mentionPaddingHorizontal * 2
private val mentionChromeHeight = mentionAvatarSize + mentionPaddingVertical * 2

/** Matches the tint the file-attachment box uses, so both inset elements agree. */
private const val MENTION_TINT_ALPHA = 0.15f

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

/** Inline-content key prefix for a mention pill, which is keyed by user ID. */
private const val MENTION_KEY_PREFIX = "mention:"

/** Inline-content key for the `@room` pill, which has no user behind it. */
private const val MENTION_ROOM_KEY = "mention:@room"

/** What `@room` is labelled with, which the spec fixes. */
private const val ROOM_LABEL = "@room"

/** Schemes a link may not point at (anything else renders as plain text) */
private val BLOCKED_SCHEMES = listOf("javascript:", "data:", "vbscript:", "file:")

private val WHITESPACE_RUN = Regex("[ \\t\\u000B\\u000C\\r]+")

/** `@room` as a standalone word, which is what `m.mentions.room` refers to. */
private val ROOM_MENTION = Regex("(?<![\\w@])@room(?![\\w@])")

/** An inline `<img src="mxc://…">`. [path] is the resolved local file, if fetched. */
data class EmoteRef(
    val mxcUri: String,
    val alt: String?,
    val title: String?,
    val path: String? = null
) {
    val label: String get() = alt?.takeIf { it.isNotBlank() } ?: title?.takeIf { it.isNotBlank() } ?: ""
}

data class MentionRef(
    val userId: String,
    val label: String,
    val avatarPath: String? = null,
)

class MentionPillLayout(
    val widthByUserId: Map<String, TextUnit>,
    val height: TextUnit,
    val background: Color,
    val content: Color,
    val roomWidth: TextUnit? = null,
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
    pillLayout: MentionPillLayout? = null,
    onMentionClick: (String) -> Unit = {}
): FormattedBody {
    val builder = Builder(
        emotePaths, conceal, revealed, onReveal, mentionRefs, pillLayout, onMentionClick
    )
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
    private val onReveal: (Int) -> Unit,
    private val mentionRefs: Map<String, MentionRef> = emptyMap(),
    private val pillLayout: MentionPillLayout? = null,
    private val onMentionClick: (String) -> Unit = {},
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

        if (tag == "a") {
            val mention = mentionedUserOf(element)
            if (mention != null) {
                appendMention(mention)
                return
            }
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

    private fun mentionedUserOf(element: Element): MentionRef? {
        if (mentionRefs.isEmpty() || pillLayout == null) return null
        val href = element.attr("href").trim()
        if (href.isEmpty()) return null
        val userId = matrixUserIdFromLink(href) ?: return null
        val ref = mentionRefs[userId] ?: return null
        return if (pillLayout.widthByUserId.containsKey(userId)) ref else null
    }

    private fun appendMention(ref: MentionRef) {
        val layout = pillLayout ?: return
        val width = layout.widthByUserId[ref.userId] ?: return
        val id = MENTION_KEY_PREFIX + ref.userId

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
                    onClick = { onMentionClick(ref.userId) },
                )
            }
        }
        text.appendInlineContent(id, alternateText = ref.label)
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

        val roomWidth = pillLayout?.roomWidth
        if (roomWidth != null) {
            var roomCursor = 0
            for (match in ROOM_MENTION.findAll(collapsed)) {
                if (match.range.first > roomCursor) {
                    appendPlain(collapsed.substring(roomCursor, match.range.first), linkify)
                }
                appendRoomPill(roomWidth)
                roomCursor = match.range.last + 1
            }
            if (roomCursor == 0) {
                appendPlain(collapsed, linkify)
                return
            }
            if (roomCursor < collapsed.length) appendPlain(collapsed.substring(roomCursor), linkify)
            return
        }

        appendPlain(collapsed, linkify)
    }

    private fun appendRoomPill(width: TextUnit) {
        val layout = pillLayout ?: return
        val id = MENTION_ROOM_KEY
        inlineContent.getOrPut(id) {
            InlineTextContent(
                placeholder = Placeholder(
                    width = width,
                    height = layout.height,
                    placeholderVerticalAlign = PlaceholderVerticalAlign.Center,
                )
            ) {
                MentionPill(ROOM_LABEL, null, layout.background, layout.content, null)
            }
        }
        text.appendInlineContent(id, alternateText = ROOM_LABEL)
    }

    private fun appendPlain(collapsed: String, linkify: Boolean) {
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

@Composable
private fun MentionPill(
    label: String,
    avatarPath: String?,
    background: Color,
    content: Color,
    onClick: (() -> Unit)?,
) {
    val fontSize = LocalMessageFontSize.current
    Row(
        verticalAlignment = Alignment.CenterVertically,
        modifier = Modifier
            .background(background, RoundedCornerShape(mentionCornerRadius))
            .then(if (onClick != null) Modifier.clickable(onClick = onClick) else Modifier)
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
            style = MaterialTheme.typography.bodyMedium.copy(fontSize = fontSize.sp)
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
    emotePaths: Map<String, String> = emptyMap(),
    color: Color = LocalContentColor.current,
    containerColor: Color = MaterialTheme.colorScheme.surface,
    style: TextStyle = MaterialTheme.typography.bodyMedium,
    onLinkClick: ((String) -> Unit)? = null,
    mentionRefs: Map<String, MentionRef> = emptyMap(),
    mentionsRoom: Boolean = false,
    onMentionClick: ((String) -> Unit)? = null,
) {
    val baseStyle = style.copy(fontSize = LocalMessageFontSize.current.sp)
    val revealed = remember { mutableStateSetOf<Int>() }
    val density = LocalDensity.current
    val measurer = rememberTextMeasurer()

    // The pill is tinted from the bubble's own text colour, the way the file-attachment box
    // is, so it reads as an inset of the bubble instead of a themed block laid over it. That
    // keeps it legible on both the sent and received bubbles without a colour per variant.
    val pillLayout = remember(
        mentionRefs, mentionsRoom, measurer, density, color, baseStyle
    ) {
        measureMentionPills(
            refs = mentionRefs,
            mentionsRoom = mentionsRoom,
            measurer = measurer,
            style = baseStyle,
            density = density,
            content = color,
        )
    }

    // A concealed spoiler carries a clickable annotation that reveals it, so
    // the parse only has to be redone when the set of revealed ones changes.
    val parsed = remember(
        formattedBody, emotePaths, containerColor, revealed.toList(), pillLayout
    ) {
        formattedBody?.takeIf { it.isNotBlank() }?.let { html ->
            parseFormattedBody(
                html = html,
                emotePaths = emotePaths,
                conceal = containerColor,
                revealed = revealed.toSet(),
                onReveal = { revealed.add(it) },
                mentionRefs = mentionRefs,
                pillLayout = pillLayout,
                onMentionClick = { userId -> onMentionClick?.invoke(userId) },
            )
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

private fun measureMentionPills(
    refs: Map<String, MentionRef>,
    mentionsRoom: Boolean,
    measurer: TextMeasurer,
    style: TextStyle,
    density: Density,
    content: Color,
): MentionPillLayout? {
    if (refs.isEmpty() && !mentionsRoom) return null

    // A placeholder is sized in sp, which the layouter converts back to px with the same
    // density and font scale the measurement used, so the reserved box matches what the
    // pill then draws.
    val widths = LinkedHashMap<String, TextUnit>(refs.size)
    with(density) {
        refs.forEach { (userId, ref) ->
            val labelPx = measurer.measure(ref.label, style).size.width.toFloat()
            val chrome = if (ref.avatarPath != null) mentionChromeWidth else mentionPaddingHorizontal * 2
            widths[userId] = (labelPx + chrome.toPx()).toSp()
        }

        val lineHeightPx = measurer.measure("Ag", style).size.height.toFloat()
        val heightPx = maxOf(mentionChromeHeight.toPx(), lineHeightPx)
        val roomWidth = if (mentionsRoom) {
            val labelPx = measurer.measure(ROOM_LABEL, style).size.width.toFloat()
            (labelPx + (mentionPaddingHorizontal * 2).toPx()).toSp()
        } else {
            null
        }
        return MentionPillLayout(
            widthByUserId = widths,
            height = heightPx.toSp(),
            background = content.copy(alpha = MENTION_TINT_ALPHA),
            content = content,
            roomWidth = roomWidth,
        )
    }
}
