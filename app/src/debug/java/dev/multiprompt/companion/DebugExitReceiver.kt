package dev.multiprompt.companion

import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.system.Os
import android.system.OsConstants

class DebugExitReceiver : BroadcastReceiver() {
    override fun onReceive(context: Context, intent: Intent) {
        when (intent.action) {
            ACTION_ANR -> Thread.sleep(30_000)
            ACTION_NATIVE -> Os.kill(Os.getpid(), OsConstants.SIGABRT)
            ACTION_THROW -> throw IllegalStateException("IGSH-2840 crash")
        }
    }

    companion object {
        const val ACTION_ANR = "dev.multiprompt.companion.debug.ANR"
        const val ACTION_NATIVE = "dev.multiprompt.companion.debug.NATIVE"
        const val ACTION_THROW = "dev.multiprompt.companion.debug.THROW"
    }
}
