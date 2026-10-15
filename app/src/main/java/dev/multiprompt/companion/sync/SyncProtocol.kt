package dev.multiprompt.companion.sync

import org.bouncycastle.crypto.digests.SHA256Digest
import org.bouncycastle.crypto.generators.Argon2BytesGenerator
import org.bouncycastle.crypto.generators.HKDFBytesGenerator
import org.bouncycastle.crypto.params.Argon2Parameters
import org.bouncycastle.crypto.params.HKDFParameters
import org.json.JSONObject
import java.security.SecureRandom
import java.util.Base64
import javax.crypto.Cipher
import javax.crypto.spec.GCMParameterSpec
import javax.crypto.spec.SecretKeySpec


object SyncProtocol {

    const val VERSION = 1

    private const val KEK_BYTES = 32
    private const val VAULT_KEY_BYTES = 32
    private const val IV_BYTES = 12
    private const val SALT_BYTES = 16
    private const val TAG_BITS = 128
    private const val TAG_BYTES = 16

    private const val KDF_ALG = "argon2id"
    private const val ARGON2_VERSION_13 = 19
    private const val WRAP_ALG = "AES-256-GCM"
    private const val MEMORY_KIB = 65536
    private const val ITERATIONS = 3
    private const val PARALLELISM = 1

    private const val RECOVERY_KDF_ALG = "hkdf-sha256"
    private const val RECOVERY_INFO = "multiprompt-sync-v1 recovery"

    class SyncProtocolException(val reason: Reason, message: String) : Exception(message) {
        enum class Reason { MALFORMED, UNSUPPORTED_VERSION, WRONG_PASSPHRASE, TAMPERED, WRONG_RECOVERY_KEY, BAD_RECOVERY_KEY_FORMAT }
    }

    data class KdfParams(
        val memoryKiB: Int,
        val iterations: Int,
        val parallelism: Int,
        val salt: ByteArray,
        val version: Int = ARGON2_VERSION_13,
    )

    fun seal(passphrase: CharArray, records: Map<String, ByteArray>): ByteArray {
        val random = SecureRandom()
        val salt = randomBytes(random, SALT_BYTES)
        val vaultKey = randomBytes(random, VAULT_KEY_BYTES)
        val wrapIv = randomBytes(random, IV_BYTES)
        val recordIvs = records.keys.associateWith { randomBytes(random, IV_BYTES) }
        return sealWith(passphrase, records, salt, vaultKey, wrapIv, recordIvs)
    }

    fun sealWith(
        passphrase: CharArray,
        records: Map<String, ByteArray>,
        salt: ByteArray,
        vaultKey: ByteArray,
        wrapIv: ByteArray,
        recordIvs: Map<String, ByteArray>,
    ): ByteArray {
        require(salt.size == SALT_BYTES) { "salt must be $SALT_BYTES bytes" }
        require(vaultKey.size == VAULT_KEY_BYTES) { "vault key must be $VAULT_KEY_BYTES bytes" }
        require(wrapIv.size == IV_BYTES) { "wrap IV must be $IV_BYTES bytes" }
        require(records.isNotEmpty()) { "an envelope without records is not a valid vault" }
        records.keys.forEach { requireRecordId(it) }
        require(recordIvs.keys == records.keys) { "every record needs exactly one IV" }
        recordIvs.values.forEach { require(it.size == IV_BYTES) { "record IV must be $IV_BYTES bytes" } }

        val kdf = KdfParams(MEMORY_KIB, ITERATIONS, PARALLELISM, salt)
        val kek = deriveKek(passphrase, kdf)
        val wrapCt = gcmSeal(kek, wrapIv, vaultKey, wrapAad(kdf))
        val sealed = records.mapValues { (id, plaintext) ->
            gcmSeal(vaultKey, recordIvs.getValue(id), plaintext, recordAad(id))
        }
        return canonicalEnvelope(kdf, wrapIv, wrapCt, sealed, recordIvs)
    }

