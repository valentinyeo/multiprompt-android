package dev.multiprompt.companion.data

import android.content.Context
import android.os.Build
import java.io.PrintWriter
import java.io.StringWriter

data class CrashReport(
    val capturedAtEpochSeconds: Long,
    val threadName: String,
    val summary: String,
    val stackTrace: String,
) {
    fun asText(): String = buildString {
        appendLine("multiprompt crash report")
        appendLine("Android ${Build.VERSION.RELEASE} (SDK ${Build.VERSION.SDK_INT})")
        appendLine("Time: $capturedAtEpochSeconds")
        appendLine("Thread: $threadName")
        appendLine()
        appendLine(summary)
        append(stackTrace)
    }
}

/** Keeps the most recent crash locally so it can be copied into a bug report after restart. */
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
        )
    }

    fun clear() {
        preferences.edit().clear().apply()
    }

    private fun save(report: CrashReport) {
        // A synchronous commit is intentional: the process may be terminated immediately after
        // the uncaught-exception handler returns.
        preferences.edit()
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
        const val KEY_CAPTURED_AT = "captured_at"
        const val KEY_THREAD = "thread"
        const val KEY_SUMMARY = "summary"
        const val KEY_STACK_TRACE = "stack_trace"
        const val MAX_STACK_TRACE_CHARS = 16_000
    }
}
