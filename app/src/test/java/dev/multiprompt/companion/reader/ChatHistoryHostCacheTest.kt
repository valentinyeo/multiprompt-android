package dev.multiprompt.companion.reader

import dev.multiprompt.companion.model.AgentKind
import java.io.File
import java.nio.file.Files
import java.util.Base64
import java.util.concurrent.TimeUnit
import org.json.JSONObject
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class ChatHistoryHostCacheTest {
    private val script = sequenceOf(File("src/main/assets/chat_history_reader.py"),
        File("app/src/main/assets/chat_history_reader.py")).first { it.isFile }.readBytes()

    private fun open(home: File, payload: ByteArray = script): Pair<String, String?> {
        val process = ProcessBuilder("sh", "-c", ChatHistoryProtocol.command(payload)).apply {
            environment()["HOME"] = home.absolutePath
            environment()["CLAUDE_CONFIG_DIR"] = File(home, ".claude").absolutePath
            redirectError(ProcessBuilder.Redirect.to(File("/dev/null")))
        }.start()
        try {
            val input = process.outputStream.bufferedWriter(Charsets.UTF_8)
            val output = process.inputStream.bufferedReader(Charsets.UTF_8)
            val hello = output.readLine()
            if (hello == ChatHistoryProtocol.NEED) input.write(Base64.getEncoder().encodeToString(payload) + "\n")
            input.write(ChatHistoryProtocol.request(1, ChatHistoryProtocol.Identity(AgentKind.CLAUDE, "", "/tmp", "none"), ChatHistory()))
            input.flush()
            val reply = output.readLine()
            input.close()
            process.waitFor(30, TimeUnit.SECONDS)
            return hello to reply
        } finally {
            process.destroyForcibly()
        }
    }

    @Test
    fun secondOpenSkipsUploadAndStillAnswers() {
        val home = Files.createTempDirectory("mp-home").toFile()
        val first = open(home)
        val second = open(home)
        assertEquals(ChatHistoryProtocol.NEED, first.first)
        assertEquals(ChatHistoryProtocol.HAVE, second.first)
        assertEquals(1L, JSONObject(requireNotNull(first.second)).getLong("id"))
        assertEquals(first.second, second.second)
        home.deleteRecursively()
    }

    @Test
    fun tamperedCacheIsRefetched() {
        val home = Files.createTempDirectory("mp-home").toFile()
        open(home)
        val cached = File(home, ".multiprompt-runtime").listFiles { file -> file.name.startsWith("payload-") }!!.single()
        cached.writeText("raise SystemExit(9)\n")
        val again = open(home)
        assertEquals(ChatHistoryProtocol.NEED, again.first)
        assertEquals(1L, JSONObject(requireNotNull(again.second)).getLong("id"))
        assertEquals(script.size.toLong(), cached.length())
        home.deleteRecursively()
    }

    @Test
    fun wrongPayloadIsRejectedAndNotCached() {
        val home = Files.createTempDirectory("mp-home").toFile()
        val process = ProcessBuilder("sh", "-c", ChatHistoryProtocol.command(script)).apply {
            environment()["HOME"] = home.absolutePath
            redirectError(ProcessBuilder.Redirect.to(File("/dev/null")))
        }.start()
        val output = process.inputStream.bufferedReader(Charsets.UTF_8)
        assertEquals(ChatHistoryProtocol.NEED, output.readLine())
        process.outputStream.write(Base64.getEncoder().encode("print(1)".toByteArray()) + byteArrayOf(10))
        process.outputStream.flush()
        assertTrue(process.waitFor(30, TimeUnit.SECONDS))
        assertEquals(65, process.exitValue())
        assertTrue(File(home, ".multiprompt-runtime").listFiles { file -> file.name.startsWith("payload-") }.isNullOrEmpty())
        home.deleteRecursively()
    }
}
