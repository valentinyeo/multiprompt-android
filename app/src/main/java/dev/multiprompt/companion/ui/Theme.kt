package dev.multiprompt.companion.ui

import android.os.Build
import androidx.compose.material3.LocalContentColor
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Shapes
import androidx.compose.material3.Typography
import androidx.compose.material3.dynamicDarkColorScheme
import androidx.compose.material3.dynamicLightColorScheme
import androidx.compose.material3.darkColorScheme
import androidx.compose.material3.lightColorScheme
import androidx.compose.runtime.Composable
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.runtime.staticCompositionLocalOf
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.graphics.Shape
import androidx.compose.ui.unit.TextUnit
import dev.multiprompt.companion.BuildConfig
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

val ChatFontFamily = FontFamily(
    Font(R.font.ibm_plex_sans_regular, FontWeight.Normal),
    Font(R.font.ibm_plex_sans_italic, FontWeight.Normal, FontStyle.Italic),
    Font(R.font.ibm_plex_sans_bold, FontWeight.Bold),
    Font(R.font.ibm_plex_sans_bold, FontWeight.SemiBold),
)

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

val TerminalBlueLight = Color(0xFF0043CE)
val TerminalGreenLight = Color(0xFF0E6027)
val TerminalYellowLight = Color(0xFF8E6A00)
val TerminalRedLight = Color(0xFFA2191F)


val LinkOnDark = Color(0xFF8AB4F8)

fun terminalBlue(sunlight: Boolean): Color =
    if (sunlight) TerminalBlueLight else TerminalBlue

fun terminalGreen(sunlight: Boolean): Color =
    if (sunlight) TerminalGreenLight else TerminalGreen

fun terminalYellow(sunlight: Boolean): Color =
    if (sunlight) TerminalYellowLight else TerminalYellow

fun terminalRed(sunlight: Boolean): Color =
    if (sunlight) TerminalRedLight else TerminalRed

