package dev.multiprompt.companion.model

import org.json.JSONArray
import org.json.JSONObject
import java.time.DateTimeException
import java.time.DayOfWeek
import java.time.Instant
import java.time.OffsetDateTime
import java.time.ZoneId
import kotlin.math.round

object PlanUsage {
    const val MAX_AGE_SECONDS = 1_800L

    data class Window(
        val usedPercent: Double? = null,
        val resetsAtEpochSeconds: Long? = null,
        val timeGonePercent: Double? = null,
        val emptyHours: Double? = null,
    )

    data class Plan(
        val name: String,
        val stale: Boolean,
        val week: Window,
        val fiveHour: Window,
        val bonusResetEpochSeconds: Long? = null,
    ) {
        fun presentation(nowEpochSeconds: Long, zone: ZoneId): Presentation {
            val weekPercent = percentText(week.usedPercent)
            val headline = when {
                week.usedPercent == null -> "unknown"
                stale -> "STALE"
                else -> weekPercent
            }
            val fraction = week.usedPercent?.takeIf { it.isFinite() }?.let {
                (it.coerceIn(0.0, 100.0) / 100.0).toFloat()
            }
            val pace = paceWord(week.usedPercent, week.timeGonePercent)
            val weeklyReset = week.resetsAtEpochSeconds?.let { resetDetail(it, nowEpochSeconds, zone) }
            val paceLine = buildList {
                if (stale && week.usedPercent == null) add("STALE")
                add(pace)
                if (weeklyReset != null) add(weeklyReset)
            }.joinToString(" | ")
            val fiveHourLine = buildList {
                add("5H ${percentText(fiveHour.usedPercent)}")
                fiveHour.resetsAtEpochSeconds?.let { add("RESET ${countdown(it, nowEpochSeconds)}") }
                bonusResetEpochSeconds?.takeIf { it > nowEpochSeconds }?.let { add(bonusText(it, zone)) }
            }.joinToString(" | ")
            val detailLine = listOf("WEEK $weekPercent", fiveHourLine, forecastText(week.emptyHours)).joinToString(" | ")
            return Presentation(headline, fraction, paceLine, detailLine)
        }
    }

    data class Presentation(
        val percent: String,
        val fraction: Float?,
        val paceLine: String,
        val detailLine: String,
    )

    data class Snapshot(
        val fresh: Boolean,
        val plans: List<Plan>,
    )

    fun percentText(value: Double?): String {
        if (value == null || !value.isFinite()) return "-"
        val tenths = round(value.coerceIn(0.0, 100.0) * 10.0).toInt()
        val whole = tenths / 10
        val fraction = tenths % 10
        return if (fraction == 0) "$whole%" else "$whole.$fraction%"
    }

    fun paceWord(usedPercent: Double?, timeGonePercent: Double?): String {
        if (usedPercent == null || timeGonePercent == null) return "unknown"
        if (!usedPercent.isFinite() || !timeGonePercent.isFinite()) return "unknown"
        val difference = timeGonePercent - usedPercent
        return when {
            difference > 5.0 -> "BEHIND"
            difference < -5.0 -> "AHEAD"
            else -> "ON PACE"
        }
    }

    fun countdown(resetEpochSeconds: Long, nowEpochSeconds: Long): String {
        if (resetEpochSeconds <= nowEpochSeconds) return "DUE"
        val remainingMinutes = (resetEpochSeconds - nowEpochSeconds + 60 - 1) / 60
        val days = remainingMinutes / (24 * 60)
        val hours = remainingMinutes / 60
        val minutes = remainingMinutes % 60
        return when {
            days > 0 -> "${days}D ${hours % 24}H"
            hours > 0 -> "${hours}H ${minutes}M"
            else -> "${minutes}M"
        }
    }

    fun resetDetail(resetEpochSeconds: Long, nowEpochSeconds: Long, zone: ZoneId): String {
        val local = Instant.ofEpochSecond(resetEpochSeconds).atZone(zone)
        return "RESETS ${weekday(local.dayOfWeek)} ${clock(local.hour, local.minute)}, IN ${countdown(resetEpochSeconds, nowEpochSeconds)}"
    }

    fun bonusText(resetEpochSeconds: Long, zone: ZoneId): String {
        val local = Instant.ofEpochSecond(resetEpochSeconds).atZone(zone)
        return "BONUS %02d-%02d".format(local.monthValue, local.dayOfMonth)
    }

    fun forecastText(emptyHours: Double?): String {
        if (emptyHours == null || !emptyHours.isFinite() || emptyHours < 0.0) return "-"
        return "EMPTY IN ${round(emptyHours).toLong()}H"
    }

