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
import androidx.compose.ui.graphics.drawscope.DrawScope
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.graphics.drawscope.rotate
import androidx.compose.ui.graphics.drawscope.scale
import androidx.compose.ui.graphics.drawscope.translate
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp

internal data class BotAppearance(val face: Int, val color: Int) {
    companion object {
        fun forSession(name: String): BotAppearance {
            var hash = 2166136261u
            for (byte in name.toByteArray(Charsets.UTF_8)) hash = (hash xor byte.toUByte().toUInt()) * 16777619u
            return BotAppearance((hash % 20u).toInt(), ((hash / 20u) % 8u).toInt())
        }
    }
}

private val botColors = listOf(0xFFFFFFFF, 0xFFA9C29A, 0xFFC98F72, 0xFFD4BE94, 0xFF93A9C4, 0xFFB7A6D6, 0xFFD9A0A8, 0xFF9DD3C4).map { Color(it) }

@Composable
internal fun BotAvatar(sessionName: String, diameter: Dp = 48.dp, modifier: Modifier = Modifier) {
    val appearance = remember(sessionName) { BotAppearance.forSession(sessionName) }
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
    val shade = Brush.linearGradient(listOf(head, head.copy(red = head.red * .74f, green = head.green * .74f, blue = head.blue * .74f)), Offset(40f, 52f), Offset(340f, 352f))
    fun DrawScope.rect(color: Color, x: Float, y: Float, w: Float, h: Float, radius: Float) =
        drawRoundRect(color, Offset(x, y), Size(w, h), CornerRadius(radius))
    fun DrawScope.eye(x: Float, happy: Boolean = false) {
        if (happy) drawArc(eyes, 180f, 180f, false, Offset(x - 24f, 132f), Size(48f, 40f), style = Stroke(14f))
        else drawCircle(eyes, 26f, Offset(x, 148f))
    }
    fun DrawScope.headShape() {
        if (face in listOf(7, 15)) drawCircle(shade, 164f, Offset(196f, 214f))
        else drawRoundRect(shade, Offset(40f, 52f), Size(300f, 300f), CornerRadius(if (face == 13) 30f else 80f))
    }
    val zoom = when (face) { 10, 16, 18 -> 1.3f; 11 -> 1.45f; 12 -> 1.32f; 13 -> 1.65f; 14 -> 1.22f; 15 -> 1.38f; 17 -> 1.2f; 19 -> 1.25f; else -> 1f }
    val rotation = when (face) { 12 -> -14f; 17 -> 14f; else -> 0f }
    rotate(rotation, Offset(128f, 128f)) {
        scale(if (face in listOf(8, 9)) -zoom else zoom, zoom, Offset(128f, 128f)) {
            translate(if (face in listOf(10, 11, 14, 15, 18)) -52f else 0f, when (face) { 10, 17 -> 42f; 19 -> -104f; else -> 0f }) {
                if (face == 15) drawArc(grey, 180f, 180f, false, Offset(26f, 26f), Size(310f, 270f), style = Stroke(24f))
                if (face == 14) {
                    drawLine(grey, Offset(110f, 56f), Offset(72f, 0f), 10f)
                    drawLine(grey, Offset(186f, 56f), Offset(226f, 0f), 10f)
                } else if (face !in listOf(3, 13, 19)) {
                    val antennas = if (face in listOf(2, 5, 9)) listOf(106f, 218f) else listOf(136f)
                    antennas.forEach { x ->
                        rect(grey, x - 7f, 20f, 14f, 45f, 7f)
                        drawCircle(head, 18f, Offset(x, 18f))
                    }
                }
                rect(grey, 18f, 124f, 40f, 84f, 12f)
                headShape()
                if (face == 15) rect(grey, 16f, 122f, 40f, 90f, 16f)
                rect(visor, 70f, 96f, 250f, 104f, if (face in listOf(2, 9, 14)) 28f else 52f)
                when (face) {
                    1, 12 -> { eye(136f, true); eye(220f, true) }
                    2, 9 -> { rect(eyes, 112f, 124f, 48f, 48f, 12f); rect(eyes, 196f, 124f, 48f, 48f, 12f) }
                    3, 19 -> { rect(eyes, 108f, 141f, 56f, 14f, 7f); rect(eyes, 192f, 141f, 56f, 14f, 7f) }
                    4 -> rect(eyes, 112f, 122f, 132f, 52f, 26f)
                    5 -> { eye(136f); eye(220f, true) }
                    6 -> listOf(136f, 220f).forEach { x -> drawCircle(eyes, 34f, Offset(x, 148f)); drawCircle(visor, 10f, Offset(x + 8f, 148f)) }
                    16 -> eye(136f)
                    else -> { eye(136f); eye(220f) }
                }
                if (face == 14) rect(visor, 134f, 220f, 96f, 14f, 7f)
                if (face == 18) {
                    rect(visor, 134f, 214f, 96f, 50f, 16f)
                    listOf(151f, 177f, 203f).forEach { x -> rect(eyes, x, 224f, 10f, 30f, 5f) }
                }
            }
        }
    }
}
