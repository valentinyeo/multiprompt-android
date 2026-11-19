package dev.multiprompt.companion.data

import java.util.UUID

data class ExitRecord(
    val timestampMillis: Long,
    val pid: Int,
    val reason: Int,
    val description: String? = null,
    val importance: Int = 0,
    val pssKb: Long = 0,
    val rssKb: Long = 0,
    val traceText: String? = null,
)

fun interface ExitReasonSource {
    fun exits(): List<ExitRecord>
}

object ExitReasons {
    const val LOW_MEMORY = 3
    const val CRASH = 4
    const val CRASH_NATIVE = 5
    const val ANR = 6
    const val SIGNALED = 7
    const val EXCESSIVE_RESOURCE_USAGE = 9
    const val CRASH_NATIVE_UNKNOWN = 11

    private const val MAX_REPORTS = 10
    private const val MAX_METADATA_CHARS = 200

    fun label(reason: Int): String? = when (reason) {
        LOW_MEMORY -> "Low memory kill"
        CRASH -> "Java crash"
        CRASH_NATIVE, CRASH_NATIVE_UNKNOWN -> "Native crash"
        ANR -> "ANR"
        SIGNALED -> "Signaled exit"
        EXCESSIVE_RESOURCE_USAGE -> "Excessive resource usage"
        else -> null
    }

    fun report(record: ExitRecord, appVersion: String, androidVersion: String): CrashReport? {
        val label = label(record.reason) ?: return null
        val description = CrashTrace.safeMessage(record.description)
        return CrashReport(
            capturedAtEpochSeconds = record.timestampMillis / 1000,
            threadName = "main",
            summary = listOfNotNull(label, description).joinToString(": "),
            stackTrace = record.traceText.orEmpty(),
            exitDetail = metadata(label, record, description),
            id = UUID.nameUUIDFromBytes("exit:${record.timestampMillis}:${record.pid}".toByteArray(Charsets.UTF_8)).toString(),
            appVersion = appVersion,
            androidVersion = androidVersion,
        )
    }

    fun reports(
        records: List<ExitRecord>,
        highWaterMillis: Long,
        appVersion: String,
        androidVersion: String,
    ): Pair<List<CrashReport>, Long> {
        val newest = records.maxOfOrNull(ExitRecord::timestampMillis) ?: highWaterMillis
        val reports = records.asSequence()
            .filter { it.timestampMillis > highWaterMillis }
            .sortedBy(ExitRecord::timestampMillis)
            .mapNotNull { report(it, appVersion, androidVersion) }
            .toList()
            .takeLast(MAX_REPORTS)
        return reports to maxOf(highWaterMillis, newest)
    }

    private fun metadata(label: String, record: ExitRecord, description: String?): String = listOfNotNull(
        "reason=$label (${record.reason})",
        description?.let { "description=$it".take(MAX_METADATA_CHARS + 12) },
        "importance=${record.importance}",
        "pss=${record.pssKb}kB",
        "rss=${record.rssKb}kB",
    ).joinToString("\n")
}
