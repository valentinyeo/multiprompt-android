package dev.multiprompt.companion.data

object CrashPayload {
    const val FALLBACK_SUMMARY = "Android application crash"
    const val FALLBACK_TRACE = "No safe stack frames available"
    private const val MAX_SUMMARY_CHARS = 2000
    private const val MAX_TRACE_CHARS = 16000

    fun summary(report: CrashReport): String {
        val lines = CrashTrace.safeLines(source(report))
        return (lines.firstOrNull(CrashTrace::isHeader)
            ?: CrashTrace.safeMessage(report.summary)
            ?: FALLBACK_SUMMARY).take(MAX_SUMMARY_CHARS)
    }

    fun stackTrace(report: CrashReport): String {
        val lines = CrashTrace.safeLines(source(report)) +
            report.exitDetail.lineSequence().map { it.trim() }.filter { it.isNotEmpty() }
        return lines.joinToString("\n").take(MAX_TRACE_CHARS).ifEmpty { FALLBACK_TRACE }
    }

    private fun source(report: CrashReport): String = report.uploadStackTrace.ifBlank { report.stackTrace }
}
