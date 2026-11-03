package dev.multiprompt.companion.account

import dev.multiprompt.companion.security.SecretStorage
import org.bouncycastle.asn1.pkcs.PrivateKeyInfo
import org.bouncycastle.jce.provider.BouncyCastleProvider
import org.connectbot.sshlib.SshKeys
import org.connectbot.sshlib.SshSigning
import java.security.KeyFactory
import java.security.KeyPair
import java.security.KeyPairGenerator
import java.security.spec.PKCS8EncodedKeySpec
import java.util.Base64

class DeviceKeyStore(private val secrets: SecretStorage) {
    fun ensure(): String {
        val pair = SshKeys.decodePemPrivateKey(privateKey().toString(Charsets.UTF_8))
        val publicKey = SshSigning.encodePublicKey(pair)
        val line = publicKey.algorithmName + " " + Base64.getEncoder().encodeToString(publicKey.publicKeyBlob)
        require(DeviceKeys.validKey(line)) { "Invalid stored device key" }
        return line
    }

    fun privateKey(): ByteArray = synchronized(LOCK) {
        stored() ?: run {
            check(!secrets.contains(SECRET_ID)) { "The stored device key could not be read" }
            SshKeys.encodeOpenSshPrivateKey(generate()).toByteArray(Charsets.UTF_8).also { secrets.put(SECRET_ID, it) }
        }
    }

    private fun generate(): KeyPair {
        val provider = BouncyCastleProvider()
        val pair = KeyPairGenerator.getInstance("Ed25519", provider).generateKeyPair()
        val info = PrivateKeyInfo.getInstance(pair.private.encoded)
        val seedOnly = PrivateKeyInfo(info.privateKeyAlgorithm, info.parsePrivateKey()).encoded
        return KeyPair(pair.public, KeyFactory.getInstance("Ed25519", provider).generatePrivate(PKCS8EncodedKeySpec(seedOnly)))
    }

    private fun stored(): ByteArray? {
        for (attempt in 1..READ_ATTEMPTS) {
            secrets.get(SECRET_ID)?.let { return it }
            if (attempt < READ_ATTEMPTS) Thread.sleep(READ_BACKOFF_MS * attempt)
        }
        return null
    }

    companion object {
        const val SECRET_ID = "device_key"
        private const val READ_ATTEMPTS = 4
        private const val READ_BACKOFF_MS = 40L
        private val LOCK = Any()
    }
}
