package dev.multiprompt.companion.data

import android.content.Context
import android.os.Build
import dev.multiprompt.companion.BuildConfig
import org.json.JSONArray
import org.json.JSONObject
import java.util.UUID
import java.util.Collections
import java.util.IdentityHashMap
import java.io.PrintWriter
import java.io.StringWriter

data class CrashReport(
    val capturedAtEpochSeconds: Long,
    val threadName: String,
    val summary: String,
    val stackTrace: String,
    val id: String,
    val appVersion: String,
    val androidVersion: String,
    val uploaded: Boolean = false,
    val uploadStackTrace: String = "",
) {
    fun asText(displayName: String): String = buildString {
        appendLine("$displayName crash report")
        appendLine("App $appVersion")
        appendLine("Android $androidVersion")
        appendLine("Time: $capturedAtEpochSeconds")
        appendLine("Thread: $threadName")
        appendLine()
        appendLine(summary)
        append(stackTrace)
    }
}

class CrashReportStore(context: Context) {
    private val preferences = context.getSharedPreferences("crash_report", Context.MODE_PRIVATE)

    fun install() {
        val previousHandler = Thread.getDefaultUncaughtExceptionHandler()
        Thread.setDefaultUncaughtExceptionHandler { thread, throwable ->
            save(
                CrashReport(
                    capturedAtEpochSeconds = System.currentTimeMillis() / 1000,
                    threadName = thread.name,
                    summary = throwable.message ?: throwable::class.java.name,
                    stackTrace = throwable.stackTraceText(),
                    uploadStackTrace = throwable.uploadStackTrace(),
                    id = UUID.randomUUID().toString(),
                    appVersion = "${BuildConfig.VERSION_NAME} (${BuildConfig.VERSION_CODE})",
                    androidVersion = "${Build.VERSION.RELEASE} (SDK ${Build.VERSION.SDK_INT})",
                ),
            )
            previousHandler?.uncaughtException(thread, throwable)
        }
    }

    @Synchronized
    fun load(): CrashReport? {
        val capturedAt = preferences.getLong(KEY_CAPTURED_AT, 0L)
        val stackTrace = preferences.getString(KEY_STACK_TRACE, null)?.takeIf(String::isNotBlank)
            ?: return null
        return CrashReport(
            capturedAtEpochSeconds = capturedAt,
            threadName = preferences.getString(KEY_THREAD, "unknown").orEmpty(),
            summary = preferences.getString(KEY_SUMMARY, "Unknown crash").orEmpty(),
            stackTrace = stackTrace,
            uploadStackTrace = preferences.getString(KEY_UPLOAD_STACK_TRACE, "").orEmpty(),
            id = preferences.getString(KEY_ID, null) ?: UUID.nameUUIDFromBytes(
                "$capturedAt\n$stackTrace".toByteArray(Charsets.UTF_8),
            ).toString(),
            appVersion = preferences.getString(KEY_APP_VERSION, "unknown").orEmpty(),
            androidVersion = preferences.getString(KEY_ANDROID_VERSION, "unknown").orEmpty(),
            uploaded = preferences.getBoolean(KEY_UPLOADED, false),
        )
    }

    fun loadForDisplay(): CrashReport? = load()?.takeUnless { preferences.getBoolean(KEY_DISMISSED, false) }

    @Synchronized
    fun loadPending(): List<CrashReport> {
        val saved = JSONArray(preferences.getString(KEY_PENDING, "[]"))
        val pending = (0 until saved.length()).map { index ->
            val json = saved.getJSONObject(index)
            CrashReport(
                capturedAtEpochSeconds = json.getLong(KEY_CAPTURED_AT),
                threadName = json.getString(KEY_THREAD),
                summary = json.getString(KEY_SUMMARY),
                stackTrace = json.getString(KEY_STACK_TRACE),
                uploadStackTrace = json.optString(KEY_UPLOAD_STACK_TRACE),
                id = json.getString(KEY_ID),
                appVersion = json.getString(KEY_APP_VERSION),
                androidVersion = json.getString(KEY_ANDROID_VERSION),
            )
        }
        return (pending + listOfNotNull(load()?.takeUnless { it.uploaded })).distinctBy { it.id }
    }

