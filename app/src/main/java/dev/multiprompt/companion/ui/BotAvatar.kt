package dev.multiprompt.companion.ui

import androidx.compose.foundation.Canvas
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.runtime.Composable
import androidx.compose.runtime.remember
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.geometry.CornerRadius
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.Path
import androidx.compose.ui.graphics.drawscope.DrawScope
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.graphics.drawscope.rotate
import androidx.compose.ui.graphics.drawscope.scale
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp

internal data class BotAppearance(val face: Int, val color: Int) {
    companion object {
        fun forSession(name: String, workspaceColor: Int = 0): BotAppearance {
            var hash = 2166136261u
            for (byte in name.toByteArray(Charsets.UTF_8)) hash = (hash xor byte.toUByte().toUInt()) * 16777619u
            val color = workspaceColor.mod(7)
            return BotAppearance((hash % 20u).toInt(), color)
        }
    }
}

internal val botColors = listOf(0xFFA9C29A, 0xFFC98F72, 0xFFD4BE94, 0xFF93A9C4, 0xFFB7A6D6, 0xFFD9A0A8, 0xFF9DD3C4).map { Color(it) }

@Composable
internal fun BotAvatar(sessionName: String, diameter: Dp = 48.dp, modifier: Modifier = Modifier, workspaceColor: Int = 0) {
    val appearance = remember(sessionName, workspaceColor) { BotAppearance.forSession(sessionName, workspaceColor) }
    Canvas(modifier.size(diameter).clip(CircleShape).semantics { contentDescription = "Robot $sessionName" }) {
        drawCircle(Color(0xFF1E1E1E))
        scale(size.width / 256f, size.height / 256f, Offset.Zero) {
            robot(appearance.face, botColors[appearance.color])
        }
    }
}

