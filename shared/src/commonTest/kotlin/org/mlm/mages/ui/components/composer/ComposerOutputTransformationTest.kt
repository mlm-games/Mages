package org.mlm.mages.ui.components.composer

import kotlin.test.Test
import kotlin.test.assertEquals

class ComposerOutputTransformationTest {
    @Test
    fun replacesMentionsAndEmotesForDisplay() {
        val mention = "[Alice](https://matrix.to/#/@alice:example.org)"
        val emote = """![wave](mxc://example.org/abc "wave")"""
        val source = "hi $mention and $emote"

        val replacements = composerReplacements(source)
        val display = buildString {
            var cursor = 0
            for ((range, replacement) in replacements) {
                append(source, cursor, range.first)
                append(replacement)
                cursor = range.last + 1
            }
            append(source, cursor, source.length)
        }

        assertEquals(2, replacements.size)
        assertEquals("hi @Alice and :wave:", display)
    }

    @Test
    fun displaysRoomMentionAsPlainAtRoom() {
        val source = "Hey $ROOM_MENTION_LINK everyone"
        val display = buildString {
            var cursor = 0
            for ((range, replacement) in composerReplacements(source)) {
                append(source, cursor, range.first)
                append(replacement)
                cursor = range.last + 1
            }
            append(source, cursor, source.length)
        }

        assertEquals("Hey @room everyone", display)
    }

    @Test
    fun leavesPlainTextAlone() {
        assertEquals(emptyList(), composerReplacements("just text @Alice"))
    }
}
