package dev.multiprompt.companion

import android.os.Bundle
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.activity.enableEdgeToEdge
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
                MultipromptApp(viewModel)
            }
        }
    }
}