private fun DrawScope.robot(face: Int, head: Color) {
    val grey = Color(0xFF9A9A9A)
    val visor = Color(0xFF121212)
    val eyes = Color(0xFFF2F2F2)
    val shade = Brush.linearGradient(listOf(head, head.copy(red = head.red * .74f, green = head.green * .74f, blue = head.blue * .74f)), Offset.Zero, Offset(256f, 256f))
    fun DrawScope.rect(color: Color, x: Float, y: Float, w: Float, h: Float, radius: Float) =
        drawRoundRect(color, Offset(x, y), Size(w, h), CornerRadius(radius))
    fun DrawScope.eye(x: Float, y: Float, radius: Float, happy: Boolean = false) {
        if (happy) drawArc(eyes, 180f, 180f, false, Offset(x - radius, y - radius / 2), Size(radius * 2, radius * 1.7f), style = Stroke(14f))
        else drawCircle(eyes, radius, Offset(x, y))
    }
    val headBounds = when (face) {
        10 -> listOf(-4f, 70f, 264f, 220f, 80f)
        11 -> listOf(12f, 60f, 232f, 224f, 68f)
        12 -> listOf(8f, 62f, 248f, 224f, 70f)
        13 -> listOf(-10f, -20f, 280f, 300f, 70f)
        14 -> listOf(0f, 74f, 256f, 176f, 44f)
        15 -> listOf(-2f, 28f, 260f, 260f, 130f)
        16 -> listOf(48f, 32f, 260f, 280f, 100f)
        17 -> listOf(-60f, 68f, 280f, 280f, 90f)
        18 -> listOf(12f, 50f, 232f, 240f, 58f)
        19 -> listOf(-20f, -100f, 300f, 290f, 60f)
        else -> listOf(40f, 52f, 300f, 300f, 80f)
    }
    val visorBounds = when (face) {
        10 -> listOf(26f, 116f, 228f, 100f, 50f)
        11 -> listOf(40f, 104f, 192f, 94f, 47f)
        12 -> listOf(40f, 106f, 200f, 100f, 50f)
        13 -> listOf(-10f, 70f, 280f, 120f, 60f)
        14 -> listOf(20f, 98f, 216f, 122f, 36f)
        15 -> listOf(40f, 110f, 176f, 96f, 48f)
        16 -> listOf(-10f, 96f, 176f, 100f, 50f)
        17 -> listOf(-35f, 114f, 218f, 100f, 50f)
        18 -> listOf(40f, 84f, 180f, 90f, 45f)
        19 -> listOf(44f, 62f, 270f, 100f, 50f)
        else -> listOf(70f, 96f, 250f, 104f, if (face in listOf(2, 9)) 28f else 52f)
    }
    rotate(when (face) { 12 -> -14f; 17 -> 14f; else -> 0f }, Offset(128f, 128f)) {
        scale(if (face in listOf(8, 9)) -1f else 1f, 1f, Offset(128f, 128f)) {
            if (face == 15) drawArc(grey, 180f, 180f, false, Offset(-10f, 10f), Size(276f, 280f), style = Stroke(20f))
            if (face == 14) {
                drawLine(grey, Offset(92f, 78f), Offset(48f, 0f), 12f)
                drawLine(grey, Offset(164f, 78f), Offset(210f, 0f), 12f)
                drawCircle(head, 14f, Offset(48f, 0f))
                drawCircle(head, 14f, Offset(210f, 0f))
            } else if (face == 6) {
                drawPath(Path().apply { moveTo(144f, 4f); lineTo(112f, 54f); lineTo(132f, 54f); lineTo(120f, 82f); lineTo(160f, 28f); lineTo(140f, 28f); close() }, head)
            } else if (face !in listOf(3, 13, 15, 19)) {
                val antennas = if (face in listOf(2, 5, 9)) listOf(106f, 218f) else listOf(if (face == 11) 128f else 136f)
                antennas.forEach { x ->
                    rect(grey, x - 7f, 0f, 14f, headBounds[1] + 8f, 7f)
                    drawCircle(head, 18f, Offset(x, 18f))
                }
            }
            val earX = if (face < 10) 18f else headBounds[0] - 12f
            rect(grey, earX, headBounds[1] + 72f, 40f, 84f, 12f)
            if (face == 7) drawCircle(shade, 164f, Offset(196f, 214f))
            else drawRoundRect(shade, Offset(headBounds[0], headBounds[1]), Size(headBounds[2], headBounds[3]), CornerRadius(headBounds[4]))
            if (face == 15) rect(grey, 0f, 122f, 18f, 74f, 9f)
            rect(visor, visorBounds[0], visorBounds[1], visorBounds[2], visorBounds[3], visorBounds[4])
            val y = visorBounds[1] + visorBounds[3] / 2
            val left = visorBounds[0] + visorBounds[2] * .264f
            val right = visorBounds[0] + visorBounds[2] * .6f
            val radius = when (face) { 11, 14, 15, 18 -> 20f; 13 -> 34f; else -> 26f }
            when (face) {
                1, 12 -> { eye(left, y, radius, true); eye(right, y, radius, true) }
                2, 9 -> { rect(eyes, left - 24f, y - 24f, 48f, 48f, 12f); rect(eyes, right - 24f, y - 24f, 48f, 48f, 12f) }
                3, 19 -> { rect(eyes, left - 28f, y - 7f, 56f, 14f, 7f); rect(eyes, right - 28f, y - 7f, 56f, 14f, 7f) }
                4 -> rect(eyes, 112f, 122f, 132f, 52f, 26f)
                5 -> { eye(left, y, radius); eye(right, y, radius, true) }
                6 -> listOf(left, right).forEach { x -> drawCircle(eyes, 34f, Offset(x, y)); drawCircle(visor, 10f, Offset(x + 8f, y)) }
                16 -> eye(84f, y, 28f)
                else -> { eye(left, y, radius); eye(right, y, radius) }
            }
            if (face == 14) rect(eyes, 112f, 186f, 30f, 8f, 4f)
            if (face == 18) {
                rect(visor, 84f, 196f, 92f, 50f, 16f)
                listOf(101f, 126f, 151f).forEach { x -> rect(eyes, x, 206f, 10f, 30f, 5f) }
            }
        }
    }
}
