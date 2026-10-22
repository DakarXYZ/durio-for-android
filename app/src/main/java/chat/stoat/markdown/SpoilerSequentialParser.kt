package chat.stoat.markdown

import org.intellij.markdown.IElementType
import org.intellij.markdown.parser.sequentialparsers.RangesListBuilder
import org.intellij.markdown.parser.sequentialparsers.SequentialParser
import org.intellij.markdown.parser.sequentialparsers.TokensCache

/**
 * Pairs [delimiter] tokens into [element] nodes: `||spoiler||`, and (durio) `(!)NSFW(!)`.
 */
class SpoilerSequentialParser(
    private val delimiter: IElementType = SPOILER_DELIMITER_TOKEN_TYPE,
    private val element: IElementType = SPOILER_ELEMENT_TYPE,
) : SequentialParser {
    override fun parse(
        tokens: TokensCache,
        rangesToGlue: List<IntRange>,
    ): SequentialParser.ParsingResult {
        val result = SequentialParser.ParsingResultBuilder()
        val delegateIndices = RangesListBuilder()
        var iterator: TokensCache.Iterator = tokens.RangesListIterator(rangesToGlue)

        while (iterator.type != null) {
            if (iterator.type == delimiter) {
                val opening = iterator
                var closing = iterator.advance()

                while (closing.type != null) {
                    if (
                        closing.type == delimiter &&
                        closing.start >= opening.end
                    ) {
                        var inner = opening.advance()
                        while (inner.type != null && inner.index < closing.index) {
                            delegateIndices.put(inner.index)
                            inner = inner.advance()
                        }
                        result.withNode(
                            SequentialParser.Node(
                                opening.index..closing.index + 1,
                                element,
                            )
                        )
                        iterator = closing.advance()
                        break
                    }
                    closing = closing.advance()
                }

                if (iterator != opening) continue
            }

            delegateIndices.put(iterator.index)
            iterator = iterator.advance()
        }

        return result.withFurtherProcessing(delegateIndices.get())
    }
}
