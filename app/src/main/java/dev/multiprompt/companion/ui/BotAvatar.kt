package dev.multiprompt.companion.ui

import androidx.compose.foundation.Canvas
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.ui.layout.ContentScale
import coil3.compose.AsyncImage
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.runtime.Composable
import androidx.compose.runtime.remember
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.geometry.CornerRadius
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.drawscope.DrawScope
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp

internal data class BotAppearance(val face: Int, val color: Int) {
    companion object {
        fun forSession(name: String, workspaceColor: Int = 0, face: Int? = null, color: Int? = null): BotAppearance {
            var hash = 2166136261u
            for (byte in name.toByteArray(Charsets.UTF_8)) hash = (hash xor byte.toUByte().toUInt()) * 16777619u
            return BotAppearance(face?.mod(20) ?: (hash % 20u).toInt(), color?.mod(11) ?: workspaceColor)
        }
    }
}

private val botColors = listOf(0xFF95DE69, 0xFFFF6F28, 0xFFFFC455, 0xFF5A9AE9, 0xFF9F6CFC, 0xFFFF667B, 0xFF5EFED1).map { Color(it) }

internal fun botColor(index: Int): Color = botColors[index.mod(botColors.size)]

private enum class BotShape { CIRCLE, SQUARE, SQUIRCLE, WIDE, TALL }

private enum class BotEyes { DOTS, SQUARES, HAPPY, DASHES, ONE, WINK }

private data class BotPreset(val shape: BotShape, val eyes: BotEyes)

private val botPresets = listOf(
    BotPreset(BotShape.CIRCLE, BotEyes.DOTS),
    BotPreset(BotShape.SQUARE, BotEyes.SQUARES),
    BotPreset(BotShape.SQUIRCLE, BotEyes.HAPPY),
    BotPreset(BotShape.WIDE, BotEyes.DASHES),
    BotPreset(BotShape.TALL, BotEyes.ONE),
    BotPreset(BotShape.CIRCLE, BotEyes.WINK),
    BotPreset(BotShape.SQUARE, BotEyes.DOTS),
    BotPreset(BotShape.SQUIRCLE, BotEyes.DASHES),
    BotPreset(BotShape.WIDE, BotEyes.HAPPY),
    BotPreset(BotShape.TALL, BotEyes.DOTS),
    BotPreset(BotShape.CIRCLE, BotEyes.SQUARES),
    BotPreset(BotShape.WIDE, BotEyes.ONE),
)

private val botFacePresets = intArrayOf(0, 1, 2, 3, 4, 5, 6, 7, 8, 9, 10, 11, 0, 1, 2, 3, 4, 5, 6, 7)

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
        Box(modifier.size(diameter).clip(CircleShape).semantics { contentDescription = "Robot $sessionName" }) {
            AsyncImage(
                model = picture,
                contentDescription = null,
                contentScale = ContentScale.Crop,
                modifier = Modifier.fillMaxSize(),
            )
        }
        return
    }
    Canvas(modifier.size(diameter).clip(CircleShape).semantics { contentDescription = "Robot $sessionName" }) {
        drawBotHead(appearance.face, botColor(appearance.color))
    }
}

private fun DrawScope.drawBotHead(face: Int, headColor: Color) {
    val visorColor = Color(0xFF121212)
    val eyeColor = Color(0xFFF2F2F2)
    val preset = botPresets[botFacePresets[face.mod(botFacePresets.size)]]
    val space = minOf(size.width, size.height)
    val (shapeWidth, shapeHeight, shapeRadius) = when (preset.shape) {
        BotShape.CIRCLE -> Triple(1f, 1f, 0.5f)
        BotShape.SQUARE -> Triple(1f, 1f, 0.26f)
        BotShape.SQUIRCLE -> Triple(1f, 1f, 0.38f)
        BotShape.WIDE -> Triple(1f, 0.82f, 0.30f)
        BotShape.TALL -> Triple(0.84f, 1f, 0.42f)
    }
    val headWidth = space * shapeWidth
    val headHeight = space * shapeHeight
    val headLeft = (size.width - headWidth) / 2
    val headTop = (size.height - headHeight) / 2
    val headRadius = minOf(space * shapeRadius, minOf(headWidth, headHeight) / 2)
    drawRoundRect(
        headColor,
        Offset(headLeft, headTop),
        Size(headWidth, headHeight),
        CornerRadius(headRadius),
    )
    val visorWidth = headWidth * 0.72f
    val visorHeight = headHeight * 0.36f
    val visorLeft = headLeft + headWidth * 0.14f
    val visorTop = headTop + headHeight * 0.34f
    drawRoundRect(
        visorColor,
        Offset(visorLeft, visorTop),
        Size(visorWidth, visorHeight),
        CornerRadius(headHeight * 0.18f),
    )
    val centreY = visorTop + visorHeight / 2
    val eyeSize = visorHeight * 0.42f
    if (preset.eyes == BotEyes.ONE) {
        drawBotEye(BotEyes.ONE, visorLeft + visorWidth / 2, centreY, eyeSize, eyeColor)
    } else {
        drawBotEye(if (preset.eyes == BotEyes.WINK) BotEyes.DOTS else preset.eyes, visorLeft + visorWidth * 0.32f, centreY, eyeSize, eyeColor)
        drawBotEye(if (preset.eyes == BotEyes.WINK) BotEyes.DASHES else preset.eyes, visorLeft + visorWidth * 0.68f, centreY, eyeSize, eyeColor)
    }
}

private fun DrawScope.drawBotEye(style: BotEyes, x: Float, y: Float, e: Float, eyeColor: Color) {
    when (style) {
        BotEyes.DOTS -> drawCircle(eyeColor, e / 2, Offset(x, y))
        BotEyes.SQUARES -> drawRoundRect(
            eyeColor,
            Offset(x - e / 2, y - e / 2),
            Size(e, e),
            CornerRadius(e * 0.24f),
        )
        BotEyes.DASHES -> drawRoundRect(
            eyeColor,
            Offset(x - e * 0.7f, y - e * 0.14f),
            Size(e * 1.4f, e * 0.28f),
            CornerRadius(e * 0.14f),
        )
        BotEyes.ONE -> drawRoundRect(
            eyeColor,
            Offset(x - e * 0.6f, y - e * 0.3f),
            Size(e * 1.2f, e * 0.6f),
            CornerRadius(e * 0.3f),
        )
        BotEyes.HAPPY -> drawArc(
            eyeColor,
            180f,
            180f,
            false,
            Offset(x - e * 0.65f, y - e * 0.65f),
            Size(e * 1.3f, e * 1.3f),
            style = Stroke(width = e * 0.24f),
        )
        BotEyes.WINK -> drawCircle(eyeColor, e / 2, Offset(x, y))
    }
}
