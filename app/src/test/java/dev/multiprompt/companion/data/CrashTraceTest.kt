package dev.multiprompt.companion.data

import org.junit.Assert.*
import org.junit.Test

class CrashTraceTest {
    @Test fun keepsFrameworkFrames() {
        val frame = "at android.view.View.measure(View.java:28000)"
        assertEquals(frame, CrashTrace.safeLine("    $frame"))
    }

    @Test fun keepsReleaseFramesWithMinifiedFileNames() {
        for (frame in listOf(
            "at dev.multiprompt.companion.ui.a.a(r8-map-id-1a2b3c4d5e:118)",
            "at a.b.c(Unknown Source:7)",
            "at a.b.c(SourceFile:12)",
            "at a.b.c(Native Method)",
            "at a.b.c(SourceFile)",
        )) {
            assertEquals(frame, CrashTrace.safeLine(frame))
        }
    }

    @Test fun keepsExceptionHeadersWithSafeMessages() {
        assertEquals("java.lang.IllegalArgumentException: Can't represent a size of 300000 in Constraints",
            CrashTrace.safeLine("java.lang.IllegalArgumentException: Can't represent a size of 300000 in Constraints"))
        assertEquals("Caused by: java.io.IOException: upstream returned 503 (retry 2/3)",
            CrashTrace.safeLine("Caused by: java.io.IOException: upstream returned 503 (retry 2/3)"))
        assertEquals("Suppressed: kotlinx.coroutines.JobCancellationException",
            CrashTrace.safeLine("Suppressed: kotlinx.coroutines.JobCancellationException"))
    }

    @Test fun dropsUnsafeMessagesAndKeepsTheType() {
        for (message in listOf(
            "host.example",
            "session content: \"launch secret\"",
            "path /srv/keys/id_rsa",
            "value \u00e9\u00e8",
            "tab\tseparated",
            "s".repeat(201),
        )) {
            assertEquals("java.lang.IllegalStateException",
                CrashTrace.safeLine("java.lang.IllegalStateException: $message"))
        }
    }

    @Test fun dropsEverythingThatIsNotAFrameOrAnExceptionHeader() {
        for (line in listOf(
            "private message",
            "-----BEGIN OPENSSH PRIVATE KEY-----",
            "session content: launch secret project",
            "andhost.example",
            "at private.host.example.command(private session)",
            "* referenced classes and methods",
            "reason=ANR (6)",
        )) {
            assertNull(line, CrashTrace.safeLine(line))
        }
    }

    @Test fun keepsWholeMinifiedReleaseTraceThatUsedToDisappear() {
        val trace = """
            java.lang.IllegalArgumentException: Can't represent a size of 300000 in Constraints
                at androidx.compose.ui.unit.ConstraintsKt.Constraints(Constraints.kt:100)
                at dev.multiprompt.companion.ui.b.b(r8-map-id-9f8e7d6c5b:245)
                at android.view.View.measure(View.java:28000)
        """.trimIndent()
        assertEquals(listOf(
            "java.lang.IllegalArgumentException: Can't represent a size of 300000 in Constraints",
            "at androidx.compose.ui.unit.ConstraintsKt.Constraints(Constraints.kt:100)",
            "at dev.multiprompt.companion.ui.b.b(r8-map-id-9f8e7d6c5b:245)",
            "at android.view.View.measure(View.java:28000)",
        ), CrashTrace.safeLines(trace))
    }
}
