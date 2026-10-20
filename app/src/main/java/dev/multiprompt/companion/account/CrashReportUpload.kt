package dev.multiprompt.companion.account

import dev.multiprompt.companion.data.CrashReport
import org.json.JSONObject

class CrashReportUpload(private val http: AccountHttp, private val auth: AccountAuth) {
    fun upload(report: CrashReport, markUploaded: (String) -> Unit) {
        if (report.uploaded || !auth.hasSession()) return
        try {
            val body = JSONObject()
                .put("id", report.id)
                .put("captured_at", report.capturedAtEpochSeconds)
                .put("app_version", report.appVersion.take(64))
                .put("android_version", report.androidVersion.take(64))
                .put("thread", report.threadName.take(200))
                .put("summary", report.summary.take(2000))
                .put("stack_trace", report.stackTrace.take(16000))
            var reply = http.request("POST", "/v1/crash-reports", auth.accessToken(clearRejectedSession = false), body)
            if (reply.status == 401) {
                auth.invalidateAccess()
                reply = http.request("POST", "/v1/crash-reports", auth.accessToken(clearRejectedSession = false), body)
            }
            if (reply.status == 201 || (reply.status == 200 && reply.json.optBoolean("duplicate"))) markUploaded(report.id)
        } catch (_: Exception) {
        }
    }
}
