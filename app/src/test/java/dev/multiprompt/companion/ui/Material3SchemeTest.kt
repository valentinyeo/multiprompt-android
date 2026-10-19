package dev.multiprompt.companion.ui

import androidx.compose.material3.ColorScheme
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.toArgb
import com.google.android.material.color.utilities.Hct
import com.google.android.material.color.utilities.MaterialDynamicColors
import com.google.android.material.color.utilities.SchemeTonalSpot
import org.junit.Assert.assertEquals
import org.junit.Test

internal fun ColorScheme.materialRoles(): Map<String, Color> = mapOf(
    "primary" to primary,
    "onPrimary" to onPrimary,
    "primaryContainer" to primaryContainer,
    "onPrimaryContainer" to onPrimaryContainer,
    "inversePrimary" to inversePrimary,
    "secondary" to secondary,
    "onSecondary" to onSecondary,
    "secondaryContainer" to secondaryContainer,
    "onSecondaryContainer" to onSecondaryContainer,
    "tertiary" to tertiary,
    "onTertiary" to onTertiary,
    "tertiaryContainer" to tertiaryContainer,
    "onTertiaryContainer" to onTertiaryContainer,
    "background" to background,
    "onBackground" to onBackground,
    "surface" to surface,
    "onSurface" to onSurface,
    "surfaceVariant" to surfaceVariant,
    "onSurfaceVariant" to onSurfaceVariant,
    "surfaceTint" to surfaceTint,
    "inverseSurface" to inverseSurface,
    "inverseOnSurface" to inverseOnSurface,
    "error" to error,
    "onError" to onError,
    "errorContainer" to errorContainer,
    "onErrorContainer" to onErrorContainer,
    "outline" to outline,
    "outlineVariant" to outlineVariant,
    "scrim" to scrim,
    "surfaceBright" to surfaceBright,
    "surfaceDim" to surfaceDim,
    "surfaceContainer" to surfaceContainer,
    "surfaceContainerHigh" to surfaceContainerHigh,
    "surfaceContainerHighest" to surfaceContainerHighest,
    "surfaceContainerLow" to surfaceContainerLow,
    "surfaceContainerLowest" to surfaceContainerLowest,
    "primaryFixed" to primaryFixed,
    "primaryFixedDim" to primaryFixedDim,
    "onPrimaryFixed" to onPrimaryFixed,
    "onPrimaryFixedVariant" to onPrimaryFixedVariant,
    "secondaryFixed" to secondaryFixed,
    "secondaryFixedDim" to secondaryFixedDim,
    "onSecondaryFixed" to onSecondaryFixed,
    "onSecondaryFixedVariant" to onSecondaryFixedVariant,
    "tertiaryFixed" to tertiaryFixed,
    "tertiaryFixedDim" to tertiaryFixedDim,
    "onTertiaryFixed" to onTertiaryFixed,
    "onTertiaryFixedVariant" to onTertiaryFixedVariant,
)

