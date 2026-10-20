package dev.multiprompt.companion.data

import android.app.Application
import android.content.Context
import android.os.Build
import dev.multiprompt.companion.BuildConfig
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
        "0.1.213 (100213)", "15 (SDK 35)")

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
            assertTrue(captured.capturedAtEpochSeconds > 0)
            assertTrue(delegated)
        } finally {
            Thread.setDefaultUncaughtExceptionHandler(original)
        }
    }

    @Test fun displayUsesCapturedVersionsRatherThanCurrentDevice() {
        store.save(report)
        val text = store.load()!!.asText()
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

    @Test fun clearAndTruncationStillWork() {
        store.save(report.copy(stackTrace = "x".repeat(16001)))
        assertEquals(16000, store.load()!!.stackTrace.length)
        store.clear()
        assertNull(CrashReportStore(context).load())
    }
}
