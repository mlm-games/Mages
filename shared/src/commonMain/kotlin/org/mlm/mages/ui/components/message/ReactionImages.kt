package org.mlm.mages.ui.components.message

import org.mlm.mages.matrix.ImagePackSummary
import org.mlm.mages.matrix.ReactionSummary

/**
 * MSC4027 lets a reaction key be an mxc URI instead of an emoji. The optional
 * `shortcode` cannot be read back, because matrix-sdk-ui's reaction aggregation
 * keeps only a timestamp and send state per sender and discards the annotation
 * content. Looking the key up in the loaded packs recovers the name for the
 * common case where the reaction came from a pack image.
 */
fun reactionShortcodesFrom(packs: List<ImagePackSummary>): Map<String, String> =
    packs
        .flatMap { it.images }
        .associate { it.mxcUrl to it.shortcode }

/** MSC4027's shortcode for an mxc reaction key, when it is a known pack image. */
fun List<ImagePackSummary>.reactionShortcodeFor(key: String): String? {
    if (!key.startsWith("mxc://")) return null
    return flatMap { it.images }
        .firstOrNull { it.mxcUrl == key }
        ?.shortcode
}

/** The mxc reaction keys in [chips] that need a local image to render. */
fun mxcReactionKeys(chips: List<ReactionSummary>): List<String> =
    chips.map { it.key }.filter { it.startsWith("mxc://") }.distinct()
