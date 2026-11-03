package dev.multiprompt.companion.account

import kotlinx.coroutines.CancellationException
import org.json.JSONObject
import java.io.File
import java.nio.ByteBuffer
import java.security.MessageDigest
import java.util.Base64
import java.util.Locale

object BotPhotoKey {
    fun hex(host: Int, session: String): String {
        val digest = MessageDigest.getInstance("SHA-256")
        digest.update("ZigShell bot avatar v1\u0000remote\u0000".toByteArray(Charsets.US_ASCII))
        digest.update(host.toByte())
        digest.update(session.lowercase(Locale.ROOT).toByteArray(Charsets.UTF_8))
        return digest.digest().joinToString("") { "%02x".format(it) }
    }
}

class BotPhotoCache(private val dir: File) {
    fun file(tab: AccountTab): File? = tab.photo?.let { File(dir, name(BotPhotoKey.hex(tab.host, tab.session), it)) }?.takeIf { it.isFile }

    suspend fun sync(tabs: List<AccountTab>, fetch: suspend (String) -> ByteArray?): Boolean {
        val wanted = tabs.mapNotNull { tab -> tab.photo?.let { name(BotPhotoKey.hex(tab.host, tab.session), it) } }.toSet()
        var changed = false
        dir.listFiles()?.filter { it.name !in wanted }?.forEach { if (it.delete()) changed = true }
        dir.mkdirs()
        for (name in wanted) {
            val target = File(dir, name)
            if (target.isFile) continue
            val bytes = try {
                fetch(name.substringBefore('-'))
            } catch (cancelled: CancellationException) {
                throw cancelled
            } catch (_: Exception) {
                null
            }
            if (bytes == null || !validPng(bytes)) continue
            val partial = File(dir, "$name.part")
            partial.writeBytes(bytes)
            if (partial.renameTo(target)) changed = true else partial.delete()
        }
        return changed
    }

    private fun name(key: String, revision: Long) = "$key-$revision.png"

    companion object {
        private const val MAX_BYTES = 65536
        private const val MAX_SIDE = 256
        private val SIGNATURE = byteArrayOf(-119, 80, 78, 71, 13, 10, 26, 10)

        fun validPng(bytes: ByteArray): Boolean {
            if (bytes.size !in 33..MAX_BYTES) return false
            if (!bytes.copyOfRange(0, 8).contentEquals(SIGNATURE)) return false
            val header = ByteBuffer.wrap(bytes, 8, 16)
            if (header.getInt() != 13 || String(bytes, 12, 4, Charsets.US_ASCII) != "IHDR") return false
            header.position(16)
            val width = header.getInt()
            val height = header.getInt()
            return width in 1..MAX_SIDE && height in 1..MAX_SIDE
        }

        fun decode(reply: JSONObject): ByteArray? =
            (reply.opt("png") as? String)?.let { runCatching { Base64.getDecoder().decode(it) }.getOrNull() }
    }
}
