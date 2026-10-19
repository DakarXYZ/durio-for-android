package chat.stoat.api.fork.routes

import chat.stoat.api.StoatHttp
import chat.stoat.api.fork.e2ee.E2eeAttachments
import chat.stoat.core.model.data.STOAT_FILES
import io.ktor.client.HttpClient
import io.ktor.client.request.prepareGet
import io.ktor.client.statement.bodyAsChannel
import io.ktor.http.isSuccess
import io.ktor.utils.io.jvm.javaio.copyTo
import java.io.File

/**
 * The ciphertext of an encrypted attachment streamed into [to] (media, "save", "share"): a
 * 100 MB file never sits in memory whole. Checked against its hash by the caller afterwards.
 */
suspend fun downloadEncryptedAttachment(
    id: String,
    to: File,
    http: HttpClient = StoatHttp,
    filesBase: String = STOAT_FILES,
) {
    http.prepareGet(E2eeAttachments.downloadPath(filesBase, id)).execute { response ->
        if (!response.status.isSuccess()) throw EncryptedAttachmentException(response.status.value)
        to.outputStream().use { response.bodyAsChannel().copyTo(it) }
    }
}
