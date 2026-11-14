package dev.multiprompt.companion.ssh

import java.io.File
import java.nio.file.Files
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Assert.assertThrows
import org.junit.Test

class FileUploadTest {
    @Test
    fun remoteNamesSanitizeHostileCharactersAndKeepTheExtension() {
        assertEquals("__touch_pwn___a_b.pdf", FileUpload.remoteName("C:\\x\\$(touch pwn); a b.pdf"))
        assertEquals("___quoted___x_.zip", FileUpload.remoteName("/x/..'quoted' `x`.zip"))
        assertEquals("__.log", FileUpload.remoteName("画\uD83D\uDDBC.log"))
        assertEquals("file", FileUpload.remoteName("C:\\x\\"))
        assertEquals("report.pdf", FileUpload.remoteName("report.pdf"))
        val long = FileUpload.remoteName("a".repeat(296) + ".pdf")
        assertEquals(80, long.length)
        assertTrue(long.endsWith(".pdf"))
    }

    @Test
    fun sessionDirectoriesNeverStartWithADotOrKeepShellSyntax() {
        assertEquals("session", FileUpload.remoteSessionDir(""))
        assertEquals("___a_____touch_x_____", FileUpload.remoteSessionDir("..'a' \"$(touch x)`画;\uD83D\uDDBC"))
        assertEquals(80, FileUpload.remoteSessionDir("z".repeat(200)).length)
    }

    @Test
    fun theUploadCommandCarriesOnlySafeNamesAndTheExpectedSize() {
        val command = FileUpload.command("..'$(whoami); 画", FileUpload.remoteName("a`b \".zip"), 12345)
        assertFalse(command.contains('"'))
        assertTrue(command.startsWith("sh -c '"))
        assertTrue(command.contains("/uploads/_____whoami____"))
        assertTrue(command.contains("/a_b__.zip"))
        assertFalse(command.contains("whoami)"))
        assertTrue(command.contains("ln -T -- \$t/data \$f"))
        assertTrue(command.contains("mktemp -d \$d/.staging."))
        assertTrue(command.contains("[ \$(wc -c < \$t/data) -eq 12345 ]"))
        assertTrue(command.contains("HUP INT TERM"))
        assertTrue(command.contains("-mmin +1440"))
        assertTrue(command.contains("printf %s\\\\n \$f"))
        assertFalse(command.contains('@'))
        assertFalse(command.contains("<dir>") || command.contains("<size>") || command.contains("<file>"))
    }

    @Test
    fun filesOverTheLimitAreRefused() {
        assertThrows(IllegalArgumentException::class.java) { FileUpload.command("s", "a.zip", FileUpload.MAX_BYTES + 1) }
        assertThrows(IllegalArgumentException::class.java) { FileUpload.command("s", "a.zip", -1) }
    }

    @Test
    fun theServerPathIsTakenFromTheLastLineAndChecked() {
        assertEquals("/home/v/.multiprompt/uploads/s/a.pdf", FileUpload.parsePath("12 bytes\r\n/home/v/.multiprompt/uploads/s/a.pdf\r\n", "a.pdf"))
        assertEquals("/home/v/.multiprompt/uploads/s/2-a.pdf", FileUpload.parsePath("/home/v/.multiprompt/uploads/s/2-a.pdf\n", "a.pdf"))
        assertNull(FileUpload.parsePath("", "a.pdf"))
        assertNull(FileUpload.parsePath("relative/a.pdf\n", "a.pdf"))
        assertNull(FileUpload.parsePath("/home/v/other.txt\n", "a.pdf"))
        assertNull(FileUpload.parsePath("/home/v/xa.pdf\n", "a.pdf"))
    }

    @Test
    fun theRealScriptStoresBytesWithoutOverwriting() {
        if (!File("/usr/bin/dd").exists() && !File("/bin/dd").exists()) return
        val home = Files.createTempDirectory("mp-upload-home").toFile()
        try {
            val payload = ByteArray(300_000) { (it * 31).toByte() }
            val first = runUpload(home, "chat one", "\$r port.pdf", payload)
            assertEquals(0, first.first)
            val firstPath = FileUpload.parsePath(first.second, FileUpload.remoteName("\$r port.pdf"))
            assertEquals("$home/.multiprompt/uploads/chat_one/_r_port.pdf", firstPath)
            assertTrue(File(firstPath!!).readBytes().contentEquals(payload))
            val second = runUpload(home, "chat one", "\$r port.pdf", payload.copyOf(10))
            assertEquals(0, second.first)
            assertEquals("$home/.multiprompt/uploads/chat_one/1-_r_port.pdf", FileUpload.parsePath(second.second, FileUpload.remoteName("\$r port.pdf")))
            assertTrue(File(firstPath).readBytes().contentEquals(payload))
            val wrongSize = ProcessBuilder("sh", "-c", FileUpload.command("chat one", "short.bin", 99)).apply {
                environment()["HOME"] = home.absolutePath
            }.start()
            wrongSize.outputStream.use { it.write(ByteArray(5)) }
            wrongSize.inputStream.readBytes()
            wrongSize.errorStream.readBytes()
            assertTrue(wrongSize.waitFor() != 0)
            assertFalse(File("$home/.multiprompt/uploads/chat_one/short.bin").exists())
        } finally {
            home.deleteRecursively()
        }
    }

    private fun runUpload(home: File, session: String, display: String, payload: ByteArray): Pair<Int, String> {
        val name = FileUpload.remoteName(display)
        val process = ProcessBuilder("sh", "-c", FileUpload.command(session, name, payload.size.toLong())).apply {
            environment()["HOME"] = home.absolutePath
        }.start()
        var stdout = ""
        val reader = Thread { stdout = process.inputStream.readBytes().toString(Charsets.UTF_8) }
        val errors = Thread { process.errorStream.readBytes() }
        reader.start()
        errors.start()
        process.outputStream.use { it.write(payload) }
        reader.join()
        errors.join()
        return process.waitFor() to stdout
    }
}