    fun open(passphrase: CharArray, envelope: ByteArray): Map<String, ByteArray> {
        val root = runCatching { JSONObject(String(envelope, Charsets.UTF_8)) }
            .getOrElse { malformed("envelope is not valid JSON") }
        if (root.optInt("v", -1) != VERSION) {
            throw SyncProtocolException(SyncProtocolException.Reason.UNSUPPORTED_VERSION, "envelope version ${root.optInt("v", -1)}")
        }
        val kdf = parseKdf(root.optJSONObject("kdf") ?: malformed("missing kdf"))
        val wrap = root.optJSONObject("wrap") ?: malformed("missing wrap")
        if (wrap.optString("alg") != WRAP_ALG) malformed("unknown wrap alg")
        val wrapIv = decodeIv(wrap.optString("iv"))
        val wrapCt = decode(wrap.optString("ct"))
        if (wrapCt.size != VAULT_KEY_BYTES + TAG_BYTES) {
            malformed("wrap ciphertext is not $VAULT_KEY_BYTES bytes plus tag")
        }

        val kek = deriveKek(passphrase, kdf)
        val vaultKey = runCatching {
            gcmOpen(kek, wrapIv, wrapCt, wrapAad(kdf))
        }.getOrElse {
            throw SyncProtocolException(SyncProtocolException.Reason.WRONG_PASSPHRASE, "wrap tag check failed")
        }

        val recordsJson = root.optJSONObject("records") ?: malformed("missing records")
        val decrypted = linkedMapOf<String, ByteArray>()
        val ids = recordsJson.keys().asSequence().toList()
        for (id in ids) {
            val entry = recordsJson.optJSONObject(id) ?: malformed("record $id is not an object")
            val iv = decodeIv(entry.optString("iv"))
            val ct = decode(entry.optString("ct"))
            decrypted[id] = runCatching {
                gcmOpen(vaultKey, iv, ct, recordAad(id))
            }.getOrElse {
                throw SyncProtocolException(SyncProtocolException.Reason.TAMPERED, "record $id failed the GCM tag")
            }
        }
        return decrypted
    }

    data class SealedVault(val envelope: ByteArray, val vaultKey: ByteArray, val recoveryKey: String)

    fun sealNewWithRecovery(passphrase: CharArray, records: Map<String, ByteArray>): SealedVault {
        val random = SecureRandom()
        val salt = randomBytes(random, SALT_BYTES)
        val vaultKey = randomBytes(random, VAULT_KEY_BYTES)
        val wrapIv = randomBytes(random, IV_BYTES)
        val recordIvs = records.keys.associateWith { randomBytes(random, IV_BYTES) }
        val recoveryKeyBytes = RecoveryKey.generate()
        val recoverySalt = randomBytes(random, SALT_BYTES)
        val recoveryIv = randomBytes(random, IV_BYTES)
        val envelope = sealWithRecovery(passphrase, records, salt, vaultKey, wrapIv, recordIvs, recoveryKeyBytes, recoverySalt, recoveryIv)
        return SealedVault(envelope, vaultKey, RecoveryKey.encode(recoveryKeyBytes))
    }

    fun sealWithRecovery(
        passphrase: CharArray,
        records: Map<String, ByteArray>,
        salt: ByteArray,
        vaultKey: ByteArray,
        wrapIv: ByteArray,
        recordIvs: Map<String, ByteArray>,
        recoveryKeyBytes: ByteArray,
        recoverySalt: ByteArray,
        recoveryIv: ByteArray,
    ): ByteArray {
        require(salt.size == SALT_BYTES) { "salt must be $SALT_BYTES bytes" }
        require(vaultKey.size == VAULT_KEY_BYTES) { "vault key must be $VAULT_KEY_BYTES bytes" }
        require(wrapIv.size == IV_BYTES) { "wrap IV must be $IV_BYTES bytes" }
        require(recoveryKeyBytes.size == RecoveryKey.KEY_BYTES) { "recovery key must be ${RecoveryKey.KEY_BYTES} bytes" }
        require(recoverySalt.size == SALT_BYTES) { "recovery salt must be $SALT_BYTES bytes" }
        require(recoveryIv.size == IV_BYTES) { "recovery IV must be $IV_BYTES bytes" }
        require(records.isNotEmpty()) { "an envelope without records is not a valid vault" }
        records.keys.forEach { requireRecordId(it) }
        require(recordIvs.keys == records.keys) { "every record needs exactly one IV" }
        recordIvs.values.forEach { require(it.size == IV_BYTES) { "record IV must be $IV_BYTES bytes" } }

        val kdf = KdfParams(MEMORY_KIB, ITERATIONS, PARALLELISM, salt)
        val kek = deriveKek(passphrase, kdf)
        val wrapCt = gcmSeal(kek, wrapIv, vaultKey, wrapAad(kdf))
        val sealed = records.mapValues { (id, plaintext) ->
            gcmSeal(vaultKey, recordIvs.getValue(id), plaintext, recordAad(id))
        }
        val recoveryKek = hkdfSha256(recoveryKeyBytes, recoverySalt, RECOVERY_INFO.toByteArray(Charsets.UTF_8), KEK_BYTES)
        val recoveryCt = gcmSeal(recoveryKek, recoveryIv, vaultKey, recoveryAad(recoverySalt))
        return canonicalEnvelopeWithRecovery(kdf, wrapIv, wrapCt, sealed, recordIvs, recoverySalt, recoveryIv, recoveryCt)
    }

