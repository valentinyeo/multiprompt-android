package dev.multiprompt.companion.data

import android.app.Application
import android.content.Context
import android.os.Build
import dev.multiprompt.companion.BuildConfig
import dev.multiprompt.companion.account.AccountAuth
import dev.multiprompt.companion.account.AccountHttp
import dev.multiprompt.companion.account.AccountReply
import dev.multiprompt.companion.account.CrashReportUpload
import org.json.JSONObject
import java.io.IOException
import org.junit.Assert.*
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.RuntimeEnvironment
import org.robolectric.annotation.Config
import java.util.UUID

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [35], application = Application::class)
class CrashReportStoreTest {
    private val context = RuntimeEnvironment.getApplication()
    private val store = CrashReportStore(context)
    private val report = CrashReport(123L, "main", "crash", "trace", UUID.randomUUID().toString(),
        "0.1.213 (100213)", "15 (SDK 35)", uploadStackTrace = "java.lang.RuntimeException")

    @Test fun newFieldsAndUploadFlagSurviveRecreation() {
        store.save(report)
        assertEquals(report, CrashReportStore(context).load())
        store.markUploaded(report.id)
        assertEquals(report.copy(uploaded = true), CrashReportStore(context).load())
        assertEquals(report.copy(uploaded = true), store.loadForDisplay())
        store.save(report.copy(id = UUID.randomUUID().toString()))
        assertFalse(CrashReportStore(context).load()!!.uploaded)
    }

    @Test fun legacyReportGetsSameDeterministicIdOnTwoLoads() {
        val prefs = context.getSharedPreferences("crash_report", Context.MODE_PRIVATE)
        prefs.edit().clear().putLong("captured_at", 123L).putString("stack_trace", "trace").commit()
        val first = store.load()!!
        val second = CrashReportStore(context).load()!!
        assertEquals(first.id, second.id)
        assertEquals(UUID.nameUUIDFromBytes("123\ntrace".toByteArray()).toString(), first.id)
        assertTrue(Regex("[A-Za-z0-9-]{8,64}").matches(first.id))
        assertFalse(first.uploaded)
        assertEquals("", first.uploadStackTrace)
        store.markUploaded(first.id)
        assertTrue(CrashReportStore(context).load()!!.uploaded)
        prefs.edit().putString("stack_trace", "other trace").commit()
        assertNotEquals(first.id, store.load()!!.id)
    }

    @Test fun capturesUuidAndVersionMetadataAtCrashTime() {
        val original = Thread.getDefaultUncaughtExceptionHandler()
        var delegated = false
        try {
            Thread.setDefaultUncaughtExceptionHandler { _, _ -> delegated = true }
            store.install()
            Thread.getDefaultUncaughtExceptionHandler()!!.uncaughtException(Thread.currentThread(), RuntimeException("IGSH-429 test crash"))
            val captured = CrashReportStore(context).load()!!
            assertEquals(captured.id, UUID.fromString(captured.id).toString())
            assertEquals(4, UUID.fromString(captured.id).version())
            assertEquals("${BuildConfig.VERSION_NAME} (${BuildConfig.VERSION_CODE})", captured.appVersion)
            assertEquals("${Build.VERSION.RELEASE} (SDK ${Build.VERSION.SDK_INT})", captured.androidVersion)
            assertEquals("IGSH-429 test crash", captured.summary)
            assertTrue(captured.stackTrace.contains("java.lang.RuntimeException: IGSH-429 test crash"))
            assertTrue(captured.uploadStackTrace.startsWith("java.lang.RuntimeException\n"))
            assertFalse(captured.uploadStackTrace.contains("IGSH-429 test crash"))
            assertTrue(captured.uploadStackTrace.contains("CrashReportStoreTest.kt:"))
            assertTrue(captured.capturedAtEpochSeconds > 0)
            assertTrue(delegated)
        } finally {
            Thread.setDefaultUncaughtExceptionHandler(original)
        }
    }

    @Test fun captureNeverTreatsPrivateMessagesOrForgedFramesAsUploadMetadata() {
        val original = Thread.getDefaultUncaughtExceptionHandler()
        val secrets = listOf("prod.private.example", "private session contents", "-----BEGIN OPENSSH PRIVATE KEY-----",
            "a".repeat(43), "r".repeat(43), "dev.multiprompt.companion.LeakedSession.secret")
        val failure = RuntimeException(secrets.joinToString("\n") +
            "\n\tat dev.multiprompt.companion.LeakedSession.secret(Session.kt:1)", IOException("secret cause message"))
        try {
            Thread.setDefaultUncaughtExceptionHandler { _, _ -> }
            store.install()
            Thread.getDefaultUncaughtExceptionHandler()!!.uncaughtException(Thread("prod.private.example"), failure)
            val captured = store.load()!!
            var sent = ""
            val uploader = uploader(AccountHttp { _, _, _, body ->
                sent = body.toString()
                AccountReply(201, JSONObject().put("stored", true))
            })
            uploader.upload(captured, store::markUploaded)
            assertTrue(sent.contains("CrashReportStoreTest.kt"))
            assertTrue(sent.contains("Caused by: java.io.IOException"))
            secrets.forEach {
                assertTrue("positive control: $it", captured.asText("Multi Bot").contains(it))
                assertFalse(it, sent.contains(it))
            }
            assertFalse(sent.contains("secret cause message"))
            assertTrue(store.loadPending().isEmpty())
        } finally {
            Thread.setDefaultUncaughtExceptionHandler(original)
        }
    }

