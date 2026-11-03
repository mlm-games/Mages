package org.mlm.mages.ui.components.core

import kotlin.test.Test
import kotlin.test.assertEquals

class FormattedBodyBlockTest {
    @Test
    fun aHorizontalRuleSeparatesTheBlocks() {
        assertEquals(
            "a\n\nb",
            parseFormattedBody("<p>a</p><hr><p>b</p>").text.text
        )
    }

    @Test
    fun divsStartTheirOwnLines() {
        assertEquals(
            "one\ntwo",
            parseFormattedBody("<div>one</div><div>two</div>").text.text
        )
    }
}