    data class RecoveryOpenResult(val vaultKey: ByteArray, val records: Map<String, ByteArray>)

    fun openWithRecoveryKey(recoveryKey: String, envelope: ByteArray): RecoveryOpenResult {
        val recoveryKeyBytes = RecoveryKey.decode(recoveryKey)
        val root = runCatching { JSONObject(String(envelope, Charsets.UTF_8)) }
            .getOrElse { malformed("envelope is not valid JSON") }
        if (root.optInt("v", -1) != VERSION) {
            throw SyncProtocolException(SyncProtocolException.Reason.UNSUPPORTED_VERSION, "envelope version ${root.optInt("v", -1)}")
        }
        val recoveryKdfObj = root.optJSONObject("recoveryKdf") ?: malformed("envelope has no recovery key configured")
        if (recoveryKdfObj.optString("alg") != RECOVERY_KDF_ALG) malformed("unknown recovery kdf alg")
        val recoverySalt = decode(recoveryKdfObj.optString("salt"))
        if (recoverySalt.size != SALT_BYTES) malformed("recovery salt must be $SALT_BYTES bytes")
        val recoveryObj = root.optJSONObject("recovery") ?: malformed("envelope has no recovery block")
        if (recoveryObj.optString("alg") != WRAP_ALG) malformed("unknown recovery alg")
        val recoveryIv = decodeIv(recoveryObj.optString("iv"))
        val recoveryCt = decode(recoveryObj.optString("ct"))
        if (recoveryCt.size != VAULT_KEY_BYTES + TAG_BYTES) {
            malformed("recovery ciphertext is not $VAULT_KEY_BYTES bytes plus tag")
        }

        val recoveryKek = hkdfSha256(recoveryKeyBytes, recoverySalt, RECOVERY_INFO.toByteArray(Charsets.UTF_8), KEK_BYTES)
        val vaultKey = runCatching {
            gcmOpen(recoveryKek, recoveryIv, recoveryCt, recoveryAad(recoverySalt))
        }.getOrElse {
            throw SyncProtocolException(SyncProtocolException.Reason.WRONG_RECOVERY_KEY, "recovery wrap tag check failed")
        }

        val recordsJson = root.optJSONObject("records") ?: malformed("missing records")
        val decrypted = linkedMapOf<String, ByteArray>()
        val ids = recordsJson.keys().asSequence().toList()
        for (id in ids) {
            val entry = recordsJson.optJSONObject(id) ?: malformed("record $id is not an object")
            val iv = decodeIv(entry.optString("iv"))
            val ct = decode(entry.optString("ct"))
            decrypted[id] = runCatching {
                gcmOpen(vaultKey, iv, ct, recordAad(id))
            }.getOrElse {
                throw SyncProtocolException(SyncProtocolException.Reason.TAMPERED, "record $id failed the GCM tag")
            }
        }
        return RecoveryOpenResult(vaultKey, decrypted)
    }

    fun rewrapPassphrase(vaultKey: ByteArray, newPassphrase: CharArray, envelope: ByteArray): ByteArray {
        val random = SecureRandom()
        return rewrapPassphraseWith(vaultKey, newPassphrase, randomBytes(random, SALT_BYTES), randomBytes(random, IV_BYTES), envelope)
    }

