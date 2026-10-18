package chat.stoat.api.fork.e2ee

import chat.stoat.api.StoatJson
import kotlinx.serialization.EncodeDefault
import kotlinx.serialization.ExperimentalSerializationApi
import kotlinx.serialization.SerialName
import kotlinx.serialization.Serializable
import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.JsonObject

/**
 * Payload v1 — what is inside the Megolm ciphertext (C7, C8, CR-19; schema in
 * `stoat-crypto-proto/src/payload.rs`, fixtures in `stoat-crypto/fixtures/payload`). The core
 * adds `type`/`room_id` around it and the padding; the client fills the rest.
 *
 * Plaintext: no `toString` of these classes may reach a log ([toString] is overridden anyway).
 * Unknown fields are ignored (a newer client may add some, CR-23).
 */
@OptIn(ExperimentalSerializationApi::class)
@Serializable
class E2eePayload(
    @EncodeDefault val v: Int = VERSION,
    val content: String? = null,
    val attachments: List<E2eePayloadAttachment> = emptyList(),
    /** = the `nonce` of the send request (CR-19). */
    @SerialName("client_nonce") val clientNonce: String,
    /** Id of the edited message, only in edits. */
    @SerialName("edit_of") val editOf: String? = null,
    /** 0 for the original, +1 for every edit (CR-19). */
    @EncodeDefault @SerialName("edit_seq") val editSeq: Long = 0,
    /** Replied message ids, the same as the open `replies` of the request. */
    @SerialName("in_reply_to") val inReplyTo: List<String> = emptyList(),
    /** Sender clock, ms (display only). */
    @EncodeDefault val ts: Long,
) {
    override fun toString() = "E2eePayload(v$v, ${attachments.size} attachments, edit_seq=$editSeq)"

    /** autumn ids for the open `attachments` of the request: files **and** thumbnails (C9). */
    fun fileIds(): List<String> = attachments.flatMap { listOfNotNull(it.id, it.thumbnail?.id) }

    fun toJson(): String = StoatJson.encodeToString(serializer(), this)

    companion object {
        const val VERSION = 1

        /** @throws IllegalArgumentException on a malformed payload (the core checked the version) */
        fun parse(json: String): E2eePayload = StoatJson.decodeFromString(serializer(), json)
    }
}

/** An encrypted attachment (C8): the open message only names the autumn blob. */
@OptIn(ExperimentalSerializationApi::class)
@Serializable
class E2eePayloadAttachment(
    /** autumn id of the encrypted blob. */
    val id: String,
    val name: String,
    @SerialName("content_type") val contentType: String,
    /** Plaintext size in bytes. */
    val size: Long,
    /** Stoat file metadata (`{"type":"Image","width":…,"height":…}`), as is. */
    @EncodeDefault val metadata: JsonElement = JsonObject(emptyMap()),
    val spoiler: Boolean = false,
    /** `EncryptedFile` v2 (key, IV, hash): secret, kept only in memory. */
    val file: JsonObject,
    val thumbnail: E2eePayloadThumbnail? = null,
) {
    override fun toString() = "E2eePayloadAttachment($id)"
}

@Serializable
class E2eePayloadThumbnail(
    val id: String,
    val width: Int,
    val height: Int,
    val file: JsonObject,
) {
    override fun toString() = "E2eePayloadThumbnail($id)"
}
