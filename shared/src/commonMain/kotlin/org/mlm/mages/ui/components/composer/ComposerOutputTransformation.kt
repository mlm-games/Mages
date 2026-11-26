package org.mlm.mages.ui.components.composer

import androidx.compose.foundation.text.input.OutputTransformation
import androidx.compose.foundation.text.input.TextFieldBuffer

private val EMOTE_WITH_SHORTCODE =
    Regex("""!\[(?:\\.|[^\]\\])*]\(mxc://[^ )]+\s+"([^"]*)"\)""")

class ComposerOutputTransformation : OutputTransformation {

    override fun TextFieldBuffer.transformOutput() {
        for ((range, replacement) in composerReplacements(originalText.toString()).asReversed()) {
            replace(range.first, range.last + 1, replacement)
        }
    }
}

internal fun composerReplacements(source: String): List<Pair<IntRange, String>> {
    val found = mutableListOf<Pair<IntRange, String>>()

    for (match in MENTION_MARKDOWN.findAll(source)) {
        val label = unescapeMarkdown(match.groupValues[1])
        found += Pair(match.range, if (label.startsWith("@")) label else "@$label")
    }

    for (match in EMOTE_WITH_SHORTCODE.findAll(source)) {
        val shortcode = match.groupValues[1]
        if (shortcode.isNotBlank()) found += Pair(match.range, ":$shortcode:")
    }

    return found.sortedBy { it.first.first }
}
