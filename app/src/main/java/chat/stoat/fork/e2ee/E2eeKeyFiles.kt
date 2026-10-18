package chat.stoat.fork.e2ee

import chat.stoat.api.fork.e2ee.E2eeCore
import chat.stoat.api.fork.e2ee.E2eeException
import chat.stoat.api.fork.e2ee.E2eeKeyExport
import chat.stoat.api.fork.e2ee.E2eeKeyImport
import chat.stoat.api.fork.e2ee.E2eeMessages
import chat.stoat.api.fork.e2ee.E2eeRoomKey
import kotlinx.coroutines.CancellationException
import logcat.LogPriority
import logcat.logcat
import java.io.ByteArrayOutputStream
import java.io.InputStream
import java.time.LocalDate

/** How hard a key file passphrase is to guess, roughly (CR-21). */
enum class PassphraseStrength { TooShort, Weak, Fair, Strong }

/** Why exporting or importing a key file failed, for the UI (CR-21). */
enum class KeyFileFailure {
    /** Export: the file would pass the core's 64 MiB limit — export per channel. */
    TooManySessions,

    /** Import: wrong passphrase, or the file was modified (one MAC, the core cannot tell). */
    WrongPassphrase,

    /** Import: not a key file, or a cut-off one. */
    InvalidFile,

    /** Import: bigger than the core accepts; not even read in full. */
    TooLarge,

    /** The chosen file could not be read or written. */
    FileAccess,

    /** No E2EE device right now. */
    NoDevice,
    Other,
}

/**
 * Key export to a file and import from one (plan CR-21, contract in stoat-crypto lib.rs "Key
 * export to a file"): the Matrix "Megolm session export", encrypted with a passphrase. For a
 * reinstall without the server backup, and for moving keys by hand.
 *
 * Passphrases arrive as UTF-8 [ByteArray]s and are wiped here whatever happens (best effort: the
 * text field's String cannot be wiped). The core runs PBKDF2 off the calling thread (about a
 * second); file I/O is the caller's, on [kotlinx.coroutines.Dispatchers.IO].
 */
object E2eeKeyFiles {
    /** The core refuses bigger files (`key_export::MAX_FILE_LEN`, review O-8). */
    const val MAX_FILE_BYTES = 64 * 1024 * 1024
    const val MIN_PASSPHRASE = 10

    /**
     * A rough estimate, not a cracker model: length first (several words are best), then
     * variety. Export needs at least [PassphraseStrength.Fair].
     */
    fun strength(passphrase: CharSequence): PassphraseStrength {
        val length = passphrase.codePoints().count()
        if (length < MIN_PASSPHRASE) return PassphraseStrength.TooShort
        val distinct = passphrase.codePoints().distinct().count()
        val classes = listOf<(Int) -> Boolean>(
            Character::isLowerCase,
            Character::isUpperCase,
            Character::isDigit,
            { !Character.isLetterOrDigit(it) },
        ).count { test -> passphrase.codePoints().anyMatch { test(it) } }
        return when {
            distinct < 5 || (length < 14 && classes < 2) -> PassphraseStrength.Weak
            length >= 16 || (length >= 12 && classes >= 3) -> PassphraseStrength.Strong
            else -> PassphraseStrength.Fair
        }
    }

    fun canExport(passphrase: CharSequence, repeated: CharSequence): Boolean =
        passphrase.toString() == repeated.toString() && strength(passphrase) >= PassphraseStrength.Fair

    /** Name offered in the save dialog. */
    fun fileName(today: LocalDate = LocalDate.now()) = "dur.io-keys-$today.txt"

    sealed interface Exported {
        class Ready(val export: E2eeKeyExport) : Exported
        data class Failed(val reason: KeyFileFailure) : Exported
    }

    /** Keys of [channelIds] (all if empty) into a file; [passphrase] is wiped. */
    suspend fun export(core: E2eeCore, passphrase: ByteArray, channelIds: List<String>): Exported = try {
        Exported.Ready(core.exportRoomKeys(passphrase, channelIds))
    } catch (e: CancellationException) {
        throw e
    } catch (e: Exception) {
        logcat(LogPriority.WARN) { "Keys not exported: ${e::class.simpleName}" }
        Exported.Failed(
            when (e) {
                is E2eeException.InvalidKeyFile -> KeyFileFailure.TooManySessions
                else -> KeyFileFailure.Other
            }
        )
    } finally {
        passphrase.fill(0)
    }

    sealed interface Imported {
        class Done(val result: E2eeKeyImport) : Imported
        data class Failed(val reason: KeyFileFailure) : Imported
    }

    /**
     * Imports a key file read with [readCapped]; [passphrase] is wiped. Messages waiting for the
     * imported keys are decrypted again ([onRoomKeys]).
     */
    suspend fun import(
        core: E2eeCore,
        file: ByteArray,
        passphrase: ByteArray,
        onRoomKeys: suspend (List<E2eeRoomKey>) -> Unit = E2eeMessages::onRoomKeys,
    ): Imported = try {
        val result = core.importRoomKeys(file.decodeToString(), passphrase)
        if (result.roomKeys.isNotEmpty()) onRoomKeys(result.roomKeys)
        Imported.Done(result)
    } catch (e: CancellationException) {
        throw e
    } catch (e: Exception) {
        logcat(LogPriority.WARN) { "Keys not imported: ${e::class.simpleName}" }
        Imported.Failed(
            when (e) {
                is E2eeException.WrongPassphrase -> KeyFileFailure.WrongPassphrase
                is E2eeException.InvalidKeyFile -> KeyFileFailure.InvalidFile
                else -> KeyFileFailure.Other
            }
        )
    } finally {
        passphrase.fill(0)
    }

    /**
     * Reads [input] up to [max] bytes; null if there is more (the file is refused before it all
     * sits in memory, review O-8).
     */
    fun readCapped(input: InputStream, max: Int = MAX_FILE_BYTES): ByteArray? {
        val out = ByteArrayOutputStream()
        val buffer = ByteArray(64 * 1024)
        var total = 0L
        while (true) {
            val read = input.read(buffer)
            if (read < 0) break
            total += read
            if (total > max) return null
            out.write(buffer, 0, read)
        }
        return out.toByteArray()
    }
}
