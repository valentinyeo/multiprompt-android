package dev.multiprompt.companion.account

import dev.multiprompt.companion.data.CrashReport
import org.json.JSONObject
import org.junit.Assert.*
import org.junit.Test
import java.io.IOException

class CrashReportUploadTest {
    private val storage = mutableMapOf<String, String>()
    private val requests = mutableListOf<Pair<String, String?>>()
    private var reply = AccountReply(201, JSONObject().put("stored", true))
    private var refreshReply = AccountReply(200, tokens("b"))
    private var failure: IOException? = null
    private var body: JSONObject? = null
    private val http = AccountHttp { method, path, token, json ->
        assertEquals("POST", method)
        requests += path to token
        failure?.let { throw it }
        if (path == "/oauth/token") {
            assertNull(token)
            assertEquals("refresh_token", json!!.getString("grant_type"))
            assertEquals("r".repeat(43), json.getString("refresh_token"))
            refreshReply
        } else {
            assertEquals("/v1/crash-reports", path)
            body = json
            reply
        }
    }
    private val auth = AccountAuth("dev.multiprompt.companion://auth/callback", http, storage::get,
        { key, value -> if (value == null) storage.remove(key) else storage[key] = value }, { 10_000L })
    private val uploader = CrashReportUpload(http, auth)
    private var uploaded: String? = null
    private val report = CrashReport(1234567890L, "main", "test crash", "java.lang.RuntimeException: test crash\n\tat dev.multiprompt.companion.MainViewModel.test(MainViewModel.kt:42)", "12345678-abcd",
        "0.1.213 (100213)", "15 (SDK 35)", uploadStackTrace = "java.lang.RuntimeException\nat dev.multiprompt.companion.MainViewModel.test(MainViewModel.kt:42)")

    private fun signIn() { storage[AccountAuth.TOKENS] = tokens("a").put("expires_at", 610_000L).toString() }
    private fun upload(value: CrashReport = report) = uploader.upload(value) { uploaded = it }

    @Test fun exactContractFieldsAndCrashTimeValues() {
        signIn(); upload()
        assertEquals(listOf("/v1/crash-reports" to "a".repeat(43)), requests)
        val json = body!!
        assertEquals(setOf("id", "captured_at", "app_version", "android_version", "thread", "summary", "stack_trace"), json.keys().asSequence().toSet())
        assertEquals(report.id, json.getString("id"))
        assertEquals(report.capturedAtEpochSeconds, json.getLong("captured_at"))
        assertTrue(json.get("captured_at") is Long)
        assertEquals(report.appVersion, json.getString("app_version"))
        assertEquals(report.androidVersion, json.getString("android_version"))
        assertEquals(report.threadName, json.getString("thread"))
        assertEquals("java.lang.RuntimeException", json.getString("summary"))
        assertEquals("java.lang.RuntimeException\nat dev.multiprompt.companion.MainViewModel.test(MainViewModel.kt:42)", json.getString("stack_trace"))
    }

    @Test fun truncatesEveryBoundedFieldAtContractCaps() {
        val type = "java.lang." + "E".repeat(2000) + "Exception"
        val trace = type + "\n" + "\tat dev.multiprompt.companion.MainViewModel.test(MainViewModel.kt:42)\n".repeat(300)
        signIn(); upload(report.copy(threadName = "t".repeat(201), summary = "s".repeat(2001),
            uploadStackTrace = trace, appVersion = "a".repeat(65), androidVersion = "v".repeat(65)))
        assertEquals("background", body!!.getString("thread"))
        assertEquals(type.take(2000), body!!.getString("summary"))
        assertEquals(16000, body!!.getString("stack_trace").length)
        assertEquals("a".repeat(64), body!!.getString("app_version"))
        assertEquals("v".repeat(64), body!!.getString("android_version"))
        assertTrue(body!!.toString().toByteArray(Charsets.UTF_8).size <= 32 * 1024)
    }

