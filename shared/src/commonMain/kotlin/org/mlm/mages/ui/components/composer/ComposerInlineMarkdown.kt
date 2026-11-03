package org.mlm.mages.ui.components.composer

import org.intellij.markdown.IElementType
import org.intellij.markdown.MarkdownElementType
import org.intellij.markdown.MarkdownTokenTypes
import org.intellij.markdown.ast.ASTNode
import org.intellij.markdown.flavours.commonmark.CommonMarkFlavourDescriptor
import org.intellij.markdown.html.EqualDelimiterTrimmingInlineTagProvider
import org.intellij.markdown.html.GeneratingProvider
import org.intellij.markdown.html.HtmlGenerator
import org.intellij.markdown.html.URI
import org.intellij.markdown.lexer.GeneratedLexer
import org.intellij.markdown.lexer.MarkdownLexer
import org.intellij.markdown.lexer._MarkdownLexer
import org.intellij.markdown.parser.LinkMap
import org.intellij.markdown.parser.sequentialparsers.DelimiterParser
import org.intellij.markdown.parser.sequentialparsers.EmphasisLikeParser
import org.intellij.markdown.parser.sequentialparsers.SequentialParser
import org.intellij.markdown.parser.sequentialparsers.SequentialParserManager
import org.intellij.markdown.parser.sequentialparsers.TokensCache
import org.intellij.markdown.parser.sequentialparsers.impl.AutolinkParser
import org.intellij.markdown.parser.sequentialparsers.impl.BacktickParser
import org.intellij.markdown.parser.sequentialparsers.impl.EmphStrongDelimiterParser
import org.intellij.markdown.parser.sequentialparsers.impl.ImageParser
import org.intellij.markdown.parser.sequentialparsers.impl.InlineLinkParser
import org.intellij.markdown.parser.sequentialparsers.impl.ReferenceLinkParser

/**
 * Matrix's spoiler syntax in the composer.
 *
 * The spec only defines how a spoiler is *sent* (`<span data-mx-spoiler>` in
 * `formatted_body`); it says nothing about how a user types one. This accepts
 * the de facto Element spelling, `|spoiler|text|spoiler|`, plus its `||text||`
 * shorthand, because that is what people already type when moving between
 * clients.
 */
internal object SpoilerSyntax {
    /** The attribute the spec marks a spoiler span with. */
    const val SPOILER_ATTR = "data-mx-spoiler"

    /** The `|` itself, split out of `TEXT` so a delimiter parser can see it. */
    val PIPE: IElementType = MarkdownElementType("MAGES_SPOILER_PIPE", true)

    /** A matched pair of delimiters, wrapping whatever was between them. */
    val SPOILER: IElementType = MarkdownElementType("MAGES_SPOILER")
}

/**
 * GFM's strikethrough, which every other Matrix client already sends as
 * `<del>`. Only the two-tilde spelling is a delimiter: a lone `~` stays prose,
 * because `~` is ordinary punctuation in chat text.
 */
internal object StrikeSyntax {
    /** The `~` itself, split out of `TEXT` so a delimiter parser can see it. */
    val TILDE: IElementType = MarkdownElementType("MAGES_STRIKE_TILDE", true)

    /** A matched pair of delimiters, wrapping whatever was between them. */
    val STRIKE: IElementType = MarkdownElementType("MAGES_STRIKE")
}

internal const val PIPE_CHAR = '|'
internal const val TILDE_CHAR = '~'

/** The number of tildes a strikethrough is spelled with. */
private const val STRIKE_DELIMITERS = 2

/** The characters the composer splits out of `TEXT` for [PairedMarkerDelimiterParser]. */
private val INLINE_MARKERS = charArrayOf(PIPE_CHAR, TILDE_CHAR)

/**
 * Splits the inline markers out of the `TEXT` runs the CommonMark lexer
 * produces.
 *
 * The delimiter machinery works on token boundaries, and CommonMark lexes a
 * run of prose as one `TEXT` token with no delimiter tokens of its own, so the
 * markers have to be carved out before a [DelimiterParser] can pair them up.
 * Only `TEXT` is touched, which leaves code spans (`CODE_SPAN`), links and raw
 * HTML alone, so a `|` or `~` in backticks stays literal. A marker escaped with
 * a backslash stays in the `TEXT` run, which is what keeps `\~` and `\|`
 * literal.
 */
