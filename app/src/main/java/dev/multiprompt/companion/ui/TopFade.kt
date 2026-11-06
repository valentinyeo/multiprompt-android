package dev.multiprompt.companion.ui

import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ColumnScope
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.statusBarsPadding
import androidx.compose.material3.MaterialTheme
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.drawBehind
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.unit.dp

@Composable
internal fun TopFade(content: @Composable ColumnScope.() -> Unit) {
    val background = MaterialTheme.colorScheme.background
    Column(
        Modifier.fillMaxWidth().testTag("top-fade").drawBehind {
            val height = size.height + 24.dp.toPx()
            val solid = (size.height / height).coerceIn(0.01f, 0.99f)
            val fade = 1f - solid
            drawRect(
                brush = Brush.verticalGradient(
                    0f to background,
                    solid to background,
                    solid + fade * 0.25f to background.copy(alpha = 0.55f),
                    solid + fade * 0.5f to background.copy(alpha = 0.25f),
                    1f to Color.Transparent,
                    endY = height,
                ),
                size = Size(size.width, height),
            )
        }.statusBarsPadding(),
        content = content,
    )
}
