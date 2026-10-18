package chat.stoat.api.fork.routes

import chat.stoat.api.HitRateLimitException
import chat.stoat.api.StoatAPI
import chat.stoat.api.StoatHttp
import chat.stoat.api.StoatJson
import chat.stoat.api.fork.e2ee.E2eeAttachments
import chat.stoat.core.model.data.STOAT_FILES
import chat.stoat.core.model.schemas.AutumnId
import io.ktor.client.HttpClient
import io.ktor.client.plugins.onUpload
import io.ktor.client.request.forms.MultiPartFormDataContent
import io.ktor.client.request.forms.formData
import io.ktor.client.request.get
import io.ktor.client.request.header
import io.ktor.client.request.post
import io.ktor.client.request.setBody
import io.ktor.client.statement.bodyAsText
import io.ktor.client.statement.readRawBytes
import io.ktor.http.Headers
import io.ktor.http.HttpHeaders
import io.ktor.http.HttpStatusCode
import io.ktor.http.isSuccess

/** autumn refused an encrypted upload or download; [status] 413 = too large. */
class EncryptedAttachmentException(val status: Int) : Exception("Encrypted attachment request failed: $status")

/**
 * `POST {autumn}/attachments?encrypted=true` (plan AN-28, BE-14): the ciphertext as `file.bin`,
 * `application/octet-stream` — autumn skips MIME sniffing, metadata and EXIF stripping for it
 * (it could not read them anyway). Returns the file id.
 */
suspend fun uploadEncryptedAttachment(
    ciphertext: ByteArray,
    onProgress: (Long, Long) -> Unit = { _, _ -> },
    http: HttpClient = StoatHttp,
    filesBase: String = STOAT_FILES,
): String {
    val response = http.post("$filesBase/attachments?encrypted=true") {
        setBody(
            MultiPartFormDataContent(
                formData {
                    append(
                        "file",
                        ciphertext,
                        Headers.build {
                            append(HttpHeaders.ContentType, E2eeAttachments.ENCRYPTED_TYPE)
                            append(HttpHeaders.ContentDisposition, "filename=\"${E2eeAttachments.ENCRYPTED_NAME}\"")
                        }
                    )
                }
            )
        )
        header(StoatAPI.TOKEN_HEADER_NAME, StoatAPI.sessionToken)
        onUpload { sent, total -> total?.let { onProgress(sent, it) } }
    }
    if (response.status == HttpStatusCode.TooManyRequests) throw HitRateLimitException()
    if (!response.status.isSuccess()) throw EncryptedAttachmentException(response.status.value)
    return StoatJson.decodeFromString(AutumnId.serializer(), response.bodyAsText()).id
}

/** The ciphertext of an encrypted attachment (decrypted by the caller after the hash check). */
suspend fun downloadEncryptedAttachment(
    id: String,
    http: HttpClient = StoatHttp,
    filesBase: String = STOAT_FILES,
): ByteArray {
    val response = http.get(E2eeAttachments.downloadPath(filesBase, id))
    if (!response.status.isSuccess()) throw EncryptedAttachmentException(response.status.value)
    return response.readRawBytes()
}
