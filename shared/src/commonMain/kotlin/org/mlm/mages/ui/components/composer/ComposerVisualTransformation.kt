package org.mlm.mages.ui.components.composer

import androidx.compose.ui.text.AnnotatedString
import androidx.compose.ui.text.input.OffsetMapping
import androidx.compose.ui.text.input.TransformedText
import androidx.compose.ui.text.input.VisualTransformation

/**
 * Emote markdown. The quoted title inside the target is the shortcode, and only
 * [EmoteSuggestion.markdown] writes that shape, so an image anyone else types
 * is left as it is. Separate from [EMOTE_MARKDOWN] because the send path
 * collapses every image to its alt text, while this one has to read the
 * shortcode back out and needs the target to be an mxc URI.
 */
private val EMOTE_WITH_SHORTCODE =
    Regex("""!\[(?:\\.|[^\]\\])*]\((mxc://[^ )]+)\s+"([^"]*)"\)""")

class ComposerVisualTransformation(private val emoteUris: Set<String>) : VisualTransformation {

    override fun filter(text: AnnotatedString): TransformedText {
        val source = text.text
        val replacements = replacementsIn(source)
        if (replacements.isEmpty()) return TransformedText(text, OffsetMapping.Identity)

        val out = AnnotatedString.Builder()
        val spans = mutableListOf<Span>()
        var cursor = 0
        for ((range, replacement) in replacements) {
            if (range.first < cursor) continue
            out.append(text.subSequence(cursor, range.first))
            val transformedStart = out.length
            out.append(replacement)
            spans += Span(range.first, range.last + 1, transformedStart, out.length)
            cursor = range.last + 1
        }
        out.append(text.subSequence(cursor, source.length))

        return TransformedText(out.toAnnotatedString(), OffsetBy(spans))
    }

    private fun replacementsIn(source: String): List<Pair<IntRange, String>> {
        val found = mutableListOf<Pair<IntRange, String>>()

        for (match in MENTION_MARKDOWN.findAll(source)) {
            val label = unescapeMarkdown(match.groupValues[1])
            found += Pair(match.range, if (label.startsWith("@")) label else "@$label")
        }

        for (match in EMOTE_WITH_SHORTCODE.findAll(source)) {
            val mxcUri = match.groupValues[1]
            val shortcode = match.groupValues[2]
            if (mxcUri in emoteUris && shortcode.isNotBlank()) {
                found += Pair(match.range, ":$shortcode:")
            }
        }

        return found.sortedBy { it.first.first }
    }
}

private class Span(
    val originalStart: Int,
    val originalEnd: Int,
    val transformedStart: Int,
    val transformedEnd: Int,
) {
    val originalLength get() = originalEnd - originalStart
    val transformedLength get() = transformedEnd - transformedStart
}

private class OffsetBy(private val spans: List<Span>) : OffsetMapping {

    override fun originalToTransformed(offset: Int): Int {
        var delta = 0
        for (span in spans) {
            if (offset >= span.originalEnd) delta += span.transformedLength - span.originalLength
            else if (offset > span.originalStart) return span.transformedStart
            else break
        }
        return offset + delta
    }

    override fun transformedToOriginal(offset: Int): Int {
        var delta = 0
        for (span in spans) {
            if (offset >= span.transformedEnd) delta += span.originalLength - span.transformedLength
            else if (offset > span.transformedStart) return (offset + delta).coerceAtMost(span.originalEnd)
            else break
        }
        return offset + delta
    }
}
