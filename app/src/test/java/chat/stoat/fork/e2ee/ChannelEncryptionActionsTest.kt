package chat.stoat.fork.e2ee

import chat.stoat.api.fork.routes.EnableEncryptionException
import org.junit.Assert.assertEquals
import org.junit.Test

// Answers of PUT /channels/{id}/encryption (BE-11) → what the user is told (plan AN-27)
class ChannelEncryptionActionsTest {
    @Test
    fun serverAnswersMapToResults() {
        val cases = mapOf(
            EnableEncryptionException(409, "AlreadyEncrypted") to EnableEncryptionResult.AlreadyEnabled,
            EnableEncryptionException(403, "MissingPermission") to EnableEncryptionResult.NoPermission,
            EnableEncryptionException(404, null) to EnableEncryptionResult.Unavailable,
            EnableEncryptionException(400, "NotEncryptable") to EnableEncryptionResult.NotEncryptable,
            EnableEncryptionException(400, "FailedValidation") to EnableEncryptionResult.Failed,
            EnableEncryptionException(500, null) to EnableEncryptionResult.Failed,
        )
        for ((e, expected) in cases) assertEquals("${e.status} ${e.type}", expected, ChannelEncryptionActions.result(e))
    }
}
