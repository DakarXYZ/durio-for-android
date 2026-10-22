package chat.stoat.markdown

import org.intellij.markdown.ast.ASTNode
import org.intellij.markdown.parser.MarkdownParser
import org.junit.Assert.assertEquals
import org.junit.Test

class SpoilerParsingTest {
    private fun spoilers(text: String): List<Pair<String, String>> {
        val tree = MarkdownParser(StoatMarkdownFlavour(text)).buildMarkdownTreeFromString(text)
        val found = mutableListOf<Pair<String, String>>()
        fun walk(node: ASTNode) {
            if (node.type == SPOILER_ELEMENT_TYPE || node.type == NSFW_ELEMENT_TYPE) {
                found += node.type.name to text.substring(node.startOffset, node.endOffset)
            }
            node.children.forEach(::walk)
        }
        walk(tree)
        return found
    }

    @Test
    fun spoilersAndNsfw() {
        assertEquals(listOf("SPOILER" to "||the end||"), spoilers("so ||the end|| is sad"))
        assertEquals(listOf("NSFW" to "(!)a picture(!)"), spoilers("look (!)a picture(!) here"))
        assertEquals(
            listOf("SPOILER" to "||one||", "NSFW" to "(!)two(!)"),
            spoilers("||one|| and (!)two(!)"),
        )
    }

    @Test
    fun unpairedStaysText() {
        assertEquals(emptyList<Pair<String, String>>(), spoilers("just (!) one and (text)!"))
    }
}
