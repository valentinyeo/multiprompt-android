package dev.multiprompt.companion.account

import dev.multiprompt.companion.data.CrashPayload
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
            val body = JSONObject()
                .put("id", report.id)
                .put("captured_at", report.capturedAtEpochSeconds)
                .put("app_version", report.appVersion.take(64).trimEnd { it.isHighSurrogate() })
                .put("android_version", report.androidVersion.take(64).trimEnd { it.isHighSurrogate() })
                .put("thread", if (report.threadName == "main") "main" else "background")
                .put("summary", CrashPayload.summary(report))
                .put("stack_trace", CrashPayload.stackTrace(report))
            while (body.toString().toByteArray(Charsets.UTF_8).size > 32 * 1024) {
                val trace = body.getString("stack_trace")
                body.put("stack_trace", trace.take(trace.length / 2))
            }
            var reply = http.request("POST", "/v1/crash-reports", auth.accessToken(clearRejectedSession = false), body)
            if (reply.status == 401) {
                auth.invalidateAccess()
                reply = http.request("POST", "/v1/crash-reports", auth.accessToken(clearRejectedSession = false), body)
            }
            if (reply.status in 200..299 ||
                (reply.status == 400 && reply.json.optString("error") == "invalid_crash_report")) markUploaded(report.id)
        } catch (_: Exception) {
        }
    }
}
