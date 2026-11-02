package dev.multiprompt.companion.room

import java.security.MessageDigest
import java.util.Base64
import kotlinx.coroutines.withTimeout
import org.connectbot.sshlib.SshClient
import org.connectbot.sshlib.SshSession
import org.json.JSONObject

class SshRoomWire(
    private val client: SshClient,
    private val accountId: String,
    private val serviceSource: String,
    private val bridgeSource: String,
    private val timeoutMs: Long = 20_000L,
) : RoomWire {
    private var session: SshSession? = null
    private val lines = LineAssembler()

    override suspend fun call(request: JSONObject): JSONObject = withTimeout(timeoutMs) {
        val open = ensureSession()
        open.write((request.toString() + "\n").toByteArray())
        var answer: String? = null
        while (answer == null) {
            val chunk = open.read() ?: break
            if (chunk.isEmpty()) break
            answer = lines.append(chunk).firstOrNull()
        }
        val text = answer ?: throw RoomFailure("no_answer")
        runCatching { JSONObject(text) }.getOrElse { throw RoomFailure("bad_answer") }
    }

    private suspend fun ensureSession(): SshSession {
        session?.let { return it }
        val opened = client.openSession() ?: throw RoomFailure("no_channel")
        if (!opened.requestExec("python3 -u -c \"$BOOTSTRAP\"")) {
            opened.close()
            throw RoomFailure("no_service")
        }
        opened.write(base64Line(serviceSource))
        opened.write(base64Line(bridgeSource))
        opened.write((accountKey(accountId) + "\n").toByteArray())
        session = opened
        return opened
    }

    override fun close() {
        runCatching { session?.close() }
        session = null
    }

    companion object {
        const val BOOTSTRAP = "import base64,sys,types,os,re;m=types.ModuleType('room_service');sys.modules[m.__name__]=m;exec(compile(base64.b64decode(sys.stdin.buffer.readline()),'room_service.py','exec'),m.__dict__);b=types.ModuleType('room_bridge');exec(compile(base64.b64decode(sys.stdin.buffer.readline()),'room_bridge.py','exec'),b.__dict__);k=sys.stdin.buffer.readline().decode('ascii').strip();assert re.fullmatch('[0-9a-f]{64}',k);b.serve(os.path.expanduser('~/.local/state/multiprompt/rooms-accounts/'+k))"

        fun accountKey(accountId: String): String =
            MessageDigest.getInstance("SHA-256").digest(accountId.toByteArray())
                .joinToString("") { "%02x".format(it) }

        private fun base64Line(source: String): ByteArray =
            (Base64.getEncoder().encodeToString(source.toByteArray()) + "\n").toByteArray()
    }
}
