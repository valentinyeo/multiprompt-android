package dev.multiprompt.companion.ui

import android.app.Application
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.material3.Text
import androidx.compose.ui.Modifier
import androidx.compose.ui.test.junit4.createComposeRule
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.performClick
import androidx.compose.ui.unit.sp
import org.junit.Assert.assertEquals
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config
import org.robolectric.annotation.GraphicsMode

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [35], application = Application::class, qualifiers = "w411dp-h891dp-mdpi")
@GraphicsMode(GraphicsMode.Mode.NATIVE)
class ShellTapTest {
    @get:Rule val compose = createComposeRule()

    @Test fun aTapOnTheLiveShellReachesTheScreenInsteadOfAnOverlay() {
        var taps = 0
        compose.setContent {
            MultipromptTheme {
                ShellContent(
                    historyVisible = false,
                    onOpenHistory = {},
                    onJumpToLive = {},
                    loadHistory = { emptyList() },
                    fontSize = 14.sp,
                    onFontSizeChange = {},
                    contentWidth = androidx.compose.ui.unit.Dp.Unspecified,
                    onLiveTap = { taps++ },
                ) { liveModifier ->
                    Box(liveModifier.fillMaxSize()) { Text("live shell") }
                }
            }
        }
        compose.onNodeWithText("live shell").performClick()
        assertEquals("the shell tap must reach onLiveTap, which opens the keyboard", 1, taps)
    }
}
