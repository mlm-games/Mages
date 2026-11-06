package org.mlm.mages.ui.components.composer

import org.mlm.mages.MessageEvent
import kotlin.test.Test
import kotlin.test.assertEquals

private fun message(body: String, formattedBody: String?) = MessageEvent(
    itemId = "item",
    eventId = "\$event",
    roomId = "!room:example.org",
    sender = "@alice:example.org",
    body = body,
    formattedBody = formattedBody,
    timestampMs = 0,
)

class ComposerEditDraftTest {

    @Test
    fun restoresBlocksFromAnotherClient() {
        val event = message(
            "Hello bold and italic, gone.\nSecond Bob and link.\nquoted\none\ntwo\nnested\nthree",
            """
            <mx-reply><blockquote>old ping</blockquote></mx-reply>
            <p>Hello <strong>bold</strong> and <em>italic</em>, <del>gone</del>.</p>
            <p>Second <a href="https://matrix.to/#/@bob:example.org">Bob</a> and <a href="https://example.org/a?b=1&amp;c=2">link</a>.</p>
            <blockquote><p>quoted</p></blockquote>
            <ul><li>one</li><li>two<ul><li>nested</li></ul></li></ul>
            <ol start="3"><li>three</li></ol>
            <pre><code class="language-kotlin">val x = 1
</code></pre>
            <hr>
            <p>text with | pipe and * star</p>
            """.trimIndent(),
        )

        assertEquals(
            """
            Hello **bold** and *italic*, ~~gone~~.

            Second [@Bob](https://matrix.to/#/@bob:example.org) and [link](https://example.org/a?b=1&c=2).

            > quoted

            - one
            - two
              - nested

            3. three

            ```kotlin
            val x = 1
            ```

            ---

            text with \| pipe and \* star
            """.trimIndent(),
            event.editComposerText(),
        )
    }

    @Test
    fun roundTripsMentionsEmotesAndSpoilers() {
        val event = message(
            "hi [@Bob](https://matrix.to/#/@bob:example.org) [Spoiler](mxc://example.org/s)",
            """<p>hi <a href="https://matrix.to/#/@bob:example.org">Bob</a> """ +
                """<img src="mxc://example.org/cat" alt="catJAM" title="catJAM" height="32"> """ +
                """<span data-mx-spoiler>secret <strong>bit</strong></span></p>""",
        )

        val draft = event.editComposerText()
        assertEquals(
            """hi [@Bob](https://matrix.to/#/@bob:example.org) ![catJAM](mxc://example.org/cat "catJAM") |secret **bit**|""",
            draft,
        )
        assertEquals(
            """hi <a href="https://matrix.to/#/@bob:example.org">@Bob</a> """ +
                """<img data-mx-emoticon src="mxc://example.org/cat" alt="catJAM" title="catJAM" height="32"> """ +
                """<span data-mx-spoiler>secret <strong>bit</strong></span>""",
            parseComposerMarkdown(draft, emptyList())!!.formattedBody,
        )
    }

    @Test
    fun escapesBlockMarkersAfterAHardBreak() {
        val event = message("a\n- b", "<p>a<br>- b</p>")
        assertEquals("a\\\n\\- b", event.editComposerText())
    }

    @Test
    fun plainBodyDropsDraftEscapes() {
        val parsed = parseComposerMarkdown("IMG\\_2024 \\<3", emptyList())!!
        assertEquals("IMG_2024 <3", composerToPlainBody(parsed))
    }

    @Test
    fun fallsBackToTheBodyWithoutFormattedBody() {
        val event = message("plain **text**", null)
        assertEquals("plain **text**", event.editComposerText())
    }
}