    fun rewrapPassphraseWith(
        vaultKey: ByteArray,
        newPassphrase: CharArray,
        newSalt: ByteArray,
        newWrapIv: ByteArray,
        envelope: ByteArray,
    ): ByteArray {
        require(vaultKey.size == VAULT_KEY_BYTES) { "vault key must be $VAULT_KEY_BYTES bytes" }
        require(newSalt.size == SALT_BYTES) { "salt must be $SALT_BYTES bytes" }
        require(newWrapIv.size == IV_BYTES) { "wrap IV must be $IV_BYTES bytes" }
        val root = runCatching { JSONObject(String(envelope, Charsets.UTF_8)) }
            .getOrElse { malformed("envelope is not valid JSON") }
        if (root.optInt("v", -1) != VERSION) {
            throw SyncProtocolException(SyncProtocolException.Reason.UNSUPPORTED_VERSION, "envelope version ${root.optInt("v", -1)}")
        }
        val recordsJson = root.optJSONObject("records") ?: malformed("missing records")
        val recoveryKdfObj = root.optJSONObject("recoveryKdf")
        val recoveryObj = root.optJSONObject("recovery")

        val newKdf = KdfParams(MEMORY_KIB, ITERATIONS, PARALLELISM, newSalt)
        val kek = deriveKek(newPassphrase, newKdf)
        val wrapCt = gcmSeal(kek, newWrapIv, vaultKey, wrapAad(newKdf))

        return buildString {
            append("{\"v\":").append(VERSION)
            append(",\"kdf\":").append(canonicalKdf(newKdf))
            append(",\"wrap\":{\"alg\":\"").append(WRAP_ALG)
            append("\",\"iv\":\"").append(b64(newWrapIv))
            append("\",\"ct\":\"").append(b64(wrapCt)).append("\"}")
            if (recoveryKdfObj != null && recoveryObj != null) {
                append(",\"recoveryKdf\":{\"alg\":\"").append(recoveryKdfObj.optString("alg"))
                append("\",\"info\":\"").append(recoveryKdfObj.optString("info"))
                append("\",\"salt\":\"").append(recoveryKdfObj.optString("salt")).append("\"}")
                append(",\"recovery\":{\"alg\":\"").append(recoveryObj.optString("alg"))
                append("\",\"iv\":\"").append(recoveryObj.optString("iv"))
                append("\",\"ct\":\"").append(recoveryObj.optString("ct")).append("\"}")
            }
            append(",\"records\":{")
            val ids = recordsJson.keys().asSequence().sorted().toList()
            ids.forEachIndexed { index, id ->
                val entry = recordsJson.getJSONObject(id)
                if (index > 0) append(",")
                append("\"").append(id).append("\":{\"iv\":\"").append(entry.getString("iv"))
                append("\",\"ct\":\"").append(entry.getString("ct")).append("\"}")
            }
            append("}}")
        }.toByteArray(Charsets.UTF_8)
    }

    data class EntityMetadata(
        val recordId: String,
        val entityId: String,
        val accountId: String,
        val revision: Long,
        val schemaVersion: Int = VERSION,
    )

    fun sealEntity(
        vaultKey: ByteArray,
        metadata: EntityMetadata,
        plaintext: ByteArray,
        iv: ByteArray,
    ): ByteArray {
        require(vaultKey.size == VAULT_KEY_BYTES) { "vault key must be $VAULT_KEY_BYTES bytes" }
        requireRecordId(metadata.recordId)
        requireEntityId(metadata.entityId)
        require(metadata.accountId.isNotBlank()) { "account id must not be blank" }
        require(metadata.revision >= 0) { "revision must be >= 0" }
        require(iv.size == IV_BYTES) { "iv must be $IV_BYTES bytes" }
        val ct = gcmSeal(vaultKey, iv, plaintext, entityAad(metadata))
        return canonicalEntityRecord(metadata, iv, ct)
    }

    fun openEntity(vaultKey: ByteArray, metadata: EntityMetadata, payload: ByteArray): ByteArray {
        val root = runCatching { JSONObject(String(payload, Charsets.UTF_8)) }
            .getOrElse { malformed("entity record is not valid JSON") }
        if (root.optInt("v", -1) != VERSION) {
            throw SyncProtocolException(SyncProtocolException.Reason.UNSUPPORTED_VERSION, "entity record version ${root.optInt("v", -1)}")
        }
        if (root.optString("recordId") != metadata.recordId) malformed("recordId mismatch")
        if (root.optString("entityId") != metadata.entityId) malformed("entityId mismatch")
        val iv = decodeIv(root.optString("iv"))
        val ct = decode(root.optString("ct"))
        return runCatching {
            gcmOpen(vaultKey, iv, ct, entityAad(metadata))
        }.getOrElse {
            throw SyncProtocolException(SyncProtocolException.Reason.TAMPERED, "entity record failed the GCM tag")
        }
    }