internal class ComposerMarkerLexer(private val base: GeneratedLexer) : GeneratedLexer {
    private var text: CharSequence = ""
    private val queue = ArrayDeque<Token>()
    private var exhausted = false

    override var tokenStart: Int = 0
        private set

    override var tokenEnd: Int = 0
        private set

    override val state: Int get() = base.state

    override fun reset(buffer: CharSequence, start: Int, end: Int, initialState: Int) {
        text = buffer
        queue.clear()
        pending = null
        exhausted = false
        base.reset(buffer, start, end, initialState)
        tokenStart = start
        tokenEnd = start
    }

    override fun advance(): IElementType? {
        if (queue.isEmpty() && !exhausted) pull()
        val token = queue.removeFirstOrNull() ?: return null
        tokenStart = token.start
        tokenEnd = token.end
        return token.type
    }

    private var pending: Token? = null

    private fun pull() {
        while (queue.isEmpty() && !exhausted) {
            val type = base.advance()
            if (type == null) {
                exhausted = true
                flushPending()
                return
            }
            val start = base.tokenStart
            val end = base.tokenEnd
            if (type != MarkdownTokenTypes.TEXT) {
                flushPending()
                queue.addLast(Token(type, start, end))
                continue
            }
            // The base lexer leaves a backslash and the pipe it
            // escapes as separate runs, because `|` is missing.
            if (end - start == 1 && text[start] == PIPE_CHAR &&
                pending != null && pending!!.end == start &&
                pending!!.end - pending!!.start == 1 &&
                text[pending!!.start] == '\\'
            ) {
                val from = pending!!.start
                pending = null
                queue.addLast(Token(MarkdownTokenTypes.TEXT, from, end))
                continue
            }
            flushPending()
            if (end - start == 1 && text[start] == '\\') {
                pending = Token(type, start, end)
                continue
            }
            var pos = start
            var search = start
            while (search < end) {
                val marker = text.indexOfAny(INLINE_MARKERS, search)
                if (marker < 0 || marker >= end) break
                if (isEscaped(marker)) {
                    search = marker + 1
                    continue
                }
                if (marker > pos) queue.addLast(Token(type, pos, marker))
                queue.addLast(Token(markerType(text[marker]), marker, marker + 1))
                pos = marker + 1
                search = pos
            }
            if (pos < end) queue.addLast(Token(type, pos, end))
        }
    }

    private fun isEscaped(index: Int): Boolean {
        var backslashes = 0
        var i = index - 1
        while (i >= 0 && text[i] == '\\') {
            backslashes++
            i--
        }
        return backslashes % 2 == 1
    }

    private fun markerType(char: Char): IElementType =
        if (char == PIPE_CHAR) SpoilerSyntax.PIPE else StrikeSyntax.TILDE

    private fun flushPending() {
        val held = pending
        if (held != null) {
            pending = null
            queue.addLast(held)
        }
    }

    private class Token(val type: IElementType, val start: Int, val end: Int)
}

/**
 * Pairs a run of one marker character into a node, the way the CommonMark
 * flavour pairs `*` and `_` for emphasis.
 *
 * [runLength] pins the exact number of markers a delimiter is spelled with;
 * null accepts any run. A run of the wrong length is left alone, so it falls
 * through to the HTML as literal text.
 */
