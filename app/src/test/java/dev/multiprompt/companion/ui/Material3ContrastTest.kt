package dev.multiprompt.companion.ui

import androidx.compose.ui.graphics.Color
import org.junit.Assert.assertTrue
import org.junit.Test
import kotlin.math.max
import kotlin.math.min
import kotlin.math.pow

class Material3ContrastTest {
    @Test
    fun fallbackTextPairsMeetWcagAA() {
        listOf(Material3LightColors, Material3DarkColors).forEachIndexed { index, scheme ->
            val roles = scheme.materialRoles()
            listOf(
                "primary", "secondary", "tertiary", "error", "primaryContainer",
                "secondaryContainer", "tertiaryContainer", "errorContainer", "surface", "background",
                "surfaceVariant",
            ).forEach { role ->
                val onRole = "on" + role.replaceFirstChar(Char::uppercase)
                val a = luminance(roles.getValue(role))
                val b = luminance(roles.getValue(onRole))
                val contrast = (max(a, b) + 0.05) / (min(a, b) + 0.05)
                assertTrue("scheme=$index $onRole/$role contrast=$contrast", contrast >= 4.5)
            }
        }
    }

    private fun luminance(color: Color): Double {
        fun linear(channel: Float): Double = channel.toDouble().let {
            if (it <= 0.04045) it / 12.92 else ((it + 0.055) / 1.055).pow(2.4)
        }
        return 0.2126 * linear(color.red) + 0.7152 * linear(color.green) + 0.0722 * linear(color.blue)
    }
}
