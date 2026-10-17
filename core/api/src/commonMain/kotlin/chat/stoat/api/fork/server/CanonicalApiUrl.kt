package chat.stoat.api.fork.server

/**
 * Canonical form of an API URL (plan AN-06, C11, C17). It is part of the account key
 * (`sha256(api + "\n" + userId)`) and of push routing: pushd sends it as `instance` after the
 * same normalisation (`canonical_api_url` in stoatchat `pushd/.../routing.rs`). Both sides must
 * give identical output, so this is a plain string transformation sharing the server's tests:
 *
 * - surrounding whitespace trimmed, missing scheme means `https`;
 * - scheme and host lowercased, path case kept;
 * - default port dropped (`:443` for https, `:80` for http);
 * - trailing slashes removed.
 */
fun canonicalApiUrl(url: String): String {
    val trimmed = url.trim()
    val schemeEnd = trimmed.indexOf("://")
    val scheme = if (schemeEnd >= 0) trimmed.substring(0, schemeEnd).asciiLowercase() else "https"
    val rest = if (schemeEnd >= 0) trimmed.substring(schemeEnd + 3) else trimmed

    val pathStart = rest.indexOf('/').let { if (it < 0) rest.length else it }
    var authority = rest.substring(0, pathStart).asciiLowercase()
    val path = rest.substring(pathStart)

    val defaultPort = when (scheme) {
        "https" -> ":443"
        "http" -> ":80"
        else -> null
    }
    if (defaultPort != null && authority.endsWith(defaultPort)) {
        authority = authority.removeSuffix(defaultPort)
    }

    return "$scheme://$authority${path.trimEnd('/')}"
}

// Like Rust's to_ascii_lowercase: non-ASCII letters stay as they are on both sides
private fun String.asciiLowercase(): String =
    map { if (it in 'A'..'Z') it + ('a' - 'A') else it }.joinToString("")
