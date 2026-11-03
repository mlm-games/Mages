package org.mlm.mages.spoiler

import kotlin.test.Test
import kotlin.test.assertEquals
import org.mlm.mages.ui.components.composer.parseComposerMarkdown

private fun send(text: String) = parseComposerMarkdown(text, emptyList())!!

class StrikeSendTest {
    @Test
    fun wrapsTheTextInDel() {
        assertEquals("<del>gone</del>", send("~~gone~~").formattedBody)
    }

    @Test
    fun leavesUnpairedTildesAlone() {
        assertEquals("about ~5 and ~~open", send("about ~5 and ~~open").formattedBody)
    }

    @Test
    fun leavesTildesInCodeAlone() {
        assertEquals("<code>~~gone~~</code>", send("`~~gone~~`").formattedBody)
    }

    @Test
    fun honoursEscapedTildes() {
        assertEquals("~~gone~~", send("\\~\\~gone\\~\\~").formattedBody)
    }
}
