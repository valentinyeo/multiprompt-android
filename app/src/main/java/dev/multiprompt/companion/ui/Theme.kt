package dev.multiprompt.companion.ui

import androidx.compose.material3.LocalContentColor
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Shapes
import androidx.compose.material3.Typography
import androidx.compose.material3.darkColorScheme
import androidx.compose.material3.lightColorScheme
import androidx.compose.runtime.remember
import androidx.compose.ui.text.googlefonts.isAvailableOnDevice
import androidx.compose.ui.text.googlefonts.GoogleFont
import androidx.compose.ui.text.googlefonts.Font as GoogleFontFont
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
import androidx.compose.ui.unit.sp
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

internal val ReaderBackground = Color(0xFF101010)
internal val ReaderCard = Color(0xFF1E1E1E)
internal val ReaderUserCard = Color(0xFF3A3A3A)
internal val ReaderControl = Color(0xFF1F1F1F)
internal val ReaderOutline = Color(0xFF2E2E2E)
internal val ReaderOutlineWidth = 1.dp
internal val ReaderMenuShape = androidx.compose.foundation.shape.RoundedCornerShape(20.dp)
internal val ReaderForeground = Color(0xFFF2F2F2)
internal val ReaderHint = Color(0xFF8A8A8A)
internal val ReaderChoiceBlue = Color(0xFF89B4F8)
internal val ReaderChoiceBackground = Color(0xFF156FD1)
internal val ReaderBadgeForeground = Color(0xFFFFFFFF)
internal val ReaderBadgeShape = androidx.compose.foundation.shape.RoundedCornerShape(6.dp)
internal val ReaderCardShape = androidx.compose.foundation.shape.RoundedCornerShape(20.dp)
internal val ReaderComposerShape = androidx.compose.foundation.shape.RoundedCornerShape(28.dp)
internal val ReaderCardPadding = 16.dp
internal val ReaderScreenPadding = 12.dp
internal val ReaderMessageSpacing = 6.dp
internal val ReaderControlSize = 48.dp
internal val ReaderControlSpacing = 8.dp
internal val ReaderComposerHeight = 56.dp
internal val ReaderSendSize = 40.dp
internal val ReaderJumpSize = 40.dp
internal val ReaderFloatingInset = 72.dp
internal val ReaderTextSize = 16.sp
internal val ReaderLineHeight = 26.sp
internal val ReaderMetaTextSize = 12.sp
internal val ReaderHeadingTextSize = 20.sp
internal val ReaderHeadingLineHeight = 30.sp
internal val ReaderCodeTextSize = 14.sp
internal val ReaderCodeLineHeight = 22.sp
internal val ReaderBadgeSize = 24.dp
internal val ReaderBadgeTextSize = 12.sp
internal val ReaderBadgeGap = 8.dp

val ChatBubbleRadius = 4.dp
const val ChatUserMaxWidthFraction = 0.7f
const val ChatAgentMaxWidthFraction = 0.85f
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
    primary = Color(0xFF8F4C27),
    onPrimary = Color(0xFFFFFFFF),
    primaryContainer = Color(0xFFFFDBCA),
    onPrimaryContainer = Color(0xFF351000),
    inversePrimary = Color(0xFFFFB68E),
    secondary = Color(0xFF755845),
    onSecondary = Color(0xFFFFFFFF),
    secondaryContainer = Color(0xFFFFDBCA),
    onSecondaryContainer = Color(0xFF2C160B),
    tertiary = Color(0xFF5F6135),
    onTertiary = Color(0xFFFFFFFF),
    tertiaryContainer = Color(0xFFE8E6B0),
    onTertiaryContainer = Color(0xFF1D1D00),
    background = Color(0xFFFFF8F5),
    onBackground = Color(0xFF221A15),
    surface = Color(0xFFFFF8F5),
    onSurface = Color(0xFF221A15),
    surfaceVariant = Color(0xFFF3DED2),
    onSurfaceVariant = Color(0xFF52443C),
    surfaceTint = Color(0xFF8F4C27),
    inverseSurface = Color(0xFF382F29),
    inverseOnSurface = Color(0xFFFEEEE4),
    error = Color(0xFFBA1A1A),
    onError = Color(0xFFFFFFFF),
    errorContainer = Color(0xFFFFDAD6),
    onErrorContainer = Color(0xFF93000A),
    outline = Color(0xFF85736B),
    outlineVariant = Color(0xFFD7C2B8),
    scrim = Color(0xFF000000),
    surfaceBright = Color(0xFFFFF8F5),
    surfaceDim = Color(0xFFE7D7CE),
    surfaceContainer = Color(0xFFFCEAE3),
    surfaceContainerHigh = Color(0xFFF6E5DD),
    surfaceContainerHighest = Color(0xFFF0DFD7),
    surfaceContainerLow = Color(0xFFFFF1EB),
    surfaceContainerLowest = Color(0xFFFFFFFF),
    primaryFixed = Color(0xFFFFDBCA),
    primaryFixedDim = Color(0xFFFFB68E),
    onPrimaryFixed = Color(0xFF301400),
    onPrimaryFixedVariant = Color(0xFF713700),
    secondaryFixed = Color(0xFFFFDBCA),
    secondaryFixedDim = Color(0xFFE4BFA7),
    onSecondaryFixed = Color(0xFF2A1708),
    onSecondaryFixedVariant = Color(0xFF5C4132),
    tertiaryFixed = Color(0xFFE8E6B0),
    tertiaryFixedDim = Color(0xFFC8CA94),
    onTertiaryFixed = Color(0xFF1B1D00),
    onTertiaryFixedVariant = Color(0xFF4A4919),
)

