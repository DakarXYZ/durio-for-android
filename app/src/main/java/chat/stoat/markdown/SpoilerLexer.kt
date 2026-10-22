package chat.stoat.markdown

import org.intellij.markdown.IElementType
import org.intellij.markdown.MarkdownTokenTypes
import org.intellij.markdown.flavours.gfm.lexer._GFMLexer
import org.intellij.markdown.lexer.GeneratedLexer

/** Splits `||` out of text so the sequential parser can pair spoiler delimiters. */
class SpoilerLexer(
    private val delegate: GeneratedLexer = _GFMLexer(),
) : GeneratedLexer {
    private data class Token(
        val type: IElementType,
        val start: Int,
        val end: Int,
    )

    private var content: CharSequence = ""
    private var contentEnd: Int = 0
    private var consumedUntil: Int = 0
    private val pending = ArrayDeque<Token>()

    override var tokenStart: Int = 0
        private set

    override var tokenEnd: Int = 0
        private set

    override val state: Int
        get() = delegate.state

    override fun reset(buffer: CharSequence, start: Int, end: Int, initialState: Int) {
        content = buffer
        contentEnd = end
        consumedUntil = start
        pending.clear()
        tokenStart = start
        tokenEnd = start
        delegate.reset(buffer, start, end, initialState)
    }

    override fun advance(): IElementType? {
        while (pending.isEmpty()) {
            val type = delegate.advance() ?: return null
            val start = maxOf(delegate.tokenStart, consumedUntil)
            if (start >= delegate.tokenEnd) continue
            split(type, start, delegate.tokenEnd)
        }

        val token = pending.removeFirst()
        tokenStart = token.start
        tokenEnd = token.end
        return token.type
    }

    private fun split(type: IElementType, start: Int, end: Int) {
        val text = type == MarkdownTokenTypes.TEXT
        var cursor = start
        var segmentStart = start
        while (cursor < end) {
            // durio: `(!)` is three tokens to the GFM lexer, so it is looked for at the start of
            // any token too; text tokens are searched through
            val delimiter = if (text || cursor == start) delimiterAt(cursor) else null
            if (delimiter != null) {
                if (segmentStart < cursor) {
                    pending += Token(type, segmentStart, cursor)
                }
                pending += Token(delimiter.first, cursor, cursor + delimiter.second)
                cursor += delimiter.second
                segmentStart = cursor
            } else if (text) {
                cursor++
            } else {
                cursor = end
            }
        }

        if (segmentStart < end) {
            pending += Token(type, segmentStart, end)
        }
        consumedUntil = maxOf(consumedUntil, cursor, end)
    }

    /** The delimiter starting at [offset] and its length: `||`, or (durio) `(!)` */
    private fun delimiterAt(offset: Int): Pair<IElementType, Int>? {
        if (isEscaped(offset)) return null
        if (offset + 1 < contentEnd && content[offset] == '|' && content[offset + 1] == '|') {
            return SPOILER_DELIMITER_TOKEN_TYPE to 2
        }
        if (
            offset + 2 < contentEnd &&
            content[offset] == '(' &&
            content[offset + 1] == '!' &&
            content[offset + 2] == ')'
        ) {
            return NSFW_DELIMITER_TOKEN_TYPE to 3
        }
        return null
    }

    private fun isEscaped(offset: Int): Boolean {
        var backslashes = 0
        var cursor = offset - 1
        while (cursor >= 0 && content[cursor] == '\\') {
            backslashes++
            cursor--
        }
        return backslashes % 2 != 0
    }
}
