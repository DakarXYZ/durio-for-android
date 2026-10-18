package chat.stoat.fork.keystore

import android.os.Build
import android.security.keystore.KeyGenParameterSpec
import android.security.keystore.KeyProperties
import android.security.keystore.StrongBoxUnavailableException
import java.security.KeyStore
import javax.crypto.KeyGenerator
import javax.crypto.SecretKey

/**
 * The device's wrap key in Android Keystore (plan AN-22): one per install, AES-256-GCM, usable
 * without user authentication and while the device is locked, so push handling can unwrap
 * tokens in the background. StrongBox when the device has it.
 */
object AndroidKeystoreWrapKey {
    private val key = AndroidKeystoreAesKey("stoat.wrap.v1")

    fun get(): SecretKey = key.get()

    fun wrapper(): KeyWrapper = AesGcmKeyWrapper(::get)
}

/**
 * An AES-256-GCM key under [alias] in Android Keystore, created on first use and kept for the
 * process. Its own class so instrumented tests (AN-34) use an alias of their own.
 */
class AndroidKeystoreAesKey(private val alias: String) {
    @Volatile
    private var cached: SecretKey? = null

    @Synchronized
    fun get(): SecretKey {
        cached?.let { return it }
        val keyStore = KeyStore.getInstance(KEYSTORE).apply { load(null) }
        val key = (keyStore.getKey(alias, null) as? SecretKey) ?: generate()
        cached = key
        return key
    }

    private fun generate(): SecretKey {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.P) {
            try {
                return generate(strongBox = true)
            } catch (_: StrongBoxUnavailableException) {
                // most devices; fall through to the TEE
            }
        }
        return generate(strongBox = false)
    }

    private fun generate(strongBox: Boolean): SecretKey {
        val spec = KeyGenParameterSpec.Builder(
            alias,
            KeyProperties.PURPOSE_ENCRYPT or KeyProperties.PURPOSE_DECRYPT
        )
            .setBlockModes(KeyProperties.BLOCK_MODE_GCM)
            .setEncryptionPaddings(KeyProperties.ENCRYPTION_PADDING_NONE)
            .setKeySize(256)
            .setUserAuthenticationRequired(false)
            .setRandomizedEncryptionRequired(true)
            .apply {
                if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.P) {
                    setUnlockedDeviceRequired(false)
                    if (strongBox) setIsStrongBoxBacked(true)
                }
            }
            .build()

        return KeyGenerator.getInstance(KeyProperties.KEY_ALGORITHM_AES, KEYSTORE)
            .apply { init(spec) }
            .generateKey()
    }

    private companion object {
        const val KEYSTORE = "AndroidKeyStore"
    }
}
