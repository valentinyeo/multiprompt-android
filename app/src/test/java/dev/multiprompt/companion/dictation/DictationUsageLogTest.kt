package dev.multiprompt.companion.dictation

import java.time.LocalDate
import org.junit.Assert.*
import org.junit.Test

class DictationUsageLogTest {
    @Test
    fun accumulatesStreamedSecondsAndRollsOverLocalDates() {
        val store = MemoryStore()
        var date = LocalDate.of(2026, 6, 8)
        val log = DictationUsageLog(store) { date }
        assertEquals(12.52, log.add(12.52), 0.00001)
        assertEquals(15.02, log.add(2.5), 0.00001)
        date = date.plusDays(1)
        assertEquals(0.7, log.add(0.7), 0.00001)
        assertEquals(15.02, store.values.getValue("2026-06-08"), 0.00001)
        assertEquals(0.7, store.values.getValue("2026-06-09"), 0.00001)
        assertEquals(0.7, DictationUsageLog(store) { date }.add(0.0), 0.00001)
    }

    @Test
    fun retainsExactlyThirtyCalendarDaysAndPrunesAfterGaps() {
        val store = MemoryStore()
        var date = LocalDate.of(2026, 1, 1)
        val log = DictationUsageLog(store) { date }
        repeat(40) {
            log.add(1.0)
            date = date.plusDays(1)
        }
        assertEquals(30, store.values.size)
        assertEquals("2026-01-11", store.values.keys.min())
        assertEquals("2026-02-09", store.values.keys.max())
        date = date.plusDays(35)
        log.add(2.0)
        assertEquals(mapOf(date.toString() to 2.0), store.values)
    }

    @Test
    fun rejectsInvalidDurationsWithoutWriting() {
        val store = MemoryStore()
        val log = DictationUsageLog(store)
        listOf(-1.0, Double.NaN, Double.POSITIVE_INFINITY).forEach { value ->
            assertThrows(IllegalArgumentException::class.java) { log.add(value) }
        }
        assertTrue(store.values.isEmpty())
    }

    private class MemoryStore : DictationUsageLog.Store {
        val values = mutableMapOf<String, Double>()
        override fun entries(): Map<String, Double> = values.toMap()
        override fun put(key: String, seconds: Double) { values[key] = seconds }
        override fun remove(key: String) { values.remove(key) }
    }
}
