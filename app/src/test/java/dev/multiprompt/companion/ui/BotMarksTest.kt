package dev.multiprompt.companion.ui

import kotlin.math.cos
import kotlin.math.hypot
import kotlin.math.sin
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class BotMarksTest {
    private fun head(face: Int) = botMarks(face, 3).single { it.shade != null }

    @Test fun desktopNamesGetTheDesktopFaces() {
        assertEquals(15, BotAppearance.forSession("agent-one").face)
        assertEquals(13, BotAppearance.forSession("agent-two").face)
    }

    @Test fun everyFaceDrawsAShadedHeadAndEyes() {
        (0 until BOT_FACES).forEach { face ->
            val marks = botMarks(face, 7)
            assertEquals("face $face head", 1, marks.count { it.shade != null })
            assertTrue("face $face eyes", marks.any { it.color == 0xFFF2F2F2L })
            assertTrue("face $face visor", marks.any { it.color == 0xFF121212L })
        }
    }

    @Test fun theHeadShadesToSeventyFourPercent() {
        val mark = head(0)
        assertEquals(0xFF6F9FE0L, mark.color)
        assertEquals(0xFF5275A5L, mark.shade)
    }

    @Test fun faceSevenHasARoundHead() {
        head(7).points.forEach { assertEquals(164.0, hypot(it.x - 196.0, it.y - 214.0), 0.01) }
    }

    @Test fun facesEightAndNineAreMirrored() {
        val plain = head(0).points
        val mirrored = head(8).points
        plain.zip(mirrored).forEach { (a, b) ->
            assertEquals(256f - a.x, b.x, 0.01f)
            assertEquals(a.y, b.y, 0.01f)
        }
    }

    @Test fun facesTwelveAndSeventeenAreTiltedFourteenDegrees() {
        listOf(Triple(12, 8.0 to 132.0, -14.0), Triple(17, -60.0 to 158.0, 14.0)).forEach { (face, start, degrees) ->
            val angle = Math.toRadians(degrees)
            val dx = start.first - 128
            val dy = start.second - 128
            val first = head(face).points.first()
            assertEquals(dx * cos(angle) - dy * sin(angle) + 128, first.x.toDouble(), 0.01)
            assertEquals(dx * sin(angle) + dy * cos(angle) + 128, first.y.toDouble(), 0.01)
        }
    }

    @Test fun roundedBoxesUseTwelvePointsPerCorner() {
        assertEquals(48, head(0).points.size)
    }

    @Test fun happyEyesAreSeventeenPointStrokes() {
        val happy = botMarks(1, 0).filter { it.stroke > 0f }
        assertEquals(2, happy.size)
        happy.forEach { assertEquals(17, it.points.size); assertEquals(14f, it.stroke) }
    }
}
