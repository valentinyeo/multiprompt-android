package dev.multiprompt.companion

import android.content.Intent
import android.os.Bundle
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.activity.enableEdgeToEdge
import androidx.compose.foundation.isSystemInDarkTheme
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.SideEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.core.view.WindowCompat
import androidx.lifecycle.viewmodel.compose.viewModel
import dev.multiprompt.companion.ui.MultipromptApp
import dev.multiprompt.companion.ui.AppTheme
import dev.multiprompt.companion.ui.MultipromptTheme

class MainActivity : ComponentActivity() {
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        enableEdgeToEdge()
        setContent {
            val viewModel: MainViewModel = viewModel()
            val state by viewModel.state.collectAsState()
            val systemDark = isSystemInDarkTheme()
            val sunlight = when (state.appTheme) {
                AppTheme.SYSTEM -> !systemDark
                AppTheme.DARK -> false
                AppTheme.SUNLIGHT -> true
            }
            SideEffect {
                WindowCompat.getInsetsController(window, window.decorView).apply {
                    isAppearanceLightStatusBars = sunlight
                    isAppearanceLightNavigationBars = sunlight
                }
            }
            MultipromptTheme(sunlight = sunlight) {
                LaunchedEffect(Unit) {
                    if (intent.getBooleanExtra(EXTRA_OPEN_UPDATE, false)) {
                        viewModel.select(AppSection.UPDATE)
                        viewModel.updates.check(force = true)
                    }
                }
                MultipromptApp(viewModel)
            }
        }
    }

    override fun onNewIntent(intent: Intent) {
        super.onNewIntent(intent)
        setIntent(intent)
        if (intent.getBooleanExtra(EXTRA_OPEN_UPDATE, false)) recreate()
    }

    companion object {
        const val EXTRA_OPEN_UPDATE = "open_update"
    }
}
