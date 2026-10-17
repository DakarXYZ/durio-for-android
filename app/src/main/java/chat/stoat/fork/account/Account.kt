package chat.stoat.fork.account

import chat.stoat.api.fork.server.canonicalApiUrl
import chat.stoat.core.model.fork.ServerEndpoints
import kotlinx.serialization.Serializable
import java.security.MessageDigest

/**
 * One signed-in account (plan AN-08). The session token is not here: it is kept wrapped with
 * the device's Keystore key in the account's own KV file (see [AccountStorage.readToken]).
 *
 * [ownServer] means the server is a dur.io server (it announces `features.e2ee`); upstream and
 * other servers work without background notifications (O12).
 */
@Serializable
data class Account(
    val key: String,
    val endpoints: ServerEndpoints,
    val userId: String,
    val sessionId: String,
    val displayName: String? = null,
    val avatarUrl: String? = null,
    val addedAt: Long,
    val ownServer: Boolean = false,
) {
    /** Host of the API, shown next to the name ("alice @ duri0.duckdns.org"). */
    val host: String
        get() = endpoints.api.substringAfter("://").substringBefore('/')
}

/**
 * Account key (00-architecture §6, C17): `sha256(canonicalApiUrl + "\n" + userId)`, first 16 hex
 * characters. Names the account's files (`kv_<key>`, `cache_<key>.db`, later `crypto_<key>`),
 * notification tags and push routing; the same rule is used by for-web.
 */
fun accountKey(apiUrl: String, userId: String): String {
    val digest = MessageDigest.getInstance("SHA-256")
        .digest("${canonicalApiUrl(apiUrl)}\n$userId".toByteArray(Charsets.UTF_8))
    return digest.joinToString("") { "%02x".format(it) }.take(16)
}
