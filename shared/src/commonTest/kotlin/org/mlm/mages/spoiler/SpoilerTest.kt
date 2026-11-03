package org.mlm.mages.spoiler

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.LinkAnnotation
import org.mlm.mages.ui.components.composer.ComposerMarkdown
import org.mlm.mages.ui.components.composer.composerToPlainBody
import org.mlm.mages.ui.components.composer.parseComposerMarkdown
import org.mlm.mages.ui.components.core.parseFormattedBody

private fun send(text: String, placeholder: String? = null): Pair<ComposerMarkdown, String> {
    val parsed = parseComposerMarkdown(text, emptyList())!!
    return parsed to composerToPlainBody(parsed, placeholder)
}

class SpoilerSendTest {
    @Test
    fun wrapsTheTextInASpan() {
        val (parsed, _) = send("see |secret| now")
        assertEquals("see <span data-mx-spoiler>secret</span> now", parsed.formattedBody)
    }

    @Test
    fun supportsShorthand() {
        val (parsed, _) = send("see ||secret|| now")
        assertEquals("see <span data-mx-spoiler>secret</span> now", parsed.formattedBody)
    }

    @Test
    fun leavesUnpairedPipesAlone() {
        val (parsed, _) = send("a | b and 5 | 6")
        assertEquals("a | b and 5 | 6", parsed.formattedBody)
    }

    @Test
    fun leavesPipesInCodeAlone() {
        val (parsed, _) = send("`|spoiler|`")
        assertEquals("<code>|spoiler|</code>", parsed.formattedBody)
    }

    @Test
    fun formatsInsideASpoiler() {
        val (parsed, _) = send("|a **bold** claim|")
        assertEquals(
            "<span data-mx-spoiler>a <strong>bold</strong> claim</span>",
            parsed.formattedBody
        )
    }

    @Test
    fun redactsTheBodyToThePlaceholder() {
        val (_, body) = send("see |secret| now", "mxc://example.org/spoiler")
        assertEquals("see [Spoiler](mxc://example.org/spoiler) now", body)
    }

    @Test
    fun redactsToALabelWithoutAPlaceholder() {
        val (_, body) = send("see |secret| now")
        assertEquals("see [Spoiler] now", body)
        assertTrue("secret" !in body, "the hidden text must not reach body")
    }

    @Test
    fun redactsEverySpoiler() {
        val (_, body) = send("|one| and |two|", "mxc://e/s")
        assertEquals("[Spoiler](mxc://e/s) and [Spoiler](mxc://e/s)", body)
    }

    @Test
    fun redactsAnUnclosedSpoiler() {
        val (parsed, body) = send("|secret")
        assertEquals("|secret", parsed.formattedBody)
        assertEquals("|secret", body)
    }

    @Test
    fun keepsMentionsWorkingAlongsideSpoilers() {
        val (parsed, body) = send("|hi| [Alice](https://matrix.to/#/@alice:example.org)")
        assertEquals("[Spoiler] @Alice", body)
        assertTrue("matrix.to" in parsed.formattedBody!!)
    }
}

class SpoilerRenderTest {
    @Test
    fun paintsOverTheHiddenText() {
        val parsed = parseFormattedBody(
            "see <span data-mx-spoiler>secret</span> now",
            conceal = Color.Black
        )
        assertEquals("see secret now", parsed.text.text)
        assertEquals(1, parsed.text.spanStyles.size)
    }

    @Test
    fun aRevealedSpoilerIsNotConcealed() {
        val parsed = parseFormattedBody(
            "<span data-mx-spoiler>secret</span>",
            conceal = Color.Black,
            revealed = setOf(0)
        )
        assertEquals("secret", parsed.text.text)
        assertTrue(parsed.text.spanStyles.isEmpty())
    }

    @Test
    fun revealsOnlyTheSpoilerThatWasTapped() {
        val parsed = parseFormattedBody(
            "<span data-mx-spoiler>a</span> and <span data-mx-spoiler>b</span>",
            conceal = Color.Black,
            revealed = setOf(1)
        )
        assertEquals("a and b", parsed.text.text)
        assertEquals(1, parsed.text.spanStyles.size)
    }

    @Test
    fun tappingAConcealedSpoilerReportsItsIndex() {
        val asked = mutableListOf<Int>()
        val parsed = parseFormattedBody(
            "<span data-mx-spoiler>a</span><span data-mx-spoiler>b</span>",
            conceal = Color.Black,
            onReveal = { asked += it }
        )
        val links = parsed.text.getLinkAnnotations(0, parsed.text.length)
        links.forEach { range ->
            val link = range.item as LinkAnnotation.Clickable
            link.linkInteractionListener!!.onClick(link)
        }
        assertEquals(listOf(0, 1), asked)
    }

    @Test
    fun nothingIsConcealedWithoutAColour() {
        val parsed = parseFormattedBody("<span data-mx-spoiler>secret</span>")
        assertEquals("secret", parsed.text.text)
        assertTrue(parsed.text.getLinkAnnotations(0, parsed.text.length).isEmpty())
    }

    @Test
    fun anEmptySpoilerIsHarmless() {
        val parsed = parseFormattedBody("a<span data-mx-spoiler></span>b", conceal = Color.Black)
        assertEquals("ab", parsed.text.text)
    }

    @Test
    fun aLinkInsideASpoilerIsNotClickable() {
        val parsed = parseFormattedBody(
            "<span data-mx-spoiler><a href=\"https://example.org\">x</a></span>",
            conceal = Color.Black
        )
        assertEquals("x", parsed.text.text)
        val links = parsed.text.getLinkAnnotations(0, parsed.text.length)
        assertTrue(links.none { it.item is LinkAnnotation.Url })
    }
}