internal val Material3DarkColors = darkColorScheme(
    primary = Color(0xFFFFB68E),
    onPrimary = Color(0xFF532200),
    primaryContainer = Color(0xFF713700),
    onPrimaryContainer = Color(0xFFFFDBCA),
    inversePrimary = Color(0xFF8F4C27),
    secondary = Color(0xFFE4BFA7),
    onSecondary = Color(0xFF422B1B),
    secondaryContainer = Color(0xFF5C4132),
    onSecondaryContainer = Color(0xFFFFDBCA),
    tertiary = Color(0xFFC8CA94),
    onTertiary = Color(0xFF30330B),
    tertiaryContainer = Color(0xFF4A4919),
    onTertiaryContainer = Color(0xFFE8E6B0),
    background = Color(0xFF1A120D),
    onBackground = Color(0xFFF0DFD7),
    surface = Color(0xFF1A120D),
    onSurface = Color(0xFFF0DFD7),
    surfaceVariant = Color(0xFF52443C),
    onSurfaceVariant = Color(0xFFD7C2B8),
    surfaceTint = Color(0xFFFFB68E),
    inverseSurface = Color(0xFFF0DFD7),
    inverseOnSurface = Color(0xFF382F29),
    error = Color(0xFFFFB4AB),
    onError = Color(0xFF690005),
    errorContainer = Color(0xFF93000A),
    onErrorContainer = Color(0xFFFFDAD6),
    outline = Color(0xFFA08D84),
    outlineVariant = Color(0xFF52443C),
    scrim = Color(0xFF000000),
    surfaceBright = Color(0xFF413731),
    surfaceDim = Color(0xFF1A120D),
    surfaceContainer = Color(0xFF271E19),
    surfaceContainerHigh = Color(0xFF322823),
    surfaceContainerHighest = Color(0xFF3D332D),
    surfaceContainerLow = Color(0xFF221A15),
    surfaceContainerLowest = Color(0xFF140D09),
    primaryFixed = Color(0xFFFFDBCA),
    primaryFixedDim = Color(0xFFFFB68E),
    onPrimaryFixed = Color(0xFF301400),
    onPrimaryFixedVariant = Color(0xFF713700),
    secondaryFixed = Color(0xFFFFDBCA),
    secondaryFixedDim = Color(0xFFE4BFA7),
    onSecondaryFixed = Color(0xFF2A1708),
    onSecondaryFixedVariant = Color(0xFF5C4132),
    tertiaryFixed = Color(0xFFE8E6B0),
    tertiaryFixedDim = Color(0xFFC8CA94),
    onTertiaryFixed = Color(0xFF1B1D00),
    onTertiaryFixedVariant = Color(0xFF4A4919),
)

val LocalMaterial3 = staticCompositionLocalOf { false }
internal val LocalReaderStyle = staticCompositionLocalOf { false }

