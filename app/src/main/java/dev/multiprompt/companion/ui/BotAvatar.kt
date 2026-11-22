package dev.multiprompt.companion.ui

import androidx.compose.foundation.Canvas
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.runtime.Composable
import androidx.compose.runtime.remember
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.Path
import androidx.compose.ui.graphics.StrokeCap
import androidx.compose.ui.graphics.StrokeJoin
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import coil3.compose.AsyncImage
import kotlin.math.PI
import kotlin.math.cos
import kotlin.math.sin

internal data class BotAppearance(val face: Int, val color: Int) {
    companion object {
        fun forSession(name: String, workspaceColor: Int = 0, face: Int? = null, color: Int? = null): BotAppearance {
            var hash = 2166136261u
            for (byte in name.toByteArray(Charsets.UTF_8)) hash = (hash xor byte.toUByte().toUInt()) * 16777619u
            return BotAppearance(face?.mod(BOT_FACES) ?: (hash % BOT_FACES.toUInt()).toInt(), (color ?: workspaceColor).mod(BOT_COLORS))
        }
    }
}

internal const val BOT_FACES = 20
internal const val BOT_COLORS = 8

private val botColors = longArrayOf(0xFF8FCB6B, 0xFFE9875A, 0xFFE8C65A, 0xFF6F9FE0, 0xFFA77FE0, 0xFFE77B98, 0xFF5CCFC0, 0xFFEDEDED)

internal fun botColor(index: Int): Color = Color(botColors[index.mod(BOT_COLORS)])

internal fun botPaletteColor(index: Int?): Int? = index?.takeIf { it in 0 until BOT_COLORS }

internal val BotTileShape = RoundedCornerShape(percent = 22)

private const val GREY = 0xFF9A9A9AL
private const val VISOR = 0xFF121212L
private const val EYES = 0xFFF2F2F2L
private const val TILE = 0xFF1E1E1EL

internal class BotMark(val points: List<Offset>, val color: Long, val stroke: Float = 0f, val shade: Long? = null)

private data class BotBox(val x: Float, val y: Float, val width: Float, val height: Float, val radius: Float)

private class BotPainter(private val face: Int) {
    val marks = mutableListOf<BotMark>()

    fun point(x: Float, y: Float): Offset {
        val mirrored = if (face == 8 || face == 9) 256 - x else x
        val angle = when (face) {
            12 -> -14.0 * PI / 180
            17 -> 14.0 * PI / 180
            else -> 0.0
        }
        val dx = mirrored - 128
        val dy = y - 128
        return Offset(
            (dx * cos(angle) - dy * sin(angle) + 128).toFloat(),
            (dx * sin(angle) + dy * cos(angle) + 128).toFloat(),
        )
    }

    fun rounded(bounds: BotBox, color: Long, gradient: Boolean = false) {
        val radius = minOf(bounds.radius, minOf(bounds.width, bounds.height) / 2)
        val points = (0 until 4).flatMap { corner ->
            val cx = bounds.x + if (corner == 0 || corner == 3) radius else bounds.width - radius
            val cy = bounds.y + if (corner < 2) radius else bounds.height - radius
            (0 until 12).map { step ->
                val angle = (corner * 90 + 180 + step * 90f / 11) * PI / 180
                point((cx + radius * cos(angle)).toFloat(), (cy + radius * sin(angle)).toFloat())
            }
        }
        marks += BotMark(points, color, shade = if (gradient) botShade(color) else null)
    }

    fun box(color: Long, x: Float, y: Float, width: Float, height: Float, radius: Float) =
        rounded(BotBox(x, y, width, height, radius), color)

    fun eye(x: Float, y: Float, radius: Float, happy: Boolean) {
        if (!happy) return box(EYES, x - radius, y - radius, radius * 2, radius * 2, radius)
        val points = (0..16).map { i ->
            val angle = (180 + i * 180f / 16) * PI / 180
            point((x + radius * cos(angle)).toFloat(), (y + radius * 0.35f + radius * 0.85f * sin(angle)).toFloat())
        }
        marks += BotMark(points, EYES, stroke = 14f)
    }
}