    @Test fun displayUsesCapturedVersionsRatherThanCurrentDevice() {
        store.save(report)
        val text = store.load()!!.asText("Multi Bot")
        assertTrue(text.startsWith("Multi Bot crash report\n"))
        assertTrue(text.contains("App ${report.appVersion}"))
        assertTrue(text.contains("Android ${report.androidVersion}"))
        assertTrue(text.contains(report.stackTrace))
    }

    @Test fun dismissedPendingReportStaysUploadableAcrossLaunchesAndClearsAfterSuccess() {
        store.save(report)
        store.dismiss()
        assertNull(CrashReportStore(context).loadForDisplay())
        assertEquals(report, CrashReportStore(context).load())
        store.markUploaded(report.id)
        assertNull(CrashReportStore(context).load())
    }

    @Test fun dismissingUploadedReportClearsItAndNewCrashIsVisible() {
        store.save(report)
        store.markUploaded(report.id)
        store.dismiss()
        assertNull(store.load())
        store.save(report.copy(id = UUID.randomUUID().toString()))
        assertNotNull(store.loadForDisplay())
    }

    @Test fun staleUploadCannotMarkOrClearNewCrash() {
        store.save(report)
        store.markUploaded("other-id")
        assertFalse(store.load()!!.uploaded)
        store.dismiss()
        val next = report.copy(id = UUID.randomUUID().toString())
        store.save(next)
        store.markUploaded(report.id)
        assertEquals(next, store.loadForDisplay())
    }

    @Test fun successiveCrashesStayQueuedAcrossRecreationAndOutOfOrderAcknowledgements() {
        store.save(report)
        store.dismiss()
        val next = report.copy(id = UUID.randomUUID().toString())
        store.save(next)
        assertEquals(listOf(report, next), CrashReportStore(context).loadPending())
        store.markUploaded(next.id)
        assertEquals(listOf(report), CrashReportStore(context).loadPending())
        store.dismiss()
        assertNull(store.load())
        assertEquals(listOf(report), CrashReportStore(context).loadPending())
        store.markUploaded(report.id)
        assertTrue(CrashReportStore(context).loadPending().isEmpty())
    }

    @Test fun serverCreatedDuplicateAndBadRequestRemoveRealQueuedReports() {
        for (status in listOf(201, 200, 400)) {
            for (dismissed in listOf(false, true)) {
                store.save(report)
                if (dismissed) store.dismiss()
                val uploader = uploader(AccountHttp { _, _, _, _ ->
                    AccountReply(status, JSONObject().put("duplicate", status == 200)
                        .apply { if (status == 400) put("error", "invalid_crash_report") })
                })
                store.loadPending().forEach { uploader.upload(it, store::markUploaded) }
                assertTrue("status=$status dismissed=$dismissed", CrashReportStore(context).loadPending().isEmpty())
                if (dismissed) assertNull(store.load())
                else assertTrue(store.load()!!.uploaded)
            }
        }
    }

    @Test fun networkErrorAndServerErrorRetainRealQueueAndRetryWithSameId() {
        store.save(report)
        store.dismiss()
        val ids = mutableListOf<String>()
        var status = 500
        var offline = true
        val uploader = uploader(AccountHttp { _, _, _, body ->
            ids += body!!.getString("id")
            if (offline) throw IOException("offline")
            AccountReply(status, JSONObject().put("duplicate", status == 200))
        })
        store.loadPending().forEach { uploader.upload(it, store::markUploaded) }
        assertEquals(listOf(report), CrashReportStore(context).loadPending())
        offline = false
        store.loadPending().forEach { uploader.upload(it, store::markUploaded) }
        assertEquals(listOf(report), CrashReportStore(context).loadPending())
        status = 200
        store.loadPending().forEach { uploader.upload(it, store::markUploaded) }
        assertTrue(CrashReportStore(context).loadPending().isEmpty())
        assertEquals(listOf(report.id, report.id, report.id), ids)
    }

    @Test fun signedOutLeavesRealQueueUntouchedAndSendsNothing() {
        store.save(report)
        var requests = 0
        val uploader = uploader(AccountHttp { _, _, _, _ ->
            requests++
            AccountReply(201, JSONObject())
        }, signedIn = false)
        store.loadPending().forEach { uploader.upload(it, store::markUploaded) }
        assertEquals(0, requests)
        assertEquals(listOf(report), CrashReportStore(context).loadPending())
    }

    private fun uploader(http: AccountHttp, signedIn: Boolean = true): CrashReportUpload {
        val storage = mutableMapOf<String, String>()
        if (signedIn) storage[AccountAuth.TOKENS] = JSONObject()
            .put("access_token", "a".repeat(43)).put("expires_at", Long.MAX_VALUE).toString()
        val auth = AccountAuth("dev.multiprompt.companion://auth/callback", http, storage::get,
            { key, value -> if (value == null) storage.remove(key) else storage[key] = value })
        return CrashReportUpload(http, auth)
    }

    @Test fun clearAndTruncationStillWork() {
        store.save(report.copy(stackTrace = "x".repeat(16001)))
        assertEquals(16000, store.load()!!.stackTrace.length)
        store.clear()
        assertNull(CrashReportStore(context).load())
    }
}
