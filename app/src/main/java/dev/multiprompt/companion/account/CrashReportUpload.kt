package dev.multiprompt.companion.account

import dev.multiprompt.companion.data.CrashReport
import org.json.JSONObject

class CrashReportUpload(private val http: AccountHttp, private val auth: AccountAuth) {
    fun upload(report: CrashReport, markUploaded: (String) -> Unit) {
        if (report.uploaded || !auth.hasSession()) return
        if (!Regex("[A-Za-z0-9-]{8,64}").matches(report.id)) {
            markUploaded(report.id)
            return
        }
        try {
            val lines = report.uploadStackTrace.ifBlank { report.stackTrace }.lineSequence().mapNotNull { line ->
                val trimmed = line.trim()
                frame.matchEntire(trimmed)?.value ?: exception.matchEntire(trimmed)?.let {
                    it.groupValues[1] + it.groupValues[2]
                }
            }.toList()
            val body = JSONObject()
                .put("id", report.id)
                .put("captured_at", report.capturedAtEpochSeconds)
                .put("app_version", report.appVersion.take(64).trimEnd { it.isHighSurrogate() })
                .put("android_version", report.androidVersion.take(64).trimEnd { it.isHighSurrogate() })
                .put("thread", if (report.threadName == "main") "main" else "background")
                .put("summary", lines.firstOrNull { exception.matches(it) }?.take(2000) ?: "Android application crash")
                .put("stack_trace", lines.joinToString("\n").ifEmpty { "No safe stack frames available" }.take(16000))
            while (body.toString().toByteArray(Charsets.UTF_8).size > 32 * 1024) {
                val trace = body.getString("stack_trace")
                body.put("stack_trace", trace.take(trace.length / 2))
            }
            var reply = http.request("POST", "/v1/crash-reports", auth.accessToken(clearRejectedSession = false), body)
            if (reply.status == 401) {
                auth.invalidateAccess()
                reply = http.request("POST", "/v1/crash-reports", auth.accessToken(clearRejectedSession = false), body)
            }
            if (reply.status == 400 || reply.status in 200..299) markUploaded(report.id)
        } catch (_: Exception) {
        }
    }

    private companion object {
        const val CLASS = "(?:dev\\.multiprompt\\.companion|android|androidx|java|javax|kotlin|kotlinx|com\\.trilead\\.ssh2|okhttp3|okio)\\.[A-Za-z0-9_.$]+"
        val frame = Regex("at $CLASS\\((?:[A-Za-z0-9_$-]+\\.(?:java|kt):[0-9]+|SourceFile:[0-9]+|Native Method|Unknown Source)\\)")
        val exception = Regex("(Caused by: |Suppressed: )?($CLASS(?:Exception|Error|Throwable))(?::.*)?")
    }
}
