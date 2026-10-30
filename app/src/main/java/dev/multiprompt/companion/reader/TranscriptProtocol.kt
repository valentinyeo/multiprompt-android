package dev.multiprompt.companion.reader

import dev.multiprompt.companion.model.AgentKind
import java.io.ByteArrayOutputStream
import java.nio.ByteBuffer
import java.nio.charset.CodingErrorAction
import java.util.Base64

data class TranscriptMessage(val role: String, val timestamp: String?, val text: String)

object TranscriptProtocol {
    const val END = "@@MP_END@@"
    const val MAX_MESSAGES = 96
    const val MAX_TEXT_BYTES = 180_000
    const val MAX_WIRE_BYTES = 260_000

    data class Identity(val agent: AgentKind, val tmuxName: String, val cwd: String = "", val sid: String? = null)
    data class Response(
        val status: String,
        val sid: String?,
        val token: String?,
        val messages: List<TranscriptMessage>,
    )

    fun supports(agent: AgentKind): Boolean = agent == AgentKind.CLAUDE || agent == AgentKind.CODEX || agent == AgentKind.HAX

    fun wireAgent(agent: AgentKind): String = if (agent == AgentKind.HAX) "codex" else agent.name.lowercase()

    fun request(id: Long, identity: Identity, token: String? = null): String {
        require(id > 0 && supports(identity.agent))
        val fields = listOf(
            id.toString(), "transcript", wireAgent(identity.agent),
            if (identity.sid == null) "tmux:${identity.tmuxName}" else token ?: "-",
            identity.cwd.ifBlank { "-" }, identity.sid ?: "-",
        )
        require(fields.none { field -> field.any { it.code < 32 || it.code == 127 } })
        return fields.joinToString("\t", postfix = "\n").also { require(it.toByteArray().size <= 4096) }
    }

    fun parse(wire: String, id: Long, identity: Identity): Response? {
        if (!supports(identity.agent) || wire.toByteArray(Charsets.UTF_8).size > MAX_WIRE_BYTES) return null
        val lines = wire.split('\n').map { it.trimEnd('\r') }.dropLastWhile { it.isEmpty() }
        val fields = lines.firstOrNull()?.split('\t') ?: return null
        val tmuxRequest = identity.sid == null
        if (fields.size !in (if (tmuxRequest) 9..9 else 6..7)) return null
        if (fields[0] != "@mp" || fields[1].toLongOrNull() != id || fields[2] != "transcript") return null
        val status = fields[3]
        if (status !in setOf("ok", "same", "missing")) return null
        val sid = fields[4].takeUnless { it == "-" }
        if (sid != null && !SESSION_ID.matches(sid)) return null
        if (tmuxRequest) {
            if (fields[7] != wireAgent(identity.agent) || fields[8] != identity.tmuxName) return null
        } else if (sid != identity.sid) return null
        val count = fields[5].toIntOrNull() ?: return null
        if (count !in 0..MAX_MESSAGES || (status == "ok" && (count == 0 || sid == null))) return null
        if (status != "ok" && count != 0) return null
        val token = fields.getOrNull(6)?.takeUnless { it == "-" }
        if (token != null && (token.length > 40 || !TOKEN.matches(token))) return null
        if (lines.size != count + 2 || lines.last() != END) return null
        var bytesUsed = 0
        val messages = ArrayList<TranscriptMessage>()
        for (line in lines.subList(1, lines.lastIndex)) {
            val row = line.split('\t')
            if (row.size != 4 || row[0] != "M" || row[1] !in setOf("user", "assistant")) return null
            val bytes = try {
                Base64.getDecoder().decode(row[3])
            } catch (_: IllegalArgumentException) {
                return null
            }
            if (Base64.getEncoder().encodeToString(bytes) != row[3]) return null
            bytesUsed += bytes.size
            if (bytesUsed > MAX_TEXT_BYTES) return null
            val text = try {
                decodeUtf8(bytes)
            } catch (_: java.nio.charset.CharacterCodingException) {
                return null
            }
            if (text.isEmpty()) return null
            if (row[1] == "user" && text.trimStart().startsWith("<task-notification>")) continue
            messages.add(TranscriptMessage(row[1], row[2].takeUnless { it == "-" }, text))
        }
        return Response(status, sid, token, messages)
    }

    class Framer {
        private val pending = ByteArrayOutputStream()
        private val linePrefix = ByteArray(END.length + 1)
        private var lineLength = 0

        fun feed(chunk: ByteArray): List<String> {
            val frames = ArrayList<String>()
            for (byte in chunk) {
                require(pending.size() < MAX_WIRE_BYTES) { "The transcript response was too large" }
                pending.write(byte.toInt())
                if (byte == '\n'.code.toByte()) {
                    val line = if (lineLength <= linePrefix.size) {
                        String(linePrefix, 0, lineLength, Charsets.US_ASCII).trimEnd('\r')
                    } else null
                    if (line == END) {
                        frames.add(decodeUtf8(pending.toByteArray()))
                        pending.reset()
                    }
                    lineLength = 0
                } else {
                    if (lineLength < linePrefix.size) linePrefix[lineLength] = byte
                    lineLength++
                }
            }
            return frames
        }

        fun hasPending(): Boolean = pending.size() != 0
    }

    private fun decodeUtf8(bytes: ByteArray): String = Charsets.UTF_8.newDecoder()
        .onMalformedInput(CodingErrorAction.REPORT)
        .onUnmappableCharacter(CodingErrorAction.REPORT)
        .decode(ByteBuffer.wrap(bytes)).toString()

    private val SESSION_ID = Regex("[A-Za-z0-9_-]{1,80}")
    private val TOKEN = Regex("[0-9]+\\.[0-9]+")
}