    @Test fun neverUploadsMessagesSessionContentHostNamesKeysOrTokens() {
        signIn()
        val secrets = listOf("prod.private.example", "session content: launch secret project", "ssh-ed25519 AAAATESTKEY",
            "-----BEGIN OPENSSH PRIVATE KEY-----", "a".repeat(43), "r".repeat(43))
        val privateText = secrets.joinToString("\n")
        upload(report.copy(threadName = privateText, summary = privateText,
            stackTrace = "java.lang.RuntimeException: $privateText\n" +
                "\tat dev.multiprompt.companion.MainViewModel.test(MainViewModel.kt:42)\n" +
                "Caused by: java.io.IOException: $privateText\n" +
                "\tat secret.host.example.command(private-session:123)\n" +
                "Suppressed: java.lang.IllegalStateException: $privateText",
            uploadStackTrace = "java.lang.RuntimeException\nat dev.multiprompt.companion.MainViewModel.test(MainViewModel.kt:42)\nCaused by: java.io.IOException\nSuppressed: java.lang.IllegalStateException"))
        val sent = body!!.toString()
        val unsafeBody = JSONObject().put("summary", privateText).put("stack_trace", privateText).toString()
        secrets.forEach {
            assertTrue("positive control: $it", unsafeBody.contains(it))
            assertFalse(it, sent.contains(it))
        }
        assertEquals("background", body!!.getString("thread"))
        assertEquals("java.lang.RuntimeException", body!!.getString("summary"))
        assertTrue(body!!.getString("stack_trace").contains("Caused by: java.io.IOException"))
        assertTrue(body!!.getString("stack_trace").contains("Suppressed: java.lang.IllegalStateException"))
        assertTrue(body!!.getString("stack_trace").contains("MainViewModel.kt:42"))
    }

    @Test fun legacyTraceUsesSameAllowlistWithoutUploadingExceptionMessages() {
        signIn()
        val trace = """
            java.lang.IllegalStateException: secret message host.example
                at dev.multiprompt.companion.MainViewModel.load(MainViewModel.kt:42)
                at private.host.example.load(private-session:123)
            Caused by: java.io.IOException: token abc
                at java.io.InputStream.read(InputStream.java:100)
        """.trimIndent()
        for (uploadTrace in listOf("", " \n\t")) {
            upload(report.copy(stackTrace = trace, uploadStackTrace = uploadTrace))
            assertEquals("java.lang.IllegalStateException", body!!.getString("summary"))
            assertEquals("java.lang.IllegalStateException\n" +
                "at dev.multiprompt.companion.MainViewModel.load(MainViewModel.kt:42)\n" +
                "Caused by: java.io.IOException\n" +
                "at java.io.InputStream.read(InputStream.java:100)", body!!.getString("stack_trace"))
            val sent = body!!.toString()
            for (secret in listOf("secret", "host.example", "token abc", "private-session")) {
                assertTrue("positive control: $secret", trace.contains(secret))
                assertFalse(secret, sent.contains(secret))
            }
        }
    }

    @Test fun unsafeOrEmptyTraceHasNonemptySafeFallbackAndBoundedUtf8Body() {
        signIn()
        for (trace in listOf("", "🔑\"\n".repeat(16000), "private session contents")) {
            upload(report.copy(stackTrace = trace, uploadStackTrace = trace, appVersion = "😀".repeat(65), androidVersion = "\"\n".repeat(65)))
            assertEquals("No safe stack frames available", body!!.getString("stack_trace"))
            assertEquals("Android application crash", body!!.getString("summary"))
            assertTrue(body!!.getString("app_version").length <= 64)
            assertFalse(body!!.getString("app_version").last().isHighSurrogate())
            assertTrue(body!!.getString("android_version").length <= 64)
            assertTrue(body!!.toString().toByteArray(Charsets.UTF_8).size <= 32 * 1024)
        }
    }

    @Test fun invalidIdIsMarkedWithoutSendingAPermanentlyInvalidRequest() {
        signIn()
        for (id in listOf("short", "a".repeat(65), "private.host.example")) {
            uploaded = null
            upload(report.copy(id = id))
            assertEquals(id, uploaded)
        }
        assertTrue(requests.isEmpty())
    }