    fun parse(json: String, nowEpochSeconds: Long): Snapshot? {
        val start = json.indexOf('{')
        val end = json.lastIndexOf('}')
        if (start < 0 || end <= start) return null
        val root = try {
            JSONObject(json.substring(start, end + 1))
        } catch (_: Exception) {
            return null
        }
        val checkedAt = finite(root, "ts") ?: return null
        val plans = plans(root, nowEpochSeconds)
        val fresh = nowEpochSeconds - checkedAt <= MAX_AGE_SECONDS
        return Snapshot(fresh = fresh, plans = plans)
    }

    private fun plans(root: JSONObject, nowEpochSeconds: Long): List<Plan> {
        val accounts = root.opt("accounts")
        if (accounts is JSONArray) return accountPlans(accounts, nowEpochSeconds)
        if (accounts != null) return emptyList()
        return listOfNotNull(
            plan(root.optJSONObject("claude"), "Claude", "claude", nowEpochSeconds),
            plan(root.optJSONObject("codex"), "Codex", "codex", nowEpochSeconds),
        )
    }

    private fun accountPlans(accounts: JSONArray, nowEpochSeconds: Long): List<Plan> = buildList {
        for (index in 0 until accounts.length()) {
            val item = accounts.optJSONObject(index) ?: continue
            plan(item, "", "", nowEpochSeconds)?.let(::add)
        }
    }

    private fun plan(item: JSONObject?, fallbackName: String, fallbackProvider: String, nowEpochSeconds: Long): Plan? {
        if (item == null) return null
        val provider = text(item, "provider") ?: fallbackProvider
        val cluster = text(item, "cluster").orEmpty()
        val configured = text(item, "label")
            ?: text(item, "name")
            ?: text(item, "display_name")
            ?: text(item, "id")
            ?: fallbackName.takeIf { it.isNotBlank() }
            ?: return null
        val name = shortName(configured, cluster).ifBlank { providerTitle(provider) }
        if (name.isBlank()) return null
        val week = window(item.optJSONObject("week"))
        val fiveHour = window(item.optJSONObject("five_hour"))
        val stale = item.opt("stale") == true || !text(item, "error").isNullOrBlank() || week.usedPercent == null
        return Plan(
            name = name,
            stale = stale,
            week = week,
            fiveHour = fiveHour,
            bonusResetEpochSeconds = soonestReset(item.optJSONArray("resets"), nowEpochSeconds),
        )
    }

    private fun window(item: JSONObject?): Window {
        if (item == null) return Window()
        return Window(
            usedPercent = finite(item, "used"),
            resetsAtEpochSeconds = finite(item, "resets_at")?.takeIf { it in 0.0..4_000_000_000.0 }?.toLong(),
            timeGonePercent = finite(item, "time_gone"),
            emptyHours = finite(item.optJSONObject("pace"), "empty_hours")?.takeIf { it >= 0.0 },
        )
    }

    private fun soonestReset(resets: JSONArray?, nowEpochSeconds: Long): Long? {
        if (resets == null) return null
        var soonest: Long? = null
        for (index in 0 until resets.length()) {
            val expires = text(resets.optJSONObject(index), "expires") ?: continue
            val epoch = try {
                OffsetDateTime.parse(expires).toEpochSecond()
            } catch (_: DateTimeException) {
                continue
            }
            if (epoch <= nowEpochSeconds) continue
            if (soonest == null || epoch < soonest) soonest = epoch
        }
        return soonest
    }

    private fun shortName(label: String, cluster: String): String {
        val suffix = " ($cluster)"
        if (cluster.isEmpty() || !label.endsWith(suffix) || label.length <= suffix.length) return label
        return label.dropLast(suffix.length)
    }

    private fun providerTitle(provider: String): String = when (provider) {
        "claude" -> "Claude"
        "codex" -> "Codex"
        "kimi" -> "Kimi"
        else -> "AI"
    }

    private fun finite(item: JSONObject?, key: String): Double? {
        if (item == null || !item.has(key) || item.isNull(key)) return null
        val number = item.opt(key) as? Number ?: return null
        val value = number.toDouble()
        return value.takeIf { it.isFinite() }
    }

    private fun text(item: JSONObject?, key: String): String? {
        if (item == null || !item.has(key) || item.isNull(key)) return null
        val value = item.opt(key)
        return (value as? String)?.takeIf { it.isNotBlank() }
    }

    private fun weekday(day: DayOfWeek): String = when (day) {
        DayOfWeek.SUNDAY -> "SUN"
        DayOfWeek.MONDAY -> "MON"
        DayOfWeek.TUESDAY -> "TUE"
        DayOfWeek.WEDNESDAY -> "WED"
        DayOfWeek.THURSDAY -> "THU"
        DayOfWeek.FRIDAY -> "FRI"
        DayOfWeek.SATURDAY -> "SAT"
    }

    private fun clock(hour: Int, minute: Int): String = "%02d:%02d".format(hour, minute)
}
