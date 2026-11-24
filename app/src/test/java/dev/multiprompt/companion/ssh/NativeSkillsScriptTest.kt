package dev.multiprompt.companion.ssh

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test
import java.io.File
import java.nio.file.Files

class NativeSkillsScriptTest {
    private val script = sequenceOf(File("src/main/assets/native_skills.py"),
        File("app/src/main/assets/native_skills.py")).first { it.isFile }.readBytes()

    private fun run(home: File, session: String, folder: File, path: String = System.getenv("PATH").orEmpty()): String {
        val process = ProcessBuilder("sh", "-c", TmuxCommands.nativeSkills(session, folder.absolutePath)).apply {
            environment()["HOME"] = home.absolutePath
            environment()["PATH"] = path
            environment().remove("CLAUDE_CONFIG_DIR")
            redirectErrorStream(true)
        }.start()
        process.outputStream.use { it.write(script) }
        val output = process.inputStream.bufferedReader().readText()
        assertTrue(output, process.waitFor() == 0)
        return output
    }

    private fun skill(home: File, name: String) {
        val folder = File(home, ".claude/skills/$name")
        folder.mkdirs()
        File(folder, "SKILL.md").writeText("---\nname: $name\ndescription: Fixture skill\n---\nBODY_MUST_NOT_BE_INJECTED\n")
    }

    private fun home(): File = Files.createTempDirectory("native-skills").toFile().also { it.deleteOnExit() }

    @Test
    fun launcherListsTheSkillsOfTheConnectingAccount() {
        val home = home()
        skill(home, "personal-note")
        val project = File(home, "chat-work").also { it.mkdirs() }
        skill(project, "review-native")

        val output = run(home, "live-abc", project)

        assertTrue(output, output.contains("\"review-native\""))
        assertTrue(output, output.contains("\"personal-note\""))
        assertFalse(output, output.contains("BODY_MUST_NOT_BE_INJECTED"))
    }

    @Test
    fun aChatInTheConnectingHomeNeverAsksAnotherAccount() {
        val home = home()
        skill(home, "personal-note")
        val project = File(home, "chat-work").also { it.mkdirs() }
        val bin = File(home, "bin").also { it.mkdirs() }
        val marker = File(home, "sudo-called")
        val sudo = File(bin, "sudo").apply {
            writeText("#!/bin/sh\nprintf '%s\\n' \"$*\" > '$marker'\nexit 1\n")
            setExecutable(true)
        }
        assertTrue(sudo.isFile)

        val output = run(home, "live-abc", project, bin.absolutePath + ":" + System.getenv("PATH").orEmpty())

        assertTrue(output, output.contains("\"personal-note\""))
        assertFalse("a chat in the connecting home must not re-run itself as another account", marker.exists())
    }

    @Test
    fun launcherQuotesTheSessionAndTheFolder() {
        val home = home()
        skill(home, "personal-note")
        val project = File(home, "it's here; reboot").also { it.mkdirs() }

        val output = run(home, "live-abc", project)

        assertEquals(setOf("personal-note"), Regex("\"name\": \"([^\"]+)\"").findAll(output).map { it.groupValues[1] }.toSet())
    }
}
