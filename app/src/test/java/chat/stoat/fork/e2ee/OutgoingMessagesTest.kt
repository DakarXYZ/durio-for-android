package chat.stoat.fork.e2ee

import chat.stoat.api.fork.e2ee.E2eeException
import chat.stoat.api.fork.e2ee.E2eeNoRecipientDevicesException
import chat.stoat.api.fork.e2ee.E2eeSendPreparationException
import chat.stoat.api.fork.routes.EncryptedSendException
import org.junit.Assert.assertEquals
import org.junit.Test
import java.io.IOException

// What the user is told when an encrypted message is not sent (plan AN-26)
class OutgoingMessagesTest {
    @Test
    fun failuresMapToWhatTheUserCanDo() {
        val cases = mapOf(
            E2eeNoRecipientDevicesException("u") to E2eeSendError.NoRecipientDevices,
            E2eeException.PayloadTooLarge() to E2eeSendError.TooLong,
            EncryptedSendException(422, "PayloadTooLarge") to E2eeSendError.TooLong,
            E2eeUnavailableException() to E2eeSendError.Unavailable,
            E2eeException.StoreLocked() to E2eeSendError.Unavailable,
            E2eeSendPreparationException(retryable = true) to E2eeSendError.Network,
            E2eeSendPreparationException(retryable = false) to E2eeSendError.Refused,
            EncryptedSendException(400, "EncryptedChannelViolation") to E2eeSendError.Refused,
            IOException("offline") to E2eeSendError.Network,
        )
        for ((e, expected) in cases) assertEquals(e::class.simpleName, expected, OutgoingMessages.reason(e))
    }
}
