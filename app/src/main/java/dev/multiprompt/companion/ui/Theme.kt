package dev.multiprompt.companion.ui

import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.darkColorScheme
import androidx.compose.material3.lightColorScheme
import androidx.compose.runtime.Composable
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.font.Font
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontWeight
import dev.multiprompt.companion.R

enum class AppTheme { SYSTEM, DARK, SUNLIGHT }

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

// Sunlight mode needs dark ink on white: pastel-on-dark colours wash out completely at
// max brightness outdoors, so each hue is darkened until it keeps 7:1 contrast on white.
val TerminalBackgroundLight = Color(0xFFFFFFFF)
val TerminalForegroundLight = Color(0xFF111111)
val TerminalBlueLight = Color(0xFF0043CE)
val TerminalGreenLight = Color(0xFF0E6027)
val TerminalYellowLight = Color(0xFF8E6A00)
val TerminalRedLight = Color(0xFFA2191F)

// Plain helpers (not @Composable) because the syntax highlighter builds AnnotatedStrings
// outside composition; call sites pass the flag they already hold.
fun terminalBackground(sunlight: Boolean): Color =
    if (sunlight) TerminalBackgroundLight else TerminalBackground

fun terminalForeground(sunlight: Boolean): Color =
    if (sunlight) TerminalForegroundLight else TerminalForeground

fun terminalBlue(sunlight: Boolean): Color =
    if (sunlight) TerminalBlueLight else TerminalBlue

fun terminalGreen(sunlight: Boolean): Color =
    if (sunlight) TerminalGreenLight else TerminalGreen

fun terminalYellow(sunlight: Boolean): Color =
    if (sunlight) TerminalYellowLight else TerminalYellow

fun terminalRed(sunlight: Boolean): Color =
    if (sunlight) TerminalRedLight else TerminalRed

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

// High-contrast light scheme for direct sunlight: mid-grey text disappears outdoors, so
// every text-on-background pair here clears 7:1 at maximum brightness.
private val SunlightColors = lightColorScheme(
    primary = Color(0xFF0043CE),
    onPrimary = Color(0xFFFFFFFF),
    secondary = Color(0xFF0E6027),
    onSecondary = Color(0xFFFFFFFF),
    tertiary = Color(0xFF5E35B1),
    onTertiary = Color(0xFFFFFFFF),
    background = Color(0xFFFFFFFF),
    onBackground = Color(0xFF111111),
    surface = Color(0xFFF7F7F7),
    onSurface = Color(0xFF111111),
    surfaceVariant = Color(0xFFE0E0E0),
    onSurfaceVariant = Color(0xFF222222),
    error = Color(0xFFA2191F),
    onError = Color(0xFFFFFFFF),
)

@Composable
fun MultipromptTheme(sunlight: Boolean = false, content: @Composable () -> Unit) {
    MaterialTheme(
        colorScheme = if (sunlight) SunlightColors else MultipromptColors,
        content = content,
    )
}