    @Synchronized
    fun markUploaded(id: String) {
        val remaining = pendingJson(loadPending().filterNot { it.id == id })
        val editor = preferences.edit()
        if (load()?.id == id) {
            if (preferences.getBoolean(KEY_DISMISSED, false)) editor.clear()
            else editor.putBoolean(KEY_UPLOADED, true)
        }
        editor.putString(KEY_PENDING, remaining).commit()
    }

    @Synchronized
    fun dismiss() {
        if (load()?.uploaded == true) clear()
        else preferences.edit().putBoolean(KEY_DISMISSED, true).commit()
    }

    @Synchronized
    fun clear() {
        val pending = preferences.getString(KEY_PENDING, "[]")
        preferences.edit().clear().putString(KEY_PENDING, pending).commit()
    }

    @Synchronized
    internal fun save(report: CrashReport) {
        val pending = pendingJson(loadPending().filterNot { it.id == report.id })
        preferences.edit().clear()
            .putString(KEY_PENDING, pending)
            .putString(KEY_ID, report.id)
            .putString(KEY_APP_VERSION, report.appVersion)
            .putString(KEY_ANDROID_VERSION, report.androidVersion)
            .putBoolean(KEY_UPLOADED, report.uploaded)
            .putLong(KEY_CAPTURED_AT, report.capturedAtEpochSeconds)
            .putString(KEY_THREAD, report.threadName)
            .putString(KEY_SUMMARY, report.summary)
            .putString(KEY_STACK_TRACE, report.stackTrace.take(MAX_STACK_TRACE_CHARS))
            .putString(KEY_UPLOAD_STACK_TRACE, report.uploadStackTrace.take(MAX_STACK_TRACE_CHARS))
            .commit()
    }

    private fun pendingJson(reports: List<CrashReport>): String = JSONArray(reports.map { report ->
        JSONObject()
            .put(KEY_ID, report.id)
            .put(KEY_APP_VERSION, report.appVersion)
            .put(KEY_ANDROID_VERSION, report.androidVersion)
            .put(KEY_CAPTURED_AT, report.capturedAtEpochSeconds)
            .put(KEY_THREAD, report.threadName)
            .put(KEY_SUMMARY, report.summary)
            .put(KEY_STACK_TRACE, report.stackTrace)
            .put(KEY_UPLOAD_STACK_TRACE, report.uploadStackTrace)
    }).toString()

    private fun Throwable.uploadStackTrace(): String = buildString {
        val seen = Collections.newSetFromMap(IdentityHashMap<Throwable, Boolean>())
        var failure: Throwable? = this@uploadStackTrace
        while (failure != null && seen.add(failure) && seen.size <= 32) {
            if (failure !== this@uploadStackTrace) append("Caused by: ")
            appendLine(failure.javaClass.name)
            failure.stackTrace.forEach { frame ->
                appendLine("at ${frame.className}.${frame.methodName}(${frame.fileName}:${frame.lineNumber})")
            }
            failure = failure.cause
        }
    }.take(MAX_STACK_TRACE_CHARS)

    private fun Throwable.stackTraceText(): String = StringWriter().also { writer ->
        printStackTrace(PrintWriter(writer))
    }.toString()

    private companion object {
        const val KEY_UPLOAD_STACK_TRACE = "upload_stack_trace"
        const val KEY_PENDING = "pending"
        const val KEY_ID = "id"
        const val KEY_APP_VERSION = "app_version"
        const val KEY_ANDROID_VERSION = "android_version"
        const val KEY_UPLOADED = "uploaded"
        const val KEY_DISMISSED = "dismissed"
        const val KEY_CAPTURED_AT = "captured_at"
        const val KEY_THREAD = "thread"
        const val KEY_SUMMARY = "summary"
        const val KEY_STACK_TRACE = "stack_trace"
        const val MAX_STACK_TRACE_CHARS = 16_000
    }
}