private fun botShade(color: Long): Long {
    val r = ((color shr 16) and 255) * 74 / 100
    val g = ((color shr 8) and 255) * 74 / 100
    val b = (color and 255) * 74 / 100
    return 0xFF000000L or (r shl 16) or (g shl 8) or b
}

internal fun botMarks(face: Int, color: Int): List<BotMark> {
    val f = face.mod(BOT_FACES)
    val head = botColors[color.mod(BOT_COLORS)]
    val p = BotPainter(f)
    val hb = when (f) {
        10 -> BotBox(-4f, 70f, 264f, 220f, 80f)
        11 -> BotBox(12f, 60f, 232f, 224f, 68f)
        12 -> BotBox(8f, 62f, 248f, 224f, 70f)
        13 -> BotBox(-10f, -20f, 280f, 300f, 70f)
        14 -> BotBox(0f, 74f, 256f, 176f, 44f)
        15 -> BotBox(-2f, 28f, 260f, 260f, 130f)
        16 -> BotBox(48f, 32f, 260f, 280f, 100f)
        17 -> BotBox(-60f, 68f, 280f, 280f, 90f)
        18 -> BotBox(12f, 50f, 232f, 240f, 58f)
        19 -> BotBox(-20f, -100f, 300f, 290f, 60f)
        7 -> BotBox(32f, 50f, 328f, 328f, 164f)
        else -> BotBox(40f, 52f, 300f, 300f, 80f)
    }
    val vb = when (f) {
        10 -> BotBox(26f, 116f, 228f, 100f, 50f)
        11 -> BotBox(40f, 104f, 192f, 94f, 47f)
        12 -> BotBox(40f, 106f, 200f, 100f, 50f)
        13 -> BotBox(-10f, 70f, 280f, 120f, 60f)
        14 -> BotBox(20f, 98f, 216f, 122f, 36f)
        15 -> BotBox(40f, 110f, 176f, 96f, 48f)
        16 -> BotBox(-10f, 96f, 176f, 100f, 50f)
        17 -> BotBox(-35f, 114f, 218f, 100f, 50f)
        18 -> BotBox(40f, 84f, 180f, 90f, 45f)
        19 -> BotBox(44f, 62f, 270f, 100f, 50f)
        else -> BotBox(70f, 96f, 250f, 104f, if (f == 2 || f == 9) 28f else 52f)
    }
    if (f == 15) p.box(GREY, -10f, 10f, 276f, 280f, 138f)
    when {
        f == 14 -> {
            p.marks += BotMark(listOf(p.point(48f, 0f), p.point(92f, 78f)), GREY, stroke = 12f)
            p.marks += BotMark(listOf(p.point(210f, 0f), p.point(164f, 78f)), GREY, stroke = 12f)
            p.box(head, 34f, -14f, 28f, 28f, 14f)
            p.box(head, 196f, -14f, 28f, 28f, 14f)
        }
        f == 6 -> p.marks += BotMark(
            listOf(p.point(144f, 4f), p.point(112f, 54f), p.point(132f, 54f), p.point(120f, 82f), p.point(160f, 28f), p.point(140f, 28f)),
            head,
        )
        f != 3 && f != 13 && f != 15 && f != 19 -> {
            val twin = f == 2 || f == 5 || f == 9
            val antennas = if (twin) listOf(106f, 218f) else listOf(if (f == 11) 128f else 136f)
            antennas.forEach { x ->
                p.box(GREY, x - 7, 0f, 14f, hb.y + 8, 7f)
                p.box(if ((f == 2 || f == 5) && x == 218f) EYES else head, x - 18, 0f, 36f, 36f, 18f)
            }
        }
    }
    p.box(GREY, if (f < 10) 18f else hb.x - 12, hb.y + 72, 40f, 84f, 12f)
    p.rounded(hb, head, gradient = true)
    if (f == 15) p.box(GREY, 0f, 122f, 18f, 74f, 9f)
    p.rounded(vb, VISOR)
    val y = vb.y + vb.height / 2
    val left = vb.x + vb.width * 0.264f
    val right = vb.x + vb.width * 0.6f
    val radius = when (f) {
        11, 14, 15, 18 -> 20f
        13 -> 34f
        else -> 26f
    }
    when (f) {
        1, 12 -> {
            p.eye(left, y, radius, true)
            p.eye(right, y, radius, true)
        }
        2, 9 -> {
            p.box(EYES, left - 24, y - 24, 48f, 48f, 12f)
            p.box(EYES, right - 24, y - 24, 48f, 48f, 12f)
        }
        3, 19 -> {
            p.box(EYES, left - 28, y - 7, 56f, 14f, 7f)
            p.box(EYES, right - 28, y - 7, 56f, 14f, 7f)
        }
        4 -> p.box(EYES, 112f, 122f, 132f, 52f, 26f)
        5 -> {
            p.eye(left, y, radius, false)
            p.eye(right, y, radius, true)
        }
        6 -> listOf(left, right).forEach { x ->
            p.eye(x, y, 34f, false)
            p.box(VISOR, x - 2, y - 10, 20f, 20f, 10f)
        }
        16 -> p.eye(84f, y, 28f, false)
        else -> {
            p.eye(left, y, radius, false)
            p.eye(right, y, radius, false)
        }
    }
    if (f == 14) p.box(EYES, 112f, 186f, 30f, 8f, 4f)
    if (f == 18) {
        p.box(VISOR, 84f, 196f, 92f, 50f, 16f)
        listOf(101f, 126f, 151f).forEach { x -> p.box(EYES, x, 206f, 10f, 30f, 5f) }
    }
    return p.marks
}