internal class PairedMarkerDelimiterParser(
    private val tokenType: IElementType,
    private val nodeType: IElementType,
    private val marker: Char,
    private val runLength: Int? = null,
) : DelimiterParser() {
    override fun scan(
        tokens: TokensCache,
        iterator: TokensCache.Iterator,
        delimiters: MutableList<Info>
    ): Int {
        if (iterator.type != tokenType) return 0

        // A run of markers is one delimiter, so `||` can wrap a spoiler the
        // same way `|spoiler|` does.
        var length = 1
        var last = iterator
        while (last.rawLookup(1) == tokenType) {
            last = last.advance()
            length += 1
        }
        if (runLength != null && length != runLength) return length

        // A delimiter has to hug its content, so whitespace on either side rules
        // it out. That is what keeps `a | b` literal while still allowing a
        // spoiler to sit at the very start or end of the message.
        val canOpen = !isWhitespace(last, 1) && last.charLookup(1) != marker
        val canClose = !isWhitespace(iterator, -1) && iterator.charLookup(-1) != marker

        for (offset in 0 until length) {
            delimiters.add(
                Info(
                    tokenType = tokenType,
                    position = iterator.index + offset,
                    length = 0,
                    canOpen = canOpen,
                    canClose = canClose,
                    marker = marker
                )
            )
        }
        return length
    }

    override fun process(
        tokens: TokensCache,
        iterator: TokensCache.Iterator,
        delimiters: MutableList<Info>,
        result: SequentialParser.ParsingResultBuilder
    ) {
        // Each marker in a run gets balanced against the one opposite it, so
        // `||` arrives here as two nested pairs. Widening adjacent same-marker
        // pairs back into the run they came from is what stops the shorthand
        // from producing nested spans.
        var index = delimiters.size - 1
        while (index >= 0) {
            val first = delimiters[index]
            if (first.tokenType != tokenType || first.closerIndex == -1) {
                index -= 1
                continue
            }

            var openerIndex = index
            var closerIndex = first.closerIndex
            while (EmphStrongDelimiterParser.areAdjacentSameMarkers(delimiters, openerIndex, closerIndex)) {
                openerIndex -= 1
                closerIndex += 1
            }

            result.withNode(
                SequentialParser.Node(
                    range = delimiters[openerIndex].position..delimiters[closerIndex].position + 1,
                    type = nodeType
                )
            )
            index = openerIndex - 1
        }
    }
}

/**
 * CommonMark plus spoilers and strikethrough.
 *
 * The parser sequence is spelled out rather than inherited because
 * `EmphasisLikeParser` reports no ranges for further processing, so it has to
 * stay last in the sequence and every delimiter therefore has to share its one
 * pass with emphasis instead of running as a separate parser.
 */
internal class SpoilerFlavour : CommonMarkFlavourDescriptor() {
    override fun createInlinesLexer(): MarkdownLexer =
        MarkdownLexer(ComposerMarkerLexer(_MarkdownLexer()))

    override val sequentialParserManager: SequentialParserManager =
        object : SequentialParserManager() {
            override fun getParserSequence(): List<SequentialParser> = listOf(
                AutolinkParser(listOf(MarkdownTokenTypes.AUTOLINK)),
                BacktickParser(),
                ImageParser(),
                InlineLinkParser(),
                ReferenceLinkParser(),
                EmphasisLikeParser(
                    EmphStrongDelimiterParser(),
                    PairedMarkerDelimiterParser(
                        tokenType = SpoilerSyntax.PIPE,
                        nodeType = SpoilerSyntax.SPOILER,
                        marker = PIPE_CHAR
                    ),
                    PairedMarkerDelimiterParser(
                        tokenType = StrikeSyntax.TILDE,
                        nodeType = StrikeSyntax.STRIKE,
                        marker = TILDE_CHAR,
                        runLength = STRIKE_DELIMITERS
                    )
                )
            )
        }

    override fun createHtmlGeneratingProviders(
        linkMap: LinkMap,
        baseURI: URI?
    ): Map<IElementType, GeneratingProvider> =
        super.createHtmlGeneratingProviders(linkMap, baseURI) + mapOf(
            // The trimming provider strips the markers off the ends, and being an
            // inline holder is what routes the leaf text inside through
            // `visitLeaf`; a bare holder would drop it.
            SpoilerSyntax.SPOILER to object : EqualDelimiterTrimmingInlineTagProvider(
                "span",
                SpoilerSyntax.PIPE
            ) {
                override fun openTag(
                    visitor: HtmlGenerator.HtmlGeneratingVisitor,
                    text: String,
                    node: ASTNode
                ) {
                    visitor.consumeTagOpen(node, tagName, SpoilerSyntax.SPOILER_ATTR)
                }
            },
            StrikeSyntax.STRIKE to EqualDelimiterTrimmingInlineTagProvider(
                "del",
                StrikeSyntax.TILDE
            )
        )
}
