package dev.multiprompt.companion.sync

import android.content.Context
import android.security.keystore.KeyGenParameterSpec
import android.security.keystore.KeyProperties
import android.util.Base64
import java.security.KeyStore
import javax.crypto.Cipher
import javax.crypto.KeyGenerator
import javax.crypto.SecretKey
import javax.crypto.spec.GCMParameterSpec

/**
 * Stores the unwrapped account vault key on this device.
 *
 * After the passphrase unwraps the vault key (docs/sync-protocol-v1.md), it is persisted
 * here so the passphrase does not have to be re-entered on every launch: the vault key is
 * sealed with a dedicated Android Keystore AES key (hardware-backed, strongbox when
 * available) and stored base64 in prefs — the same pattern as [dev.multiprompt.companion.security.SecretStore].
 * The vault key itself never persists in plaintext and never leaves the device unsealed.
 * The later Windows adapter (zigshell) stores it under DPAPI instead.
 */
class VaultKeyStore(context: Context) {

    private val preferences = context.getSharedPreferences("sync_vault", Context.MODE_PRIVATE)

    /** Returns the stored vault key, or null when no account vault exists on this device. */
    fun get(): ByteArray? {
        val encoded = preferences.getString(VAULT_KEY, null) ?: return null
        return runCatching {
            val packed = Base64.decode(encoded, Base64.NO_WRAP)
            require(packed.size > IV_BYTES)
            val cipher = Cipher.getInstance(TRANSFORMATION)
            cipher.init(
                Cipher.DECRYPT_MODE,
                keystoreKey(),
                GCMParameterSpec(128, packed.copyOfRange(0, IV_BYTES)),
            )
            cipher.doFinal(packed.copyOfRange(IV_BYTES, packed.size))
        }.getOrNull()
    }

    /** Persists the unwrapped vault key, sealed by the Android Keystore key. */
    fun put(vaultKey: ByteArray) {
        require(vaultKey.size == VAULT_KEY_BYTES) { "vault key must be 32 bytes" }
        val cipher = Cipher.getInstance(TRANSFORMATION)
        cipher.init(Cipher.ENCRYPT_MODE, keystoreKey())
        val ciphertext = cipher.doFinal(vaultKey)
        val encoded = Base64.encodeToString(cipher.iv + ciphertext, Base64.NO_WRAP)
        preferences.edit().putString(VAULT_KEY, encoded).commit()
    }

    /** Forgets the vault key (sign-out, passphrase reset, corruption). */
    fun clear() {
        preferences.edit().remove(VAULT_KEY).apply()
    }

    private fun keystoreKey(): SecretKey {
        val keyStore = KeyStore.getInstance(KEYSTORE).apply { load(null) }
        (keyStore.getKey(KEY_ALIAS, null) as? SecretKey)?.let { return it }
        return KeyGenerator.getInstance(KeyProperties.KEY_ALGORITHM_AES, KEYSTORE).run {
            init(
                KeyGenParameterSpec.Builder(
                    KEY_ALIAS,
                    KeyProperties.PURPOSE_ENCRYPT or KeyProperties.PURPOSE_DECRYPT,
                )
                    .setBlockModes(KeyProperties.BLOCK_MODE_GCM)
                    .setEncryptionPaddings(KeyProperties.ENCRYPTION_PADDING_NONE)
                    .setKeySize(256)
                    .build(),
            )
            generateKey()
        }
    }

    private companion object {
        const val KEYSTORE = "AndroidKeyStore"
        const val KEY_ALIAS = "multiprompt_sync_vault_key_v1"
        const val VAULT_KEY = "vault_key"
        const val TRANSFORMATION = "AES/GCM/NoPadding"
        const val IV_BYTES = 12
        const val VAULT_KEY_BYTES = 32
    }
}
