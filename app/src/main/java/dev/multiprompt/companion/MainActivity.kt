package dev.multiprompt.companion

import android.content.Intent
import android.os.Bundle
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.activity.enableEdgeToEdge
import androidx.activity.SystemBarStyle
import androidx.compose.foundation.isSystemInDarkTheme
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.SideEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.core.view.WindowCompat
import androidx.activity.viewModels
import dev.multiprompt.companion.notify.ReplyNotifier
import dev.multiprompt.companion.ui.MultipromptApp
import dev.multiprompt.companion.ui.AppTheme
import dev.multiprompt.companion.ui.MultipromptTheme

class MainActivity : ComponentActivity() {

    private val viewModel: MainViewModel by viewModels()
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        enableEdgeToEdge(statusBarStyle = SystemBarStyle.dark(android.graphics.Color.TRANSPARENT))
        setContent {
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
            MultipromptTheme(sunlight = sunlight, material3 = false) {
                LaunchedEffect(Unit) {
                    if (intent.getBooleanExtra(EXTRA_OPEN_UPDATE, false)) {
                        viewModel.select(AppSection.UPDATE)
                        viewModel.updates.check(force = true)
                    }
                }
                MultipromptApp(viewModel)
            }
        }
        handleAuthIntent(intent)
        handleReplyIntent(intent)
    }

    override fun onResume() {
        super.onResume()
        viewModel.onResume()
    }

    override fun onPause() {
        viewModel.onPause()
        super.onPause()
    }

    override fun onStop() {
        viewModel.onStop()
        super.onStop()
    }

    override fun onNewIntent(intent: Intent) {
        super.onNewIntent(intent)
        setIntent(intent)
        if (intent.getBooleanExtra(EXTRA_OPEN_UPDATE, false)) recreate()
        handleAuthIntent(intent)
        handleReplyIntent(intent)
    }

    private fun handleReplyIntent(intent: Intent) {
        val hostId = intent.getStringExtra(ReplyNotifier.EXTRA_HOST_ID) ?: return
        val sessionName = intent.getStringExtra(ReplyNotifier.EXTRA_SESSION_NAME) ?: return
        intent.removeExtra(ReplyNotifier.EXTRA_HOST_ID)
        intent.removeExtra(ReplyNotifier.EXTRA_SESSION_NAME)
        viewModel.openSessionFromNotification(hostId, sessionName)
    }

    private fun handleAuthIntent(intent: Intent) {
        if (intent.data?.scheme == BuildConfig.APPLICATION_ID) {
            viewModel.onAuthRedirect(Intent(intent))
            intent.data = null
        }
    }

    companion object {
        const val EXTRA_OPEN_UPDATE = "open_update"
    }
}
