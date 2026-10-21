package chat.stoat.api.routes.microservices.january

import chat.stoat.core.model.data.STOAT_FILES
import chat.stoat.core.model.data.STOAT_PROXY
import io.ktor.http.encodeURLParameter

fun asJanuaryProxyUrl(url: String): String {
    return januaryProxyUrl(url, proxyBase = STOAT_PROXY, filesBase = STOAT_FILES)
}

/**
 * durio: January refuses to proxy the server's own domain, and files on the server's own file
 * server (autumn, e.g. images of archived Discord previews) need no proxy, so they stay direct.
 */
fun januaryProxyUrl(url: String, proxyBase: String, filesBase: String): String {
    val files = filesBase.trimEnd('/')
    if (files.isNotEmpty() && (url == files || url.startsWith("$files/"))) return url
    return "$proxyBase/proxy?url=${url.encodeURLParameter(spaceToPlus = true)}"
}