    @Test fun createdMarksUploaded() {
        signIn(); upload(); assertEquals(report.id, uploaded)
    }

    @Test fun duplicateMarksUploaded() {
        signIn(); reply = AccountReply(200, JSONObject().put("stored", false).put("duplicate", true))
        upload(); assertEquals(report.id, uploaded)
    }

    @Test fun failuresIncluding404KeepPendingAndLogin() {
        signIn()
        val before = storage.getValue(AccountAuth.TOKENS)
        for (status in listOf(404, 413, 429, 500, 503)) {
            reply = AccountReply(status, JSONObject())
            upload()
            assertNull(uploaded)
            assertEquals(before, storage[AccountAuth.TOKENS])
        }
    }

    @Test fun permanentBadRequestMarksUploaded() {
        signIn(); reply = AccountReply(400, JSONObject().put("error", "invalid_crash_report"))
        upload(); assertEquals(report.id, uploaded); assertTrue(auth.hasSession())
    }

    @Test fun networkFailureDuringRefreshKeepsReportAndLogin() {
        signIn(); auth.invalidateAccess()
        failure = IOException("offline"); upload()
        assertNull(uploaded); assertTrue(auth.hasSession())
    }

    @Test fun ioExceptionKeepsPendingAndDoesNotThrowOrClearLogin() {
        signIn(); val before = storage.getValue(AccountAuth.TOKENS)
        failure = IOException("offline"); upload()
        assertNull(uploaded); assertEquals(before, storage[AccountAuth.TOKENS])
    }

    @Test fun signedOutSendsNothing() {
        upload(); assertTrue(requests.isEmpty()); assertNull(uploaded)
    }

    @Test fun alreadyUploadedSendsNothing() {
        signIn(); upload(report.copy(uploaded = true)); assertTrue(requests.isEmpty()); assertNull(uploaded)
    }

    @Test fun unauthorizedRefreshesOnceThenRetriesOnceWithSameReport() {
        signIn()
        val bodies = mutableListOf<String>()
        var uploads = 0
        val retryHttp = AccountHttp { method, path, token, json ->
            if (path == "/oauth/token") http.request(method, path, token, json)
            else {
                requests += path to token
                bodies += json.toString()
                AccountReply(if (uploads++ == 0) 401 else 201, JSONObject())
            }
        }
        CrashReportUpload(retryHttp, auth).upload(report) { uploaded = it }
        assertEquals(listOf("/v1/crash-reports" to "a".repeat(43), "/oauth/token" to null,
            "/v1/crash-reports" to "b".repeat(43)), requests)
        assertEquals(bodies[0], bodies[1]); assertEquals(report.id, uploaded)
        assertTrue(auth.hasSession())
    }

    @Test fun repeatedUnauthorizedStopsAfterOneRefreshAndRetainsLogin() {
        signIn(); reply = AccountReply(401, JSONObject()); upload()
        assertEquals(3, requests.size); assertNull(uploaded); assertTrue(auth.hasSession())
    }

    @Test fun rejectedRefreshNeverClearsLoginForCrashUpload() {
        for (status in listOf(400, 401, 503)) {
            signIn(); requests.clear()
            reply = AccountReply(401, JSONObject())
            refreshReply = AccountReply(status, JSONObject().put("error", "invalid_grant"))
            upload()
            assertEquals(2, requests.size); assertNull(uploaded); assertTrue(auth.hasSession())
            assertEquals("r".repeat(43), JSONObject(storage.getValue(AccountAuth.TOKENS)).getString("refresh_token"))
        }
    }

    @Test fun expiredTokenUsesExistingRefreshBeforeUpload() {
        signIn(); auth.invalidateAccess(); upload()
        assertEquals(listOf("/oauth/token" to null, "/v1/crash-reports" to "b".repeat(43)), requests)
        assertEquals(report.id, uploaded)
    }

    companion object {
        private fun tokens(access: String) = JSONObject().put("access_token", access.repeat(43))
            .put("refresh_token", "r".repeat(43)).put("token_type", "Bearer").put("expires_in", 600)
            .put("device_id", "test-device").put("account_id", "test-account")
    }
}
