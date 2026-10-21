package dev.multiprompt.companion.account

import dev.multiprompt.companion.security.SecretStorage
import org.bouncycastle.jce.provider.BouncyCastleProvider
import org.connectbot.sshlib.SshKeys
import org.connectbot.sshlib.SshSigning
import java.security.KeyPairGenerator
import java.util.Base64

class DeviceKeyStore(private val secrets: SecretStorage) {
    fun ensure(): String = synchronized(secrets) {
        val privateKey = secrets.get(SECRET_ID) ?: run {
            val pair = KeyPairGenerator.getInstance("Ed25519", BouncyCastleProvider()).generateKeyPair()
            SshKeys.encodeOpenSshPrivateKey(pair).toByteArray(Charsets.UTF_8).also { secrets.put(SECRET_ID, it) }
        }
        val pair = SshKeys.decodePemPrivateKey(privateKey.toString(Charsets.UTF_8))
        val publicKey = SshSigning.encodePublicKey(pair)
        val line = publicKey.algorithmName + " " + Base64.getEncoder().encodeToString(publicKey.publicKeyBlob)
        require(DeviceKeys.validKey(line)) { "Invalid stored device key" }
        line
    }

    companion object { const val SECRET_ID = "device_key" }
}