internal val MultipromptColors = darkColorScheme(
    primary = Color(0xFF7DD3FC),
    onPrimary = Color(0xFF082F49),
    secondary = Color(0xFFA7F3D0),
    onSecondary = Color(0xFF064E3B),
    tertiary = Color(0xFFC4B5FD),
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

internal val SunlightColors = lightColorScheme(
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

internal val Material3LightColors = lightColorScheme(
    primary = Color(0xFF4D5C92),
    onPrimary = Color(0xFFFFFFFF),
    primaryContainer = Color(0xFFDCE1FF),
    onPrimaryContainer = Color(0xFF354479),
    inversePrimary = Color(0xFFB7C4FF),
    secondary = Color(0xFF595D72),
    onSecondary = Color(0xFFFFFFFF),
    secondaryContainer = Color(0xFFDEE1F9),
    onSecondaryContainer = Color(0xFF424659),
    tertiary = Color(0xFF75546F),
    onTertiary = Color(0xFFFFFFFF),
    tertiaryContainer = Color(0xFFFFD7F5),
    onTertiaryContainer = Color(0xFF5B3D57),
    background = Color(0xFFFAF8FF),
    onBackground = Color(0xFF1A1B21),
    surface = Color(0xFFFAF8FF),
    onSurface = Color(0xFF1A1B21),
    surfaceVariant = Color(0xFFE2E1EC),
    onSurfaceVariant = Color(0xFF45464F),
    surfaceTint = Color(0xFF4D5C92),
    inverseSurface = Color(0xFF2F3036),
    inverseOnSurface = Color(0xFFF1F0F7),
    error = Color(0xFFBA1A1A),
    onError = Color(0xFFFFFFFF),
    errorContainer = Color(0xFFFFDAD6),
    onErrorContainer = Color(0xFF93000A),
    outline = Color(0xFF767680),
    outlineVariant = Color(0xFFC6C5D0),
    scrim = Color(0xFF000000),
    surfaceBright = Color(0xFFFAF8FF),
    surfaceDim = Color(0xFFDAD9E0),
    surfaceContainer = Color(0xFFEFEDF4),
    surfaceContainerHigh = Color(0xFFE9E7EF),
    surfaceContainerHighest = Color(0xFFE3E1E9),
    surfaceContainerLow = Color(0xFFF4F2FA),
    surfaceContainerLowest = Color(0xFFFFFFFF),
    primaryFixed = Color(0xFFDCE1FF),
    primaryFixedDim = Color(0xFFB7C4FF),
    onPrimaryFixed = Color(0xFF04164B),
    onPrimaryFixedVariant = Color(0xFF354479),
    secondaryFixed = Color(0xFFDEE1F9),
    secondaryFixedDim = Color(0xFFC2C5DD),
    onSecondaryFixed = Color(0xFF161B2C),
    onSecondaryFixedVariant = Color(0xFF424659),
    tertiaryFixed = Color(0xFFFFD7F5),
    tertiaryFixedDim = Color(0xFFE3BADA),
    onTertiaryFixed = Color(0xFF2C1229),
    onTertiaryFixedVariant = Color(0xFF5B3D57),
)

internal val Material3DarkColors = darkColorScheme(
    primary = Color(0xFFB7C4FF),
    onPrimary = Color(0xFF1E2D61),
    primaryContainer = Color(0xFF354479),
    onPrimaryContainer = Color(0xFFDCE1FF),
    inversePrimary = Color(0xFF4D5C92),
    secondary = Color(0xFFC2C5DD),
    onSecondary = Color(0xFF2B3042),
    secondaryContainer = Color(0xFF424659),
    onSecondaryContainer = Color(0xFFDEE1F9),
    tertiary = Color(0xFFE3BADA),
    onTertiary = Color(0xFF43273F),
    tertiaryContainer = Color(0xFF5B3D57),
    onTertiaryContainer = Color(0xFFFFD7F5),
    background = Color(0xFF121318),
    onBackground = Color(0xFFE3E1E9),
    surface = Color(0xFF121318),
    onSurface = Color(0xFFE3E1E9),
    surfaceVariant = Color(0xFF45464F),
    onSurfaceVariant = Color(0xFFC6C5D0),
    surfaceTint = Color(0xFFB7C4FF),
    inverseSurface = Color(0xFFE3E1E9),
    inverseOnSurface = Color(0xFF2F3036),
    error = Color(0xFFFFB4AB),
    onError = Color(0xFF690005),
    errorContainer = Color(0xFF93000A),
    onErrorContainer = Color(0xFFFFDAD6),
    outline = Color(0xFF90909A),
    outlineVariant = Color(0xFF45464F),
    scrim = Color(0xFF000000),
    surfaceBright = Color(0xFF38393F),
    surfaceDim = Color(0xFF121318),
    surfaceContainer = Color(0xFF1E1F25),
    surfaceContainerHigh = Color(0xFF292A2F),
    surfaceContainerHighest = Color(0xFF34343A),
    surfaceContainerLow = Color(0xFF1A1B21),
    surfaceContainerLowest = Color(0xFF0D0E13),
    primaryFixed = Color(0xFFDCE1FF),
    primaryFixedDim = Color(0xFFB7C4FF),
    onPrimaryFixed = Color(0xFF04164B),
    onPrimaryFixedVariant = Color(0xFF354479),
    secondaryFixed = Color(0xFFDEE1F9),
    secondaryFixedDim = Color(0xFFC2C5DD),
    onSecondaryFixed = Color(0xFF161B2C),
    onSecondaryFixedVariant = Color(0xFF424659),
    tertiaryFixed = Color(0xFFFFD7F5),
    tertiaryFixedDim = Color(0xFFE3BADA),
    onTertiaryFixed = Color(0xFF2C1229),
    onTertiaryFixedVariant = Color(0xFF5B3D57),
)

val LocalMaterial3 = staticCompositionLocalOf { BuildConfig.MATERIAL3 }

@Composable
fun MultipromptTheme(
    sunlight: Boolean = false,
    material3: Boolean = BuildConfig.MATERIAL3,
    content: @Composable () -> Unit,
) {
    val colors = when {
        !material3 -> if (sunlight) SunlightColors else MultipromptColors
        Build.VERSION.SDK_INT >= 31 -> if (sunlight) dynamicLightColorScheme(LocalContext.current) else {
            dynamicDarkColorScheme(LocalContext.current)
        }
        sunlight -> Material3LightColors
        else -> Material3DarkColors
    }
    CompositionLocalProvider(
        LocalMaterial3 provides material3,
        LocalContentColor provides if (material3) colors.onBackground else LocalContentColor.current,
    ) {
        MaterialTheme(
            colorScheme = colors,
            typography = if (material3) Typography() else MaterialTheme.typography,
            shapes = if (material3) Shapes() else MaterialTheme.shapes,
            content = content,
        )
    }
}

@Composable
internal fun chatFontFamily(): FontFamily = if (LocalMaterial3.current) FontFamily.Default else ChatFontFamily

@Composable
internal fun chromeShape(classic: Shape): Shape = if (LocalMaterial3.current) MaterialTheme.shapes.small else classic

@Composable
internal fun chatShape(): Shape = if (LocalMaterial3.current) MaterialTheme.shapes.medium else {
    androidx.compose.foundation.shape.RoundedCornerShape(ChatBubbleRadius)
}

@Composable
internal fun chromeFontSize(classic: TextUnit): TextUnit =
    if (LocalMaterial3.current) MaterialTheme.typography.labelSmall.fontSize else classic

@Composable
internal fun chromeLineHeight(classic: TextUnit): TextUnit =
    if (LocalMaterial3.current) MaterialTheme.typography.labelSmall.lineHeight else classic

@Composable
internal fun chatFontSize(classic: TextUnit, scale: Float = 1f): TextUnit =
    if (LocalMaterial3.current) MaterialTheme.typography.bodyLarge.fontSize * scale else classic

@Composable
internal fun chatLineHeight(classic: TextUnit, scale: Float = 1f): TextUnit =
    if (LocalMaterial3.current) MaterialTheme.typography.bodyLarge.lineHeight * scale else classic

@Composable
internal fun chatLinkColor(classic: Color): Color = if (LocalMaterial3.current) MaterialTheme.colorScheme.primary else classic
