package dev.multiprompt.companion.reader

import dev.multiprompt.companion.model.AgentKind
import java.io.File
import java.util.Base64
import java.util.concurrent.Executors
import java.util.concurrent.TimeUnit
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue

internal class ChatHistoryProcess(
    sid: String,
    cwd: String,
    home: File? = null,
    agent: AgentKind = AgentKind.CLAUDE,
    initial: ChatHistory = ChatHistory(),
) : AutoCloseable {
    val identity = ChatHistoryProtocol.Identity(agent, "", cwd, sid)
    private val script = sequenceOf(File("src/main/assets/chat_history_reader.py"),
        File("app/src/main/assets/chat_history_reader.py")).first { it.isFile }.readBytes()
    private val process = ProcessBuilder("python3", "-u", "-c",
        "import base64,sys;exec(base64.b64decode(sys.stdin.buffer.readline()))", "--serve").apply {
        if (home != null) {
            environment()["HOME"] = home.absolutePath
            environment()["CLAUDE_CONFIG_DIR"] = File(home, ".claude").absolutePath
        }
        redirectError(ProcessBuilder.Redirect.to(File("/dev/null")))
    }.start()
    private val input = process.outputStream.bufferedWriter(Charsets.UTF_8)
    private val output = process.inputStream.bufferedReader(Charsets.UTF_8)
    private val executor = Executors.newSingleThreadExecutor()
    private var id = 0L
    var history = initial
        private set
    var pages = 0
        private set

    init {
        input.write(Base64.getEncoder().encodeToString(script) + "\n")
        input.flush()
    }

    fun poll(before: Long = 0): ChatHistory {
        id++
        input.write(ChatHistoryProtocol.request(id, identity, history, before))
        input.flush()
        val reply = executor.submit<String> { requireNotNull(output.readLine()) }.get(60, TimeUnit.SECONDS)
        val framer = ChatHistoryProtocol.Framer()
        val replies = reply.toByteArray(Charsets.UTF_8).plus(10).asList().chunked(73)
            .flatMap { framer.feed(it.toByteArray()) }
        assertEquals(1, replies.size)
        history = history.apply(requireNotNull(ChatHistoryProtocol.parse(replies.single(), id, identity)))
        pages++
        return history
    }

    fun all(): ChatHistory {
        poll()
        while (history.more) poll()
        while (history.start > 0) {
            val previous = history.start
            poll(previous)
            assertTrue("Older paging must progress", history.start < previous)
            assertTrue(pages < 2000)
        }
        return history
    }

    override fun close() {
        input.close()
        process.destroyForcibly()
        executor.shutdownNow()
        output.close()
    }
}
