package dev.multiprompt.companion.reader

import java.io.File
import org.junit.Assert.assertEquals
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder

class SkillQuoteEchoTest {
    @get:Rule val temporary = TemporaryFolder()

    private val sid = "00000000-0000-4000-8000-000000000001"
    private val cwd = "/work/demo"

    @Test fun skillQuoteEchoKeepsTheCommandInFront() {
        val home = temporary.newFolder("home")
        val fixture = sequenceOf(
            File("src/test/resources/reader/SOFT-3097/skill-quote-echo.jsonl"),
            File("app/src/test/resources/reader/SOFT-3097/skill-quote-echo.jsonl"),
        ).first { it.isFile }
        val transcript = File(home, ".claude/projects/-work-demo/$sid.jsonl").apply {
            parentFile!!.mkdirs()
            writeBytes(fixture.readBytes())
        }
        ChatHistoryProcess(sid, cwd, home).use { client ->
            val history = client.all()
            val quoted = ("> quoted line one\n>\n> quoted line two\n>\n> quoted line three\n>\n" +
                "> quoted line four\n>\n> quoted line five\n>\n> … own question @Builder{live-builder} ?")
            assertEquals(
                listOf("/pstack:correct $quoted", "echo reply"),
                history.messages.map { it.text },
            )
        }
    }
}