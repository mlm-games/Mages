package org.mlm.mages.ui.components.composer

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertTrue

class ComposerRoomMentionTest {
    @Test
    fun roomMentionMarkerBecomesPlainAtRoom() {
        val parsed = parseComposerMarkdown("Hey $ROOM_MENTION_LINK everyone", emptyList())!!

        assertEquals("Hey @room everyone", composerToPlainBody(parsed))
        assertTrue(
            parsed.formattedBody!!.contains("<a href=\"$ROOM_MENTION_MARKER\">@room</a>"),
            parsed.formattedBody,
        )
    }

    @Test
    fun memberNamedRoomDoesNotBecomeRoomMention() {
        val parsed = parseComposerMarkdown(
            "[@room](https://matrix.to/#/@room:example.org)",
            emptyList(),
        )!!

        assertEquals("@room", composerToPlainBody(parsed))
        assertFalse(parsed.formattedBody!!.contains(ROOM_MENTION_MARKER))
    }
}
