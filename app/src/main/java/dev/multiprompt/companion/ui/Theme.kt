package dev.multiprompt.companion.ui

import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.darkColorScheme
import androidx.compose.material3.lightColorScheme
import androidx.compose.runtime.Composable
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.font.Font
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontStyle
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import dev.multiprompt.companion.R

enum class AppTheme { SYSTEM, DARK, SUNLIGHT }

val ReaderFontFamily = FontFamily(
    Font(R.font.ibm_plex_mono_regular, FontWeight.Normal),
)

// Chat View font: the same family as the desktop Chat View (IBM Plex Sans; Segoe UI is only the
// desktop fallback). Bundled so the phone does not depend on a system font.
val ChatFontFamily = FontFamily(
    Font(R.font.ibm_plex_sans_regular, FontWeight.Normal),
    Font(R.font.ibm_plex_sans_italic, FontWeight.Normal, FontStyle.Italic),
    Font(R.font.ibm_plex_sans_bold, FontWeight.Bold),
    Font(R.font.ibm_plex_sans_bold, FontWeight.SemiBold),
)

// Desktop Chat View values (zigshell src/chat_style.zig and main.zig): corner radius 4
// (CHAT_BUBBLE_RADIUS), bubble padding 12 by 10, 8 between paragraphs of one reply, user bubble
// capped at 70 percent of the width, agent bubble spans the column. Fills derive from the page
// colour: dark adds 53 (user) and 36 (agent) per channel, light subtracts 26 and 11.
val ChatBubbleRadius = 4.dp
const val ChatUserMaxWidthFraction = 0.7f
val ChatBubbleUserDark = Color(0xFF353535)
val ChatBubbleAgentDark = Color(0xFF242424)
val ChatBubbleUserLight = Color(0xFFE5E5E5)
val ChatBubbleAgentLight = Color(0xFFF4F4F4)
val ChatTextDark = Color(0xFFFFFFFF)
val ChatTextLight = Color(0xFF1C1C1C)
val ChatHintDark = Color(0xFF787C84)
val ChatHintLight = Color(0xFF8C9098)

val TerminalBackground =Color(0xFF161616)
val TerminalSurface = Color(0xFF1F1F1F)
val TerminalForeground = Color(0xFFF4F4F4)
val TerminalBlue = Color(0xFF78A9FF)
val TerminalGreen = Color(0xFF42BE65)
val TerminalYellow = Color(0xFFF1C21B)
val TerminalRed = Color(0xFFFA4D56)

// Sunlight mode needs dark ink on white: pastel-on-dark colours wash out completely at
// max brightness outdoors, so each hue is darkened until it keeps 7:1 contrast on white.
val TerminalBlueLight = Color(0xFF0043CE)
val TerminalGreenLight = Color(0xFF0E6027)
val TerminalYellowLight = Color(0xFF8E6A00)
val TerminalRedLight = Color(0xFFA2191F)


// Light link blue for text on the dark sunlight-mode prompt bubble; TerminalBlueLight is unreadable on it.
val LinkOnDark = Color(0xFF8AB4F8)

// Plain helpers (not @Composable) because the syntax highlighter builds AnnotatedStrings
// outside composition; call sites pass the flag they already hold.
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
    // Desktop AMOLED theme: pure black page, white text, grey bubbles (agent 242424, user 353535).
    background = Color(0xFF000000),
    onBackground = ChatTextDark,
    surface = Color(0xFF161616),
    onSurface = ChatTextDark,
    surfaceVariant = ChatBubbleAgentDark,
    primaryContainer = ChatBubbleUserDark,
    onPrimaryContainer = ChatTextDark,
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
    surface = Color(0xFFFFFFFF),
    onSurface = Color(0xFF111111),
    surfaceVariant = ChatBubbleAgentLight,
    onSurfaceVariant = Color(0xFF222222),
    primaryContainer = ChatBubbleUserLight,
    onPrimaryContainer = ChatTextLight,
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