@Composable
fun MultipromptTheme(
    sunlight: Boolean = false,
    material3: Boolean = BuildConfig.MATERIAL3,
    readerStyle: Boolean = false,
    content: @Composable () -> Unit,
) {
    val colors = when {
        readerStyle && sunlight -> remember(material3) {
            if (material3) Material3LightColors else SunlightColors.copy(
                surfaceContainer = ChatBubbleAgentLight,
                surfaceContainerHigh = ChatBubbleUserLight,
                surfaceContainerHighest = ChatBubbleUserLight,
            )
        }
        readerStyle && !sunlight -> remember {
            Material3DarkColors.copy(
                background = ReaderBackground, surface = ReaderBackground,
                onBackground = ReaderForeground, onSurface = ReaderForeground,
                surfaceContainer = ReaderControl, surfaceContainerHigh = ReaderControl,
                surfaceContainerHighest = ReaderControl,
                surfaceVariant = ReaderCard, onSurfaceVariant = ReaderHint,
                outline = ReaderOutline, outlineVariant = ReaderOutline,
                primary = ReaderChoiceBlue, onPrimary = ReaderBackground,
            )
        }
        !material3 -> if (sunlight) SunlightColors else MultipromptColors
        sunlight -> Material3LightColors
        else -> Material3DarkColors
    }
    val typography = if (readerStyle || material3) material3Typography(systemFont = readerStyle) else MaterialTheme.typography
    CompositionLocalProvider(
        LocalMaterial3 provides material3,
        LocalReaderStyle provides readerStyle,
        LocalContentColor provides if (readerStyle || material3) colors.onBackground else LocalContentColor.current,
    ) {
        MaterialTheme(
            colorScheme = colors,
            typography = typography,
            shapes = if (material3) Shapes(extraSmall = androidx.compose.foundation.shape.RoundedCornerShape(8.dp)) else MaterialTheme.shapes,
            content = content,
        )
    }
}

@Composable
internal fun chatFontFamily(): FontFamily = if (LocalReaderStyle.current) FontFamily.SansSerif else if (LocalMaterial3.current) MaterialTheme.typography.bodyLarge.fontFamily ?: FontFamily.SansSerif else ChatFontFamily

@Composable
internal fun chromeShape(classic: Shape): Shape = if (LocalMaterial3.current) MaterialTheme.shapes.small else classic

@Composable
internal fun chatShape(): Shape = if (LocalReaderStyle.current) ReaderCardShape else if (LocalMaterial3.current) MaterialTheme.shapes.medium else {
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
    if (LocalReaderStyle.current) ReaderTextSize * scale else if (LocalMaterial3.current) 15.sp * scale else classic

@Composable
internal fun chatLineHeight(classic: TextUnit, scale: Float = 1f): TextUnit =
    if (LocalReaderStyle.current) ReaderLineHeight * scale else if (LocalMaterial3.current) 22.sp * scale else classic

@Composable
internal fun chatLinkColor(classic: Color): Color = if (LocalMaterial3.current) MaterialTheme.colorScheme.primary else classic

@Composable
private fun material3Typography(systemFont: Boolean = false): Typography {
    val context = LocalContext.current
    val family = remember(context, systemFont) {
        if (systemFont) return@remember FontFamily.SansSerif
        val provider = GoogleFont.Provider(
            providerAuthority = "com.google.android.gms.fonts",
            providerPackage = "com.google.android.gms",
            certificates = R.array.com_google_android_gms_fonts_certs,
        )
        if (provider.isAvailableOnDevice(context)) {
            FontFamily(
                GoogleFontFont(GoogleFont("Roboto Flex"), provider, FontWeight.Normal),
                GoogleFontFont(GoogleFont("Roboto Flex"), provider, FontWeight.Medium),
                GoogleFontFont(GoogleFont("Roboto Flex"), provider, FontWeight.SemiBold),
            )
        } else FontFamily.SansSerif
    }
    return remember(family) {
        val defaults = Typography()
        Typography(
            displayLarge = defaults.displayLarge.copy(fontFamily = family),
            displayMedium = defaults.displayMedium.copy(fontFamily = family),
            displaySmall = defaults.displaySmall.copy(fontFamily = family),
            headlineLarge = defaults.headlineLarge.copy(fontFamily = family),
            headlineMedium = defaults.headlineMedium.copy(fontFamily = family),
            headlineSmall = defaults.headlineSmall.copy(fontFamily = family),
            titleLarge = defaults.titleLarge.copy(fontFamily = family),
            titleMedium = defaults.titleMedium.copy(fontFamily = family),
            titleSmall = defaults.titleSmall.copy(fontFamily = family),
            bodyLarge = defaults.bodyLarge.copy(fontFamily = family),
            bodyMedium = defaults.bodyMedium.copy(fontFamily = family),
            bodySmall = defaults.bodySmall.copy(fontFamily = family),
            labelLarge = defaults.labelLarge.copy(fontFamily = family),
            labelMedium = defaults.labelMedium.copy(fontFamily = family),
            labelSmall = defaults.labelSmall.copy(fontFamily = family),
        )
    }
}
