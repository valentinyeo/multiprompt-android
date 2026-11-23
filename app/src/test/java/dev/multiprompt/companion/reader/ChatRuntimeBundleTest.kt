package dev.multiprompt.companion.reader

import java.io.File
import java.nio.file.Files
import java.nio.file.attribute.PosixFilePermission
import java.util.Base64
import java.util.EnumSet
import java.util.concurrent.TimeUnit
import org.json.JSONObject
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class ChatRuntimeBundleTest {
    private val reader = sequenceOf(File("src/main/assets/chat_history_reader.py"),
        File("app/src/main/assets/chat_history_reader.py")).first { it.isFile }.readBytes()

    private fun bundle() = bundleWithReader(reader)

    private fun serve(home: File, payload: ByteArray = bundle()): String {
        val process = ProcessBuilder("sh", "-c", ChatHistoryProtocol.command(payload)).apply {
            environment()["HOME"] = home.absolutePath
            environment()["CLAUDE_CONFIG_DIR"] = File(home, ".claude").absolutePath
            redirectError(ProcessBuilder.Redirect.INHERIT)
        }.start()
        try {
            val input = process.outputStream.bufferedWriter(Charsets.UTF_8)
            val output = process.inputStream.bufferedReader(Charsets.UTF_8)
            val hello = output.readLine()
            if (hello == ChatHistoryProtocol.NEED) input.write(Base64.getEncoder().encodeToString(payload) + "\n")
            input.write(JSONObject().put("id", 1).put("op", "engine_info").toString() + "\n")
            input.flush()
            val reply = output.readLine()
            input.close()
            process.waitFor(30, TimeUnit.SECONDS)
            return requireNotNull(reply)
        } finally {
            process.destroyForcibly()
        }
    }

    @Test
    fun phoneBundleCannotDowngradeNewerServerRuntime() {
        val legacy = sequenceOf(
            File("tools/fixtures/SOFT-2524/legacy-reader.py"),
            File("../tools/fixtures/SOFT-2524/legacy-reader.py"),
        ).first { it.isFile }.readBytes()
        val home = Files.createTempDirectory("mp-runtime").toFile()
        serve(home, bundleWithReader(legacy))
        serve(home)
        val runtime = File(home, ".multiprompt-runtime")
        val activeAfterCurrent = JSONObject(File(runtime, "active.json").readText())
        serve(home, bundleWithReader(legacy))
        val activeAfterLegacy = JSONObject(File(runtime, "active.json").readText())
        assertEquals(activeAfterCurrent.getInt("generation"), activeAfterLegacy.getInt("generation"))
        assertEquals(activeAfterCurrent.getString("sha256"), activeAfterLegacy.getString("sha256"))
        home.deleteRecursively()
    }

    private fun bundleWithReader(readerBytes: ByteArray): ByteArray {
        val mpSend = sequenceOf(File("src/main/assets/mp_send.py"), File("app/src/main/assets/mp_send.py"))
            .first { it.isFile }.readBytes()
        val feedback = sequenceOf(File("src/main/assets/multibot_feedback.py"), File("app/src/main/assets/multibot_feedback.py"))
            .first { it.isFile }.readBytes()
        val claude = sequenceOf(File("src/main/assets/chat_connectors/claude.py"), File("app/src/main/assets/chat_connectors/claude.py"))
            .first { it.isFile }.readBytes()
        val codex = sequenceOf(File("src/main/assets/chat_connectors/codex.py"), File("app/src/main/assets/chat_connectors/codex.py"))
            .first { it.isFile }.readBytes()
        fun encode(bytes: ByteArray) = Base64.getEncoder().encodeToString(bytes)
        val prefix = "CONNECTOR_SOURCES = {'claude':'${encode(claude)}','codex':'${encode(codex)}'}\n" +
            "MP_SEND_SOURCE = '${encode(mpSend)}'\n" +
            "FEEDBACK_SOURCE = '${encode(feedback)}'\n" +
            "MULTIBOT_APP_VERSION = 'android-jvm-test'\n"
        return prefix.toByteArray(Charsets.UTF_8) + readerBytes
    }

    @Test
    fun bundledReaderInstallsMpSendWithOwnerExecuteBit() {
        val text = sequenceOf(File("src/main/assets/chat_history_reader.py"), File("app/src/main/assets/chat_history_reader.py"))
            .first { it.isFile }.readText()
        assertTrue(text.contains("def publish(name, data, mode=0o600):"))
        assertTrue(text.contains("publish(stem, payload, 0o700)"))
    }

    @Test
    fun phoneInstallMakesMpSendExecutable() {
        val home = Files.createTempDirectory("mp-runtime").toFile()
        serve(home)
        val runtime = File(home, ".multiprompt-runtime")
        val mpSend = File(runtime, "mp-send")
        assertTrue(mpSend.exists())
        val perms = Files.getPosixFilePermissions(mpSend.toPath())
        assertTrue(perms.contains(PosixFilePermission.OWNER_EXECUTE))
        assertFalse(EnumSet.of(PosixFilePermission.GROUP_EXECUTE, PosixFilePermission.OTHERS_EXECUTE).any { perms.contains(it) })
        home.deleteRecursively()
    }
}