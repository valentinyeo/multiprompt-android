package dev.multiprompt.companion.sync

import org.bouncycastle.crypto.generators.Argon2BytesGenerator
import org.bouncycastle.crypto.params.Argon2Parameters
import org.json.JSONObject
import java.security.SecureRandom
import java.util.Base64
import javax.crypto.Cipher
import javax.crypto.spec.GCMParameterSpec
import javax.crypto.spec.SecretKeySpec

// Note: this file deliberately uses only java.security + BouncyCastle (already on the
// classpath for sshlib). The entity-record API seals one entity per canonical JSON
// payload; the per-row revision/tombstone/concurrency semantics live server-side (D1)
// and in the sync client, not in the crypto envelope.

/**
 * multiprompt sync protocol v1 — see docs/sync-protocol-v1.md.
 *
 * Key hierarchy: the account passphrase derives a KEK with Argon2id; a random 256-bit
 * vault key is the content key and is wrapped with the KEK (AES-256-GCM, AAD binds the
 * exact KDF parameters); every record is sealed with the vault key (AES-256-GCM, AAD
 * binds the record id). The passphrase is never a content key, and the vault key never
 * travels unsealed.
 *
 * The canonical envelope emitted here is the cross-language contract with the desktop
 * side (zigshell). The committed fixtures in app/src/test/resources/sync/ must reproduce
 * byte-for-byte in every implementation.
 */
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

    class SyncProtocolException(val reason: Reason, message: String) : Exception(message) {
        enum class Reason { MALFORMED, UNSUPPORTED_VERSION, WRONG_PASSPHRASE, TAMPERED }
    }

    /** KDF parameters exactly as stored inside the envelope. */
    data class KdfParams(
        val memoryKiB: Int,
        val iterations: Int,
        val parallelism: Int,
        val salt: ByteArray,
        val version: Int = ARGON2_VERSION_13,
    )

    /**
     * Seals [records] into a canonical v1 envelope with fresh randomness: a random salt,
     * a random vault key, a random wrap IV, and a random IV per record.
     */
    fun seal(passphrase: CharArray, records: Map<String, ByteArray>): ByteArray {
        val random = SecureRandom()
        val salt = randomBytes(random, SALT_BYTES)
        val vaultKey = randomBytes(random, VAULT_KEY_BYTES)
        val wrapIv = randomBytes(random, IV_BYTES)
        val recordIvs = records.keys.associateWith { randomBytes(random, IV_BYTES) }
        return sealWith(passphrase, records, salt, vaultKey, wrapIv, recordIvs)
    }

    /**
     * Deterministic core: fixed salt, vault key and IVs. The fixtures and tests use this
     * to reproduce envelopes byte-for-byte; [seal] is the randomized production entry.
     */
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

    /**
     * Opens a canonical envelope with [passphrase] and returns the decrypted records.
     * A wrong passphrase (or a tampered wrap block) fails the wrap tag as
     * WRONG_PASSPHRASE; a record whose wrap opened but whose own tag fails is TAMPERED.
     */
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

    /**
     * Seals one entity into a canonical entity-record payload for the sync transport
     * (one D1 row per entity; see docs/sync-protocol-v1.md). The AAD binds recordId and
     * entityId, so a row can never be re-keyed under another entity.
     */
    fun sealEntity(
        vaultKey: ByteArray,
        recordId: String,
        entityId: String,
        plaintext: ByteArray,
        iv: ByteArray,
    ): ByteArray {
        require(vaultKey.size == VAULT_KEY_BYTES) { "vault key must be $VAULT_KEY_BYTES bytes" }
        requireRecordId(recordId)
        requireEntityId(entityId)
        require(iv.size == IV_BYTES) { "iv must be $IV_BYTES bytes" }
        val ct = gcmSeal(vaultKey, iv, plaintext, entityAad(recordId, entityId))
        return canonicalEntityRecord(recordId, entityId, iv, ct)
    }

    /** Opens a canonical entity-record payload. Wrong entity id/record id = TAMPERED. */
    fun openEntity(vaultKey: ByteArray, payload: ByteArray): Pair<String, ByteArray> {
        val root = runCatching { JSONObject(String(payload, Charsets.UTF_8)) }
            .getOrElse { malformed("entity record is not valid JSON") }
        if (root.optInt("v", -1) != VERSION) {
            throw SyncProtocolException(SyncProtocolException.Reason.UNSUPPORTED_VERSION, "entity record version ${root.optInt("v", -1)}")
        }
        val recordId = root.optString("recordId")
        val entityId = root.optString("entityId")
        requireRecordId(recordId)
        requireEntityId(entityId)
        val iv = decodeIv(root.optString("iv"))
        val ct = decode(root.optString("ct"))
        val plaintext = runCatching {
            gcmOpen(vaultKey, iv, ct, entityAad(recordId, entityId))
        }.getOrElse {
            throw SyncProtocolException(SyncProtocolException.Reason.TAMPERED, "entity record failed the GCM tag")
        }
        return recordId to plaintext
    }

    // --- key derivation -----------------------------------------------------------------

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
        // Raw UTF-8 password bytes, identical to hash_secret_raw in the fixture generator.
        // The char[] overload of generateBytes is deliberately avoided: its byte conversion
        // is an implementation detail, and this contract is UTF-8 by spec.
        val kek = ByteArray(KEK_BYTES)
        generator.generateBytes(String(passphrase).toByteArray(Charsets.UTF_8), kek)
        return kek
    }

    // --- AES-256-GCM ----------------------------------------------------------------------

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

    private fun entityAad(recordId: String, entityId: String): ByteArray =
        "mp-sync-v1/entity/$recordId/$entityId".toByteArray(Charsets.UTF_8)

    private fun requireEntityId(entityId: String) {
        require(Regex("^[A-Za-z0-9][A-Za-z0-9._-]*$").matches(entityId)) {
            "entity id must match ^[A-Za-z0-9][A-Za-z0-9._-]*$"
        }
    }

    internal fun canonicalEntityRecord(recordId: String, entityId: String, iv: ByteArray, ct: ByteArray): ByteArray =
        buildString {
            append("{\"v\":").append(VERSION)
            append(",\"recordId\":\"").append(recordId)
            append("\",\"entityId\":\"").append(entityId)
            append("\",\"iv\":\"").append(b64(iv))
            append("\",\"ct\":\"").append(b64(ct)).append("\"}")
        }.toByteArray(Charsets.UTF_8)

    // --- canonical JSON ---------------------------------------------------------------------

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

    // --- parsing helpers ----------------------------------------------------------------------

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
