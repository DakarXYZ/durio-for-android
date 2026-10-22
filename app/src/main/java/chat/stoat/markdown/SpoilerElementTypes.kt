package chat.stoat.markdown

import org.intellij.markdown.IElementType

val SPOILER_DELIMITER_TOKEN_TYPE: IElementType = IElementType("SPOILER_DELIMITER")
val SPOILER_ELEMENT_TYPE: IElementType = IElementType("SPOILER")

// durio: (!)NSFW(!) spoilers, labelled NSFW while closed
val NSFW_DELIMITER_TOKEN_TYPE: IElementType = IElementType("NSFW_DELIMITER")
val NSFW_ELEMENT_TYPE: IElementType = IElementType("NSFW")
