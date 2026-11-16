package dev.multiprompt.companion.model

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import java.time.ZoneOffset

class PlanUsageTest {
    private val zone = ZoneOffset.UTC
    private val now = 1_700_000_000L

    @Test
    fun desktopSampleKeepsWeeklyFiveHourPaceAndForecast() {
        val raw = """
            noise
            {"ts":$now,"mode":"claude","why":"Claude is behind","accounts":[
            {"id":"claude","label":"Claude (Primary / Secondary)","cluster":"Primary / Secondary","provider":"claude","week":{"used":35,"resets_at":1791738000,"time_gone":80.5,"pace":{"empty_hours":40}},"five_hour":{"used":4,"resets_at":${now + 90}},"resets":[{"expires":"2026-10-22T22:30:00Z"},{"expires":"invalid"},{"expires":"2026-10-19T23:59:00+02:00"}]},
            {"id":"codex","name":"Codex","provider":"codex","week":{"used":60,"resets_at":${now + 47 * 3600},"time_gone":45.7,"pace":{"empty_hours":null}}},
            {"id":"x","label":"Broken","provider":"claude","stale":true,"week":{"used":null}}]}
        """.trimIndent()
        val snapshot = PlanUsage.parse(raw, now)
        assertEquals(true, snapshot?.fresh)
        assertEquals(3, snapshot?.plans?.size)
        val claude = snapshot!!.plans[0].presentation(now, zone)
        val codex = snapshot.plans[1].presentation(now, zone)
        val broken = snapshot.plans[2].presentation(now, zone)
        assertEquals("Claude", snapshot.plans[0].name)
        assertEquals("35%", claude.percent)
        assertEquals(0.35f, requireNotNull(claude.fraction), 0.001f)
        assertEquals("BEHIND", PlanUsage.paceWord(35.0, 80.5))
        assertTrue(claude.paceLine.startsWith("BEHIND | RESETS "))
        assertTrue(claude.detailLine.startsWith("WEEK 35% | 5H 4% | RESET 2M | BONUS 10-19 | EMPTY IN 40H"))
        assertEquals("60%", codex.percent)
        assertEquals("AHEAD | RESETS THU 21:13, IN 1D 23H", codex.paceLine)
        assertEquals("WEEK 60% | 5H - | -", codex.detailLine)
        assertEquals("unknown", broken.percent)
        assertNull(broken.fraction)
        assertEquals("STALE | unknown", broken.paceLine)
        assertEquals("WEEK - | 5H - | -", broken.detailLine)
        assertEquals("0%", PlanUsage.percentText(0.0))
    }

    @Test
    fun paceThresholdMatchesTheDesktopCard() {
        assertEquals("ON PACE", PlanUsage.paceWord(55.0, 50.0))
        assertEquals("ON PACE", PlanUsage.paceWord(50.0, 55.0))
        assertEquals("BEHIND", PlanUsage.paceWord(66.0, 72.1))
        assertEquals("AHEAD", PlanUsage.paceWord(61.0, 55.0))
        assertEquals("unknown", PlanUsage.paceWord(null, 40.0))
        assertEquals("unknown", PlanUsage.paceWord(40.0, null))
        assertEquals("-", PlanUsage.percentText(null))
        assertEquals("52.5%", PlanUsage.percentText(52.5))
        assertEquals("100%", PlanUsage.percentText(150.0))
        assertEquals("DUE", PlanUsage.countdown(now, now))
        assertEquals("2H 5M", PlanUsage.countdown(now + 2 * 3600 + 5 * 60, now))
        assertEquals("-", PlanUsage.forecastText(null))
        assertEquals("EMPTY IN 0H", PlanUsage.forecastText(0.0))
    }

    @Test
    fun missingAndUnknownValuesDoNotCrash() {
        assertNull(PlanUsage.parse("", now))
        assertNull(PlanUsage.parse("not json", now))
        assertNull(PlanUsage.parse("""{"accounts":[]}""", now))
        val stale = PlanUsage.parse("""{"ts":1000,"accounts":[{"id":"claude","label":"Claude","week":{"used":"nope"},"five_hour":{"used":4}}]}""", 1000 + PlanUsage.MAX_AGE_SECONDS + 1)
        assertEquals(false, stale?.fresh)
        assertEquals("unknown", stale?.plans?.single()?.presentation(now, zone)?.percent)
        assertEquals("4%", PlanUsage.percentText(stale?.plans?.single()?.fiveHour?.usedPercent))
        val legacy = PlanUsage.parse("""{"ts":$now,"mode":"codex","claude":{"week":{"used":10}},"codex":{"label":"Codex Pro","week":{"used":20,"time_gone":20}}}""", now)
        assertEquals(listOf("Claude", "Codex Pro"), legacy?.plans?.map { it.name })
        assertEquals("ON PACE", legacy?.plans?.get(1)?.presentation(now, zone)?.paceLine)
        val extra = PlanUsage.parse("""{"ts":$now,"accounts":{"claude":{"week":{"used":1}}}}""", now)
        assertEquals(emptyList<PlanUsage.Plan>(), extra?.plans)
        val blank = PlanUsage.parse("""{"ts":$now,"accounts":[{},{"id":"kimi","provider":"kimi","week":{"used":12,"time_gone":12}}]}""", now)
        assertEquals(listOf("kimi"), blank?.plans?.map { it.name })
        assertEquals("ON PACE", blank?.plans?.single()?.presentation(now, zone)?.paceLine)
    }
}