    private fun deriveKek(passphrase: CharArray, kdf: KdfParams): ByteArray {
        val generator = Argon2BytesGenerator()
        generator.init(
            Argon2Parameters.Builder(Argon2Parameters.ARGON2_id)
                .withSalt(kdf.salt)
                .withVersion(kdf.version)
                .withIterations(kdf.iterations)
                .withMemoryAsKB(kdf.memoryKiB)
                .withParallelism(kdf.parallelism)
                .build(),
        )
        val kek = ByteArray(KEK_BYTES)
        generator.generateBytes(String(passphrase).toByteArray(Charsets.UTF_8), kek)
        return kek
    }


    private fun gcmSeal(key: ByteArray, iv: ByteArray, plaintext: ByteArray, aad: ByteArray): ByteArray {
        val cipher = Cipher.getInstance("AES/GCM/NoPadding")
        cipher.init(Cipher.ENCRYPT_MODE, SecretKeySpec(key, "AES"), GCMParameterSpec(TAG_BITS, iv))
        cipher.updateAAD(aad)
        return cipher.doFinal(plaintext)
    }

    private fun gcmOpen(key: ByteArray, iv: ByteArray, ciphertext: ByteArray, aad: ByteArray): ByteArray {
        val cipher = Cipher.getInstance("AES/GCM/NoPadding")
        cipher.init(Cipher.DECRYPT_MODE, SecretKeySpec(key, "AES"), GCMParameterSpec(TAG_BITS, iv))
        cipher.updateAAD(aad)
        return cipher.doFinal(ciphertext)
    }

    private fun wrapAad(kdf: KdfParams): ByteArray =
        "mp-sync-v1/wrap/${canonicalKdf(kdf)}".toByteArray(Charsets.UTF_8)

    private fun recordAad(recordId: String): ByteArray =
        "mp-sync-v1/record/$recordId".toByteArray(Charsets.UTF_8)

    private fun recoveryAad(recoverySalt: ByteArray): ByteArray =
        "mp-sync-v1/recovery/${canonicalRecoveryKdf(recoverySalt)}".toByteArray(Charsets.UTF_8)

    private fun hkdfSha256(ikm: ByteArray, salt: ByteArray, info: ByteArray, length: Int): ByteArray {
        val hkdf = HKDFBytesGenerator(SHA256Digest())
        hkdf.init(HKDFParameters(ikm, salt, info))
        val out = ByteArray(length)
        hkdf.generateBytes(out, 0, length)
        return out
    }

    private fun entityAad(metadata: EntityMetadata): ByteArray =
        "mp-sync-v1/entity/${metadata.recordId}/${metadata.entityId}/${metadata.accountId}/${metadata.revision}/${metadata.schemaVersion}"
            .toByteArray(Charsets.UTF_8)

    private fun requireEntityId(entityId: String) {
        require(Regex("^[A-Za-z0-9][A-Za-z0-9._%*-]*$").matches(entityId)) {
            "entity id must match ^[A-Za-z0-9][A-Za-z0-9._%*-]*$"
        }
    }

    internal fun canonicalEntityRecord(metadata: EntityMetadata, iv: ByteArray, ct: ByteArray): ByteArray =
        buildString {
            append("{\"v\":").append(VERSION)
            append(",\"recordId\":\"").append(metadata.recordId)
            append("\",\"entityId\":\"").append(metadata.entityId)
            append("\",\"iv\":\"").append(b64(iv))
            append("\",\"ct\":\"").append(b64(ct)).append("\"}")
        }.toByteArray(Charsets.UTF_8)


    internal fun canonicalKdf(kdf: KdfParams): String = buildString {
        append("{\"alg\":\"").append(KDF_ALG)
        append("\",\"version\":").append(kdf.version)
        append(",\"memoryKiB\":").append(kdf.memoryKiB)
        append(",\"iterations\":").append(kdf.iterations)
        append(",\"parallelism\":").append(kdf.parallelism)
        append(",\"salt\":\"").append(b64(kdf.salt)).append("\"}")
    }

