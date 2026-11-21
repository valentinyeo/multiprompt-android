package dev.multiprompt.companion.data

import android.app.ActivityManager
import android.content.Context
import android.os.Build
import java.io.ByteArrayOutputStream
import java.io.InputStream

class AndroidExitReasonSource(context: Context) : ExitReasonSource {
    private val app = context.applicationContext

    override fun exits(): List<ExitRecord> {
        if (Build.VERSION.SDK_INT < Build.VERSION_CODES.R) return emptyList()
        val manager = app.getSystemService(ActivityManager::class.java) ?: return emptyList()
        val history = runCatching {
            manager.getHistoricalProcessExitReasons(app.packageName, 0, MAX_RECORDS)
        }.getOrNull() ?: return emptyList()
        return history.mapNotNull { info ->
            runCatching {
                ExitRecord(
                    timestampMillis = info.timestamp,
                    pid = info.pid,
                    reason = info.reason,
                    description = info.description,
                    importance = info.importance,
                    pssKb = info.pss,
                    rssKb = info.rss,
                    traceText = info.traceInputStream?.use(::readTrace),
                )
            }.getOrNull()
        }
    }

    private fun readTrace(stream: InputStream): String {
        val output = ByteArrayOutputStream()
        val buffer = ByteArray(4096)
        while (output.size() < MAX_TRACE_BYTES) {
            val read = stream.read(buffer)
            if (read <= 0) break
            output.write(buffer, 0, read)
        }
        return output.toString(Charsets.UTF_8.name()).take(MAX_TRACE_BYTES)
    }

    private companion object {
        const val MAX_RECORDS = 16
        const val MAX_TRACE_BYTES = 32_768
    }
}
