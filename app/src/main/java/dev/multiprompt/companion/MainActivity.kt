package dev.multiprompt.companion

import android.content.Intent
import android.os.Bundle
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.activity.enableEdgeToEdge
import androidx.compose.runtime.LaunchedEffect
import androidx.lifecycle.viewmodel.compose.viewModel
import dev.multiprompt.companion.ui.MultipromptApp
import dev.multiprompt.companion.ui.MultipromptTheme

class MainActivity : ComponentActivity() {
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        enableEdgeToEdge()
        setContent {
            MultipromptTheme {
                val viewModel: MainViewModel = viewModel()
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
