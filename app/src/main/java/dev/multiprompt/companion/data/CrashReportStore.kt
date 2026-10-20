package dev.multiprompt.companion.data

import android.content.Context
import android.os.Build
import dev.multiprompt.companion.BuildConfig
import java.util.UUID
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
) {
    fun asText(): String = buildString {
        appendLine("multiprompt crash report")
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
                    id = UUID.randomUUID().toString(),
                    appVersion = "${BuildConfig.VERSION_NAME} (${BuildConfig.VERSION_CODE})",
                    androidVersion = "${Build.VERSION.RELEASE} (SDK ${Build.VERSION.SDK_INT})",
                ),
            )
            previousHandler?.uncaughtException(thread, throwable)
        }
    }

    fun load(): CrashReport? {
        val capturedAt = preferences.getLong(KEY_CAPTURED_AT, 0L)
        val stackTrace = preferences.getString(KEY_STACK_TRACE, null)?.takeIf(String::isNotBlank)
            ?: return null
        return CrashReport(
            capturedAtEpochSeconds = capturedAt,
            threadName = preferences.getString(KEY_THREAD, "unknown").orEmpty(),
            summary = preferences.getString(KEY_SUMMARY, "Unknown crash").orEmpty(),
            stackTrace = stackTrace,
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
    fun markUploaded(id: String) {
        if (load()?.id != id) return
        if (preferences.getBoolean(KEY_DISMISSED, false)) clear()
        else preferences.edit().putBoolean(KEY_UPLOADED, true).commit()
    }

    @Synchronized
    fun dismiss() {
        if (load()?.uploaded == true) clear()
        else preferences.edit().putBoolean(KEY_DISMISSED, true).commit()
    }

    @Synchronized
    fun clear() {
        preferences.edit().clear().apply()
    }

    @Synchronized
    internal fun save(report: CrashReport) {
        preferences.edit().clear()
            .putString(KEY_ID, report.id)
            .putString(KEY_APP_VERSION, report.appVersion)
            .putString(KEY_ANDROID_VERSION, report.androidVersion)
            .putBoolean(KEY_UPLOADED, report.uploaded)
            .putLong(KEY_CAPTURED_AT, report.capturedAtEpochSeconds)
            .putString(KEY_THREAD, report.threadName)
            .putString(KEY_SUMMARY, report.summary)
            .putString(KEY_STACK_TRACE, report.stackTrace.take(MAX_STACK_TRACE_CHARS))
            .commit()
    }

    private fun Throwable.stackTraceText(): String = StringWriter().also { writer ->
        printStackTrace(PrintWriter(writer))
    }.toString()

    private companion object {
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
