package dev.multiprompt.companion.account

import dev.multiprompt.companion.data.CrashReport
import org.json.JSONObject
import org.junit.Assert.*
import org.junit.Test

class CrashReportUploadTraceTest {
    private val storage = mutableMapOf<String, String>()
    private var body: JSONObject? = null
    private var uploaded: String? = null
    private val http = AccountHttp { _, path, _, json ->
        if (path == "/v1/crash-reports") body = json
        AccountReply(201, JSONObject().put("stored", true))
    }
    private val auth = AccountAuth("dev.multiprompt.companion://auth/callback", http, storage::get,
        { key, value -> if (value == null) storage.remove(key) else storage[key] = value }, { 10_000L })
    private val uploader = CrashReportUpload(http, auth)
    private val releaseTrace = """
        java.lang.IllegalArgumentException: Can't represent a size of 300000 in Constraints
            at androidx.compose.ui.unit.ConstraintsKt.Constraints(Constraints.kt:100)
            at dev.multiprompt.companion.ui.b.b(r8-map-id-9f8e7d6c5b:245)
            at a.b.c(Unknown Source:7)
            at android.view.View.measure(View.java:28000)
            at android.view.ViewGroup.measureChildWithMargins(ViewGroup.java:7000)
            at android.widget.FrameLayout.onMeasure(FrameLayout.java:200)
            at android.view.View.measure(View.java:28000)
            at android.os.Looper.loopOnce(Looper.java:250)
            at android.app.ActivityThread.main(ActivityThread.java:9000)
    """.trimIndent()

    private fun signIn() {
        storage[AccountAuth.TOKENS] = JSONObject().put("access_token", "a".repeat(43))
            .put("refresh_token", "r".repeat(43)).put("token_type", "Bearer").put("expires_in", 600)
            .put("device_id", "test-device").put("account_id", "test-account")
            .put("expires_at", 610_000L).toString()
    }

    private fun upload(trace: String, summary: String = "java.lang.IllegalArgumentException", detail: String = "") {
        signIn()
        val report = CrashReport(1234567890L, "main", summary, trace, "12345678-abcd",
            "0.1.609 (100609)", "17 (SDK 37)", uploadStackTrace = trace, exitDetail = detail)
        uploader.upload(report) { uploaded = it }
        assertEquals(report.id, uploaded)
    }

    @Test fun releaseFramesWithMinifiedFileNamesSurvive() {
        upload(releaseTrace)
        val stack = body!!.getString("stack_trace")
        assertEquals(releaseTrace.lineSequence().joinToString("\n") { it.trim() }, stack)
        assertTrue(stack.contains("r8-map-id-9f8e7d6c5b:245"))
        assertTrue(stack.contains("at a.b.c(Unknown Source:7)"))
        assertTrue(stack.contains("at android.app.ActivityThread.main(ActivityThread.java:9000)"))
    }

    @Test fun safeComposeMessageReachesTheSummaryInsteadOfJustTheType() {
        upload(releaseTrace)
        assertEquals("java.lang.IllegalArgumentException: Can't represent a size of 300000 in Constraints",
            body!!.getString("summary"))
    }

    @Test fun wrappedReleaseCrashKeepsItsMessageWhenTheTraceHeaderHasNone() {
        upload("java.lang.IllegalArgumentException\n" +
            "\tat dev.multiprompt.companion.ui.b.b(r8-map-id-9f8e7d6c5b:245)\n" +
            "\tat android.view.View.measure(View.java:28000)",
            summary = "Can't represent a size of 300000 in Constraints")
        assertEquals("java.lang.IllegalArgumentException: Can't represent a size of 300000 in Constraints",
            body!!.getString("summary"))
    }

    @Test fun unsafeMessageIsDroppedAndTheTypeRemains() {
        upload("java.lang.IllegalArgumentException: chat content \"secret help\"\n" +
            "\tat dev.multiprompt.companion.ui.b.b(r8-map-id-9f8e7d6c5b:245)")
        assertEquals("java.lang.IllegalArgumentException", body!!.getString("summary"))
        assertFalse(body!!.toString().contains("secret help"))
    }

    @Test fun exitMetadataAndTraceTextReachStackAndSummary() {
        upload("", summary = "ANR: Input dispatching timed out",
            detail = "reason=ANR (6)\ndescription=Input dispatching timed out\nimportance=100\npss=2048kB\nrss=8192kB")
        assertEquals("ANR: Input dispatching timed out", body!!.getString("summary"))
        assertEquals("reason=ANR (6)\ndescription=Input dispatching timed out\nimportance=100\npss=2048kB\nrss=8192kB",
            body!!.getString("stack_trace"))
    }

    @Test fun exitTraceLinesComeBeforeTheMetadataAndStayWithinTheBodyCap() {
        upload("\tat android.os.MessageQueue.nativePollOnce(Native Method)", summary = "Native crash",
            detail = "reason=Native crash (5)")
        assertEquals("at android.os.MessageQueue.nativePollOnce(Native Method)\nreason=Native crash (5)",
            body!!.getString("stack_trace"))

        upload("\tat dev.multiprompt.companion.ui.b.b(r8-map-id-9f8e7d6c5b:245)\n".repeat(400), summary = "Signaled exit",
            detail = "reason=Signaled exit (7)")
        val capped = body!!.getString("stack_trace")
        assertTrue(capped.length <= 16000)
        assertTrue(body!!.toString().toByteArray(Charsets.UTF_8).size <= 32 * 1024)
    }
}
