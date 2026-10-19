package dev.multiprompt.companion.reader

import java.io.File
import java.security.MessageDigest
import org.json.JSONObject

class ChatHistoryCache(
    private val directory: File,
    private val sessionBytes: Long = 2L * 1024 * 1024,
    private val totalBytes: Long = 64L * 1024 * 1024,
) {
    @Synchronized
    fun restore(sessionKey: String): ChatHistory? {
        val prefix = digest(sessionKey) + "-"
        val candidates = directory.listFiles().orEmpty()
            .filter { it.name.startsWith(prefix) && it.extension == "jsonl" }
            .sortedByDescending { it.lastModified() }
        for (file in candidates) {
            val restored = runCatching {
                require(file.length() <= sessionBytes)
                file.bufferedReader().use { reader ->
                    val header = JSONObject(requireNotNull(reader.readLine()))
                    require(header.getInt("version") == 1)
                    val rows = reader.lineSequence().map { ChatHistoryProtocol.parseRow(JSONObject(it)) }.toList()
                    require(rows.zipWithNext().all { (a, b) -> a.offset < b.offset })
                    ChatHistory(header.getString("sid"), header.getLong("inode"), header.getLong("offset"),
                        header.getLong("start"), header.getLong("mtime"), header.getString("probe"),
                        header.getBoolean("more"), rows).also { history ->
                        require(history.sid.isNotEmpty() && history.start <= history.offset)
                        require(rows.all { it.offset >= history.start && it.end <= history.offset })
                    }
                }
            }.getOrNull()
            if (restored != null) {
                file.setLastModified(System.currentTimeMillis())
                return restored
            }
        }
        return null
    }

    @Synchronized
    fun save(sessionKey: String, history: ChatHistory) {
        if (history.sid.isEmpty()) return
        directory.mkdirs()
        val lines = ArrayList<ByteArray>()
        var used = 0L
        for (row in history.rows.asReversed()) {
            val line = (row.json().toString() + "\n").toByteArray(Charsets.UTF_8)
            if (used + line.size + 1024 > sessionBytes) break
            lines.add(line)
            used += line.size
        }
        val start = if (lines.size == history.rows.size) history.start else {
            history.rows.getOrNull(history.rows.size - lines.size)?.offset ?: history.offset
        }
        val header = JSONObject().put("version", 1).put("sid", history.sid).put("inode", history.inode)
            .put("offset", history.offset).put("start", start).put("mtime", history.mtime)
            .put("probe", history.probe).put("more", history.more)
        val name = digest(sessionKey) + "-" + digest(history.sid) + ".jsonl"
        val target = File(directory, name)
        val temporary = File(directory, "$name.tmp")
        try {
            temporary.outputStream().use { output ->
                output.write((header.toString() + "\n").toByteArray(Charsets.UTF_8))
                lines.asReversed().forEach(output::write)
            }
            check(temporary.length() <= sessionBytes)
            check(temporary.renameTo(target))
        } finally {
            temporary.delete()
        }
        val files = directory.listFiles().orEmpty().filter { it.extension == "jsonl" }.sortedBy { it.lastModified() }
        var total = files.sumOf { it.length() }
        for (file in files) {
            if (total <= totalBytes) break
            val size = file.length()
            if (file.delete()) total -= size
        }
    }

    private fun digest(value: String): String = MessageDigest.getInstance("SHA-256")
        .digest(value.toByteArray(Charsets.UTF_8)).joinToString("") { "%02x".format(it) }
}
