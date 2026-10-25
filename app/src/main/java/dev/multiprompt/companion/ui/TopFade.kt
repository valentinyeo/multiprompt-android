package dev.multiprompt.companion.ui

import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ColumnScope
import androidx.compose.foundation.layout.WindowInsets
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.statusBars
import androidx.compose.foundation.layout.statusBarsPadding
import androidx.compose.material3.MaterialTheme
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.drawBehind
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.luminance
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.unit.dp

@Composable
internal fun TopFade(content: @Composable ColumnScope.() -> Unit) {
    val background = MaterialTheme.colorScheme.background
    val statusColor = if (background.luminance() > 0.5f) ReaderBackground else background
    val statusHeight = WindowInsets.statusBars.getTop(LocalDensity.current).toFloat()
    Column(
        Modifier.fillMaxWidth().testTag("top-fade").drawBehind {
            val height = size.height + 24.dp.toPx()
            drawRect(
                brush = Brush.verticalGradient(
                    0f to statusColor,
                    (statusHeight / height).coerceIn(0.01f, 0.5f) to statusColor,
                    0.65f to background.copy(alpha = 0.95f),
                    1f to Color.Transparent,
                    endY = height,
                ),
                size = Size(size.width, height),
            )
        }.statusBarsPadding(),
        content = content,
    )
}
