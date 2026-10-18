package chat.stoat.api.fork.e2ee

/**
 * A key export file (CR-21): the Matrix "Megolm session export", encrypted with the user's
 * passphrase. [data] is saved as text where the user picks; ciphertext, but never logged.
 */
class E2eeKeyExport(val data: String, val sessions: Int) {
    override fun toString() = "E2eeKeyExport(sessions=$sessions)"
}

/**
 * What an import of a key file did (CR-21): messages waiting for [roomKeys] are decrypted again;
 * imported keys show "sender not confirmed", like restored ones.
 */
class E2eeKeyImport(
    /** Sessions in the file. */
    val total: Int,
    /** New or better keys stored. */
    val imported: Int,
    /** Sessions of non-Stoat rooms (another Matrix client's export), left out. */
    val skipped: Int,
    val roomKeys: List<E2eeRoomKey>,
) {
    override fun toString() = "E2eeKeyImport(total=$total, imported=$imported, skipped=$skipped)"
}
