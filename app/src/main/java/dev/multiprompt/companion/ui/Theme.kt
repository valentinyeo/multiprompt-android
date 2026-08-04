package dev.multiprompt.companion.ui

import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.darkColorScheme
import androidx.compose.runtime.Composable
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.font.Font
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontWeight
import dev.multiprompt.companion.R

val ReaderFontFamily = FontFamily(
    Font(R.font.ibm_plex_mono_regular, FontWeight.Normal),
)

val TerminalBackground = Color(0xFF161616)
val TerminalSurface = Color(0xFF1F1F1F)
val TerminalForeground = Color(0xFFF4F4F4)
val TerminalBlue = Color(0xFF78A9FF)
val TerminalGreen = Color(0xFF42BE65)
val TerminalYellow = Color(0xFFF1C21B)
val TerminalRed = Color(0xFFFA4D56)

private val MultipromptColors = darkColorScheme(
    primary = Color(0xFF7DD3FC),
    onPrimary = Color(0xFF082F49),
    secondary = Color(0xFFA7F3D0),
    onSecondary = Color(0xFF064E3B),
    tertiary = Color(0xFFC4B5FD),
    background = Color(0xFF090B10),
    onBackground = Color(0xFFE5E7EB),
    surface = Color(0xFF11151D),
    onSurface = Color(0xFFE5E7EB),
    surfaceVariant = Color(0xFF1A2030),
    onSurfaceVariant = Color(0xFFAFB8C8),
    error = Color(0xFFFCA5A5),
)

@Composable
fun MultipromptTheme(content: @Composable () -> Unit) {
    MaterialTheme(colorScheme = MultipromptColors, content = content)
}
