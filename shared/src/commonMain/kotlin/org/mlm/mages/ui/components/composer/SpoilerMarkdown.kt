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

internal const val PIPE_CHAR = '|'

/**
 * Splits `|` out of the `TEXT` runs the CommonMark lexer produces.
 *
 * The delimiter machinery works on token boundaries, and CommonMark lexes a
 * run of prose as one `TEXT` token with no delimiter tokens of its own, so the
 * pipes have to be carved out before a [DelimiterParser] can pair them up. Only
 * `TEXT` is touched, which leaves code spans (`CODE_SPAN`), links and raw HTML
 * alone, so a `|` in backticks stays literal.
 */
internal class SpoilerPipeLexer(private val base: GeneratedLexer) : GeneratedLexer {
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
            while (pos < end) {
                val pipe = text.indexOf(PIPE_CHAR, pos)
                if (pipe < 0 || pipe >= end) {
                    queue.addLast(Token(type, pos, end))
                    break
                }
                if (pipe > pos) queue.addLast(Token(type, pos, pipe))
                queue.addLast(Token(SpoilerSyntax.PIPE, pipe, pipe + 1))
                pos = pipe + 1
            }
        }
    }

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
 * Pairs `|spoiler|` delimiters, the way the CommonMark flavour pairs `*` and
 * `_` for emphasis.
 */
internal class SpoilerDelimiterParser : DelimiterParser() {
    override fun scan(
        tokens: TokensCache,
        iterator: TokensCache.Iterator,
        delimiters: MutableList<Info>
    ): Int {
        if (iterator.type != SpoilerSyntax.PIPE) return 0

        // A run of pipes is one delimiter, so `||` can wrap a spoiler the same
        // way `|spoiler|` does.
        var runLength = 1
        var last = iterator
        while (last.rawLookup(1) == SpoilerSyntax.PIPE) {
            last = last.advance()
            runLength += 1
        }

        // A delimiter has to hug its content, so whitespace on either side rules
        // it out. That is what keeps `a | b` literal while still allowing a
        // spoiler to sit at the very start or end of the message.
        val canOpen = !isWhitespace(last, 1) && last.charLookup(1) != PIPE_CHAR
        val canClose = !isWhitespace(iterator, -1) && iterator.charLookup(-1) != PIPE_CHAR

        for (offset in 0 until runLength) {
            delimiters.add(
                Info(
                    tokenType = SpoilerSyntax.PIPE,
                    position = iterator.index + offset,
                    length = 0,
                    canOpen = canOpen,
                    canClose = canClose,
                    marker = PIPE_CHAR
                )
            )
        }
        return runLength
    }

    override fun process(
        tokens: TokensCache,
        iterator: TokensCache.Iterator,
        delimiters: MutableList<Info>,
        result: SequentialParser.ParsingResultBuilder
    ) {
        // Each pipe in a run gets balanced against the one opposite it, so `||`
        // arrives here as two nested pairs. Widening adjacent same-marker pairs
        // back into the run they came from is what stops the shorthand from
        // producing nested spans.
        var index = delimiters.size - 1
        while (index >= 0) {
            val first = delimiters[index]
            if (first.tokenType != SpoilerSyntax.PIPE || first.closerIndex == -1) {
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
                    type = SpoilerSyntax.SPOILER
                )
            )
            index = openerIndex - 1
        }
    }
}

/**
 * CommonMark plus spoilers.
 *
 * The parser sequence is spelled out rather than inherited because
 * `EmphasisLikeParser` reports no ranges for further processing, so it has to
 * stay last in the sequence and spoilers therefore have to share its one pass
 * with emphasis instead of running as a separate parser.
 */
internal class SpoilerFlavour : CommonMarkFlavourDescriptor() {
    override fun createInlinesLexer(): MarkdownLexer =
        MarkdownLexer(SpoilerPipeLexer(_MarkdownLexer()))

    override val sequentialParserManager: SequentialParserManager =
        object : SequentialParserManager() {
            override fun getParserSequence(): List<SequentialParser> = listOf(
                AutolinkParser(listOf(MarkdownTokenTypes.AUTOLINK)),
                BacktickParser(),
                ImageParser(),
                InlineLinkParser(),
                ReferenceLinkParser(),
                EmphasisLikeParser(EmphStrongDelimiterParser(), SpoilerDelimiterParser())
            )
        }

    override fun createHtmlGeneratingProviders(
        linkMap: LinkMap,
        baseURI: URI?
    ): Map<IElementType, GeneratingProvider> =
        super.createHtmlGeneratingProviders(linkMap, baseURI) + mapOf(
            // The trimming provider strips the pipes off the ends, and being an
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
            }
        )
}
