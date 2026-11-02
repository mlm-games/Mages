package org.mlm.mages.ui.components.core

import kotlin.test.Test
import kotlin.test.assertEquals

private fun render(html: String): String = parseFormattedBody(html).text.text

class FormattedBodyQuoteTest {
    @Test
    fun marksEveryQuotedParagraph() {
        assertEquals("▏ one\n▏ two", render("<blockquote><p>one</p><p>two</p></blockquote>"))
    }

    @Test
    fun marksNestedQuotesWithOneRailPerLevel() {
        assertEquals(
            "▏ a\n▏▏ b",
            render("<blockquote>a<blockquote>b</blockquote></blockquote>")
        )
    }

    @Test
    fun endsTheQuoteAtTheNextBlock() {
        assertEquals(
            "▏ quoted\ntail",
            render("<blockquote><p>quoted</p></blockquote><p>tail</p>")
        )
    }
}