    private fun canonicalEnvelope(
        kdf: KdfParams,
        wrapIv: ByteArray,
        wrapCt: ByteArray,
        sealedRecords: Map<String, ByteArray>,
        recordIvs: Map<String, ByteArray>,
    ): ByteArray = buildString {
        append("{\"v\":").append(VERSION)
        append(",\"kdf\":").append(canonicalKdf(kdf))
        append(",\"wrap\":{\"alg\":\"").append(WRAP_ALG)
        append("\",\"iv\":\"").append(b64(wrapIv))
        append("\",\"ct\":\"").append(b64(wrapCt))
        append("\"},\"records\":{")
        sealedRecords.keys.sorted().forEachIndexed { index, id ->
            if (index > 0) append(",")
            append("\"").append(id).append("\":{\"iv\":\"").append(b64(recordIvs.getValue(id)))
            append("\",\"ct\":\"").append(b64(sealedRecords.getValue(id))).append("\"}")
        }
        append("}}")
    }.toByteArray(Charsets.UTF_8)

    internal fun canonicalRecoveryKdf(recoverySalt: ByteArray): String = buildString {
        append("{\"alg\":\"").append(RECOVERY_KDF_ALG)
        append("\",\"info\":\"").append(RECOVERY_INFO)
        append("\",\"salt\":\"").append(b64(recoverySalt)).append("\"}")
    }

    private fun canonicalEnvelopeWithRecovery(
        kdf: KdfParams,
        wrapIv: ByteArray,
        wrapCt: ByteArray,
        sealedRecords: Map<String, ByteArray>,
        recordIvs: Map<String, ByteArray>,
        recoverySalt: ByteArray,
        recoveryIv: ByteArray,
        recoveryCt: ByteArray,
    ): ByteArray = buildString {
        append("{\"v\":").append(VERSION)
        append(",\"kdf\":").append(canonicalKdf(kdf))
        append(",\"wrap\":{\"alg\":\"").append(WRAP_ALG)
        append("\",\"iv\":\"").append(b64(wrapIv))
        append("\",\"ct\":\"").append(b64(wrapCt))
        append("\"},\"recoveryKdf\":").append(canonicalRecoveryKdf(recoverySalt))
        append(",\"recovery\":{\"alg\":\"").append(WRAP_ALG)
        append("\",\"iv\":\"").append(b64(recoveryIv))
        append("\",\"ct\":\"").append(b64(recoveryCt))
        append("\"},\"records\":{")
        sealedRecords.keys.sorted().forEachIndexed { index, id ->
            if (index > 0) append(",")
            append("\"").append(id).append("\":{\"iv\":\"").append(b64(recordIvs.getValue(id)))
            append("\",\"ct\":\"").append(b64(sealedRecords.getValue(id))).append("\"}")
        }
        append("}}")
    }.toByteArray(Charsets.UTF_8)


    private fun parseKdf(obj: JSONObject): KdfParams {
        if (obj.optString("alg") != KDF_ALG) malformed("unknown kdf alg")
        val version = obj.optInt("version", -1)
        if (version != ARGON2_VERSION_13) {
            throw SyncProtocolException(SyncProtocolException.Reason.UNSUPPORTED_VERSION, "kdf version $version")
        }
        val memoryKiB = obj.optInt("memoryKiB", -1)
        val iterations = obj.optInt("iterations", -1)
        val parallelism = obj.optInt("parallelism", -1)
        if (memoryKiB < 8 || iterations < 1 || parallelism < 1) malformed("kdf parameters out of range")
        val salt = decode(obj.optString("salt"))
        if (salt.size != SALT_BYTES) malformed("salt must be $SALT_BYTES bytes")
        return KdfParams(memoryKiB, iterations, parallelism, salt, version)
    }

    private fun decode(value: String): ByteArray = runCatching {
        Base64.getDecoder().decode(value)
    }.getOrElse { malformed("invalid base64") }

    private fun decodeIv(value: String): ByteArray {
        val iv = decode(value)
        if (iv.size != IV_BYTES) malformed("iv must be $IV_BYTES bytes")
        return iv
    }

    private fun requireRecordId(id: String) {
        require(Regex("^[a-z][a-zA-Z0-9]*$").matches(id)) { "record id must match ^[a-z][a-zA-Z0-9]*$" }
    }

    private fun malformed(message: String): Nothing =
        throw SyncProtocolException(SyncProtocolException.Reason.MALFORMED, message)

    private fun randomBytes(random: SecureRandom, size: Int): ByteArray =
        ByteArray(size).also { random.nextBytes(it) }

    private fun b64(bytes: ByteArray): String = Base64.getEncoder().encodeToString(bytes)
}
