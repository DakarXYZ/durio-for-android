package chat.stoat.api.fork.e2ee

import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.buildJsonObject
import kotlin.test.Test
import kotlin.test.assertFalse

// Review O-5: a value of a facade type that carries secrets may end up in a string or a log line
// by mistake; its toString() must not print the secret (check-crypto-hygiene.sh keeps them
// plain classes, this checks what they print).
class SecretToStringTest {
    private val secret = "S3CR3T-do-not-print"

    private fun assertHidden(value: Any) {
        val printed = value.toString() + "$value"
        assertFalse(printed.contains(secret), "${value::class.simpleName} prints a secret")
    }

    @Test
    fun secretsStayOutOfToString() {
        val file = buildJsonObject { put("k", JsonPrimitive(secret)) }
        val attachment = E2eePayloadAttachment(
            id = "a1", name = secret, contentType = "image/png", size = 1, file = file,
            thumbnail = E2eePayloadThumbnail("t1", 1, 1, file),
        )
        listOf(
            E2eeDecryptResult.Decrypted(
                payloadJson = secret, senderUser = "u", senderDevice = "d", verification = E2eeVerification.Verified,
                sessionId = secret, messageIndex = 0, isReplay = false, staleEdit = false, editMismatch = false,
                replyMismatch = false, isClean = true,
            ),
            E2eeRecoverySetup(secret, E2eeRequest("r", E2eeRequestKind.BackupVersionCreate, "POST", "/p", secret)),
            E2eeRecoverySecrets(secret, secret, secret, secret),
            E2eeKeyExport(secret, 3),
            E2eeKeyImport(1, 1, 0, listOf(E2eeRoomKey("c", secret))),
            E2eeEncryptedBlob(secret.encodeToByteArray(), secret),
            E2eeFileKey(secret.encodeToByteArray(), secret.encodeToByteArray()),
            E2eePayload(content = secret, attachments = listOf(attachment), clientNonce = "n", ts = 1),
            attachment,
            attachment.thumbnail!!,
            E2eeAttachmentKeys("m1", attachment),
            E2eeIncomingMessage("m1", "c1", "u1", secret, emptyList(), 0),
            E2eeRequest("r", E2eeRequestKind.ToDevice, "PUT", "/e2ee/to_device/x", secret),
            E2eePushDecryptor.Outcome.Text(secret, 0),
        ).forEach(::assertHidden)
    }
}
