package dev.multiprompt.companion

import android.app.Activity
import android.os.Bundle

class DebugCrashActivity : Activity() {
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        throw RuntimeException("IGSH-429 test crash")
    }
}
