package dev.multiprompt.companion.notify

import java.io.File
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class StatusBarRobotTest {
    private val icon = File("src/main/res/drawable/ic_stat_robot.xml").readText()

    @Test
    fun wholeRobotFitsTheStatusBarBoxWithItsMargin() {
        assertTrue(icon.contains("android:viewportWidth=\"24\""))
        assertTrue(icon.contains("android:viewportHeight=\"24\""))
        val numbers = Regex("android:pathData=\"([^\"]+)\"").findAll(icon)
            .flatMap { path -> coordinates(path.groupValues[1]) }
            .toList()
        assertTrue(numbers.isNotEmpty())
        numbers.forEach { assertTrue("$it leaves the 1 dp margin", it in 1.0..23.0) }
    }

    @Test
    fun robotIsWhiteOnClear() {
        val fills = Regex("android:fillColor=\"([^\"]+)\"").findAll(icon).map { it.groupValues[1] }.toSet()
        assertEquals(setOf("#FFFFFFFF"), fills)
    }

    @Test
    fun replyAndUpdateNotificationsUseTheWholeRobot() {
        listOf("notify/ReplyNotifier.kt", "update/UpdateNotifier.kt").forEach { name ->
            val source = File("src/main/java/dev/multiprompt/companion/$name").readText()
            assertTrue(name, source.contains(".setSmallIcon(R.drawable.ic_stat_robot)"))
            assertFalse(name, source.contains(".setSmallIcon(R.mipmap.ic_launcher)"))
        }
    }

    private fun coordinates(path: String): List<Double> {
        val out = mutableListOf<Double>()
        Regex("([A-Za-z])([^A-Za-z]*)").findAll(path).forEach { match ->
            val values = Regex("-?\\d+(?:\\.\\d+)?").findAll(match.groupValues[2]).map { it.value.toDouble() }.toList()
            when (match.groupValues[1]) {
                "M", "L" -> out += values
                "H", "V" -> out += values
                "A" -> values.chunked(7).forEach { if (it.size == 7) out += listOf(it[5], it[6]) }
            }
        }
        return out
    }
}
