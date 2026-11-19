package dev.multiprompt.companion.data

import org.junit.Assert.*
import org.junit.Test

class ExitReasonsTest {
    private val appVersion = "0.1.609 (100609)"
    private val androidVersion = "17 (SDK 37)"
    private val anrTrace = """
        ----- pid 4210 at 2026-08-16 15:40:11.118 -----
        Cmd line: dev.multiprompt.companion
        "main" prio=5 tid=1 Native
          at android.os.MessageQueue.nativePollOnce(Native Method)
    """.trimIndent()

    private fun record(
        timestampMillis: Long = 1_000L,
        pid: Int = 4210,
        reason: Int = ExitReasons.ANR,
        description: String? = "Input dispatching timed out",
        traceText: String? = anrTrace,
    ) = ExitRecord(timestampMillis, pid, reason, description, 100, 2048L, 8192L, traceText)

    @Test fun mapsEveryReportableExitReasonToItsSummary() {
        val expected = mapOf(
            ExitReasons.ANR to "ANR",
            ExitReasons.LOW_MEMORY to "Low memory kill",
            ExitReasons.CRASH_NATIVE to "Native crash",
            ExitReasons.CRASH to "Java crash",
            ExitReasons.SIGNALED to "Signaled exit",
            ExitReasons.EXCESSIVE_RESOURCE_USAGE to "Excessive resource usage",
        )
        for ((reason, label) in expected) {
            assertEquals(label, ExitReasons.report(record(reason = reason, description = null), appVersion, androidVersion)?.summary)
        }
        for (skipped in listOf(0, 1, 7, 8, 10, 11, 12, 13, 14, 15, 16)) {
            assertNull("reason $skipped", ExitReasons.report(record(reason = skipped), appVersion, androidVersion))
        }
    }

    @Test fun summaryKeepsASafeExitDescriptionNextToTheReason() {
        val report = ExitReasons.report(record(), appVersion, androidVersion)!!
        assertEquals("ANR: Input dispatching timed out", report.summary)
        assertEquals("ANR", ExitReasons.report(record(description = "user \"quoted\""), appVersion, androidVersion)!!.summary)
        assertEquals("ANR", ExitReasons.report(record(description = " "), appVersion, androidVersion)!!.summary)
    }

    @Test fun traceTextAndExitMetadataBothReachTheUploadedStack() {
        val report = ExitReasons.report(record(), appVersion, androidVersion)!!
        val stack = CrashPayload.stackTrace(report)
        assertEquals("at android.os.MessageQueue.nativePollOnce(Native Method)\n" +
            "reason=ANR (6)\ndescription=Input dispatching timed out\nimportance=100\npss=2048kB\nrss=8192kB", stack)
        assertEquals("ANR: Input dispatching timed out", CrashPayload.summary(report))
    }

    @Test fun missingTraceStillReportsReasonDescriptionAndMemory() {
        for (trace in listOf(null, "", " \n\t", "----- pid 4210 -----\nCmd line: dev.multiprompt.companion")) {
            val report = ExitReasons.report(record(traceText = trace), appVersion, androidVersion)!!
            val stack = CrashPayload.stackTrace(report)
            assertTrue(stack, stack.startsWith("reason=ANR (6)"))
            assertTrue(stack.contains("description=Input dispatching timed out"))
            assertTrue(stack.contains("importance=100"))
            assertTrue(stack.contains("pss=2048kB"))
            assertTrue(stack.contains("rss=8192kB"))
        }
    }

    @Test fun stableIdPerTimestampAndPid() {
        val first = ExitReasons.report(record(), appVersion, androidVersion)!!
        val second = ExitReasons.report(record(), appVersion, androidVersion)!!
        val otherPid = ExitReasons.report(record(pid = 4211), appVersion, androidVersion)!!
        assertEquals(first.id, second.id)
        assertNotEquals(first.id, otherPid.id)
        assertTrue(Regex("[A-Za-z0-9-]{8,64}").matches(first.id))
        assertEquals(1L, first.capturedAtEpochSeconds)
        assertEquals(appVersion, first.appVersion)
        assertEquals(androidVersion, first.androidVersion)
    }

    @Test fun highWaterMarkOnlyReportsNewerExitsAndNeverGoesBackwards() {
        val old = record(timestampMillis = 1_000L, pid = 1)
        val middle = record(timestampMillis = 2_000L, pid = 2)
        val recent = record(timestampMillis = 3_000L, pid = 3)
        val (reports, highWater) = ExitReasons.reports(listOf(old, middle, recent), 1_000L, appVersion, androidVersion)
        assertEquals(listOf(middle.id, recent.id), reports.map(CrashReport::id))
        assertEquals(3_000L, highWater)

        val (again, sameHighWater) = ExitReasons.reports(listOf(old, middle, recent), highWater, appVersion, androidVersion)
        assertTrue(again.isEmpty())
        assertEquals(3_000L, sameHighWater)

        val (olderOnly, keptHighWater) = ExitReasons.reports(listOf(old), highWater, appVersion, androidVersion)
        assertTrue(olderOnly.isEmpty())
        assertEquals(highWater, keptHighWater)
    }

    @Test fun skippedReasonsStillAdvanceTheHighWaterMark() {
        val filtered = record(timestampMillis = 5_000L, reason = 10)
        val (reports, highWater) = ExitReasons.reports(listOf(filtered), 1_000L, appVersion, androidVersion)
        assertTrue(reports.isEmpty())
        assertEquals(5_000L, highWater)
    }

    @Test fun oldestExitsAreDroppedWhenMoreThanTheCapArePending() {
        val records = (1..15).map { record(timestampMillis = it * 1_000L, pid = it) }
        val (reports, _) = ExitReasons.reports(records, 0L, appVersion, androidVersion)
        assertEquals(10, reports.size)
        assertEquals(6L, reports.first().capturedAtEpochSeconds)
        assertEquals(15L, reports.last().capturedAtEpochSeconds)
    }
}
