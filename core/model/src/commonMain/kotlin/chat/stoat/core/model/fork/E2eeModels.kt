package chat.stoat.core.model.fork

import kotlinx.serialization.SerialName
import kotlinx.serialization.Serializable

/**
 * `encrypted` of a message (envelope v1, contract in stoat-crypto/fixtures/envelope): Megolm
 * ciphertext and the ids needed to find its key. `device_id` is only a hint (C7). Not secret,
 * but never logged either.
 */
@Serializable
data class EncryptedEnvelope(
    val v: Int = 1,
    val algorithm: String = MEGOLM_ALGORITHM,
    @SerialName("session_id") val sessionId: String,
    @SerialName("device_id") val deviceId: String,
    val ciphertext: String,
) {
    override fun toString() = "EncryptedEnvelope(v$v, session=$sessionId, device=$deviceId)"

    companion object {
        const val MEGOLM_ALGORITHM = "m.megolm.v1.aes-sha2"
    }
}

/** `encryption` of a DM or group (BE-02): once set, never removed (AN-39). */
@Serializable
data class ChannelEncryption(
    val algorithm: String = EncryptedEnvelope.MEGOLM_ALGORITHM,
    @SerialName("enabled_at") val enabledAt: String? = null,
    @SerialName("enabled_by") val enabledBy: String? = null,
    @SerialName("rotation_period_ms") val rotationPeriodMs: Long? = null,
    @SerialName("rotation_period_msgs") val rotationPeriodMsgs: Long? = null,
)