class Material3SchemeTest {
    @Test
    fun everyRoleMatchesWireframeOrUnchangedTonalSpotFallback() {
        val colors = MaterialDynamicColors()
        listOf(false to Material3LightColors, true to Material3DarkColors).forEach { (dark, committed) ->
            val generated = SchemeTonalSpot(Hct.fromInt(0xFFD27B35.toInt()), dark, 0.0)
            val wireframe = if (dark) mapOf(
                "primary" to 0xFFFFB68E.toInt(),
                "onPrimary" to 0xFF532200.toInt(),
                "primaryContainer" to 0xFF713700.toInt(),
                "onPrimaryContainer" to 0xFFFFDBCA.toInt(),
                "inversePrimary" to 0xFF8F4C27.toInt(),
                "secondaryContainer" to 0xFF5C4132.toInt(),
                "onSecondaryContainer" to 0xFFFFDBCA.toInt(),
                "tertiaryContainer" to 0xFF4A4919.toInt(),
                "onTertiaryContainer" to 0xFFE8E6B0.toInt(),
                "background" to 0xFF1A120D.toInt(),
                "onBackground" to 0xFFF0DFD7.toInt(),
                "surface" to 0xFF1A120D.toInt(),
                "onSurface" to 0xFFF0DFD7.toInt(),
                "surfaceVariant" to 0xFF52443C.toInt(),
                "onSurfaceVariant" to 0xFFD7C2B8.toInt(),
                "surfaceTint" to 0xFFFFB68E.toInt(),
                "inverseSurface" to 0xFFF0DFD7.toInt(),
                "outline" to 0xFFA08D84.toInt(),
                "outlineVariant" to 0xFF52443C.toInt(),
                "surfaceDim" to 0xFF1A120D.toInt(),
                "surfaceContainer" to 0xFF271E19.toInt(),
                "surfaceContainerHigh" to 0xFF322823.toInt(),
                "surfaceContainerHighest" to 0xFF3D332D.toInt(),
                "surfaceContainerLowest" to 0xFF140D09.toInt(),
                "primaryFixed" to 0xFFFFDBCA.toInt(),
                "primaryFixedDim" to 0xFFFFB68E.toInt(),
                "onPrimaryFixedVariant" to 0xFF713700.toInt(),
                "secondaryFixed" to 0xFFFFDBCA.toInt(),
                "onSecondaryFixedVariant" to 0xFF5C4132.toInt(),
                "tertiaryFixed" to 0xFFE8E6B0.toInt(),
                "onTertiaryFixedVariant" to 0xFF4A4919.toInt(),
                "surfaceContainerLow" to 0xFF221A15.toInt(),
                "error" to 0xFFFFB4AB.toInt(),
            ) else mapOf(
                "primary" to 0xFF8F4C27.toInt(),
                "primaryContainer" to 0xFFFFDBCA.toInt(),
                "onPrimaryContainer" to 0xFF351000.toInt(),
                "inversePrimary" to 0xFFFFB68E.toInt(),
                "secondaryContainer" to 0xFFFFDBCA.toInt(),
                "onSecondaryContainer" to 0xFF2C160B.toInt(),
                "tertiaryContainer" to 0xFFE8E6B0.toInt(),
                "onTertiaryContainer" to 0xFF1D1D00.toInt(),
                "onSurfaceVariant" to 0xFF52443C.toInt(),
                "surfaceTint" to 0xFF8F4C27.toInt(),
                "outline" to 0xFF85736B.toInt(),
                "outlineVariant" to 0xFFD7C2B8.toInt(),
                "surfaceContainer" to 0xFFFCEAE3.toInt(),
                "surfaceContainerHigh" to 0xFFF6E5DD.toInt(),
                "surfaceContainerHighest" to 0xFFF0DFD7.toInt(),
                "surfaceContainerLow" to 0xFFFFF1EB.toInt(),
                "primaryFixed" to 0xFFFFDBCA.toInt(),
                "primaryFixedDim" to 0xFFFFB68E.toInt(),
                "onPrimaryFixedVariant" to 0xFF713700.toInt(),
                "secondaryFixed" to 0xFFFFDBCA.toInt(),
                "onSecondaryFixedVariant" to 0xFF5C4132.toInt(),
                "tertiaryFixed" to 0xFFE8E6B0.toInt(),
                "onTertiaryFixedVariant" to 0xFF4A4919.toInt(),
                "onPrimary" to 0xFFFFFFFF.toInt(),
                "surface" to 0xFFFFF8F5.toInt(),
                "surfaceContainerLowest" to 0xFFFFFFFF.toInt(),
                "onSurface" to 0xFF221A15.toInt(),
                "error" to 0xFFBA1A1A.toInt(),
            )
            committed.materialRoles().forEach { (role, color) ->
                val dynamic = MaterialDynamicColors::class.java.getMethod(role).invoke(colors)
                    as com.google.android.material.color.utilities.DynamicColor
                assertEquals("dark=$dark role=$role", wireframe[role] ?: dynamic.getArgb(generated), color.toArgb())
            }
        }
    }
}