@Composable
internal fun BotAvatar(
    sessionName: String,
    diameter: Dp = 48.dp,
    modifier: Modifier = Modifier,
    workspaceColor: Int = 0,
    face: Int? = null,
    color: Int? = null,
    picture: String? = null,
) {
    val appearance = remember(sessionName, workspaceColor, face, color) { BotAppearance.forSession(sessionName, workspaceColor, face, color) }
    if (picture != null) {
        Box(modifier.size(diameter).clip(BotTileShape).semantics { contentDescription = "Robot $sessionName" }) {
            AsyncImage(
                model = picture,
                contentDescription = null,
                contentScale = ContentScale.Crop,
                modifier = Modifier.fillMaxSize(),
            )
        }
        return
    }
    val marks = remember(appearance) { botMarks(appearance.face, appearance.color) }
    val start = remember(appearance) { BotPainter(appearance.face).point(0f, 0f) }
    val end = remember(appearance) { BotPainter(appearance.face).point(256f, 256f) }
    Canvas(modifier.size(diameter).clip(BotTileShape).semantics { contentDescription = "Robot $sessionName" }) {
        drawRect(Color(TILE))
        val scale = minOf(size.width, size.height) / 256f
        marks.forEach { mark ->
            val path = Path().apply {
                mark.points.forEachIndexed { index, point ->
                    if (index == 0) moveTo(point.x * scale, point.y * scale) else lineTo(point.x * scale, point.y * scale)
                }
                if (mark.stroke == 0f) close()
            }
            when {
                mark.stroke > 0f -> drawPath(path, Color(mark.color), style = Stroke(width = maxOf(1f, mark.stroke * scale), cap = StrokeCap.Round, join = StrokeJoin.Round))
                mark.shade != null -> drawPath(path, Brush.linearGradient(listOf(Color(mark.color), Color(mark.shade)), start * scale, end * scale))
                else -> drawPath(path, Color(mark.color))
            }
        }
    }
}
