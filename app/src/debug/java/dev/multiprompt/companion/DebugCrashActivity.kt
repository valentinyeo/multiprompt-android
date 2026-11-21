package dev.multiprompt.companion

import android.app.Activity
import android.os.Bundle
import android.os.Handler
import android.os.Looper
import android.system.Os
import android.system.OsConstants

class DebugCrashActivity : Activity() {
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        val exit = intent.getStringExtra(EXTRA_EXIT)
        Handler(Looper.getMainLooper()).postDelayed({
            when (exit) {
                EXIT_ANR -> Thread.sleep(ANR_BLOCK_MILLIS)
                EXIT_NATIVE -> Os.kill(Os.getpid(), OsConstants.SIGSEGV)
                EXIT_KILL -> Os.kill(Os.getpid(), OsConstants.SIGKILL)
                else -> throw IllegalStateException(UNCAUGHT_MESSAGE)
            }
        }, SETTLE_MILLIS)
    }

    companion object {
        const val EXTRA_EXIT = "exit"
        const val EXIT_ANR = "anr"
        const val EXIT_NATIVE = "native"
        const val EXIT_KILL = "kill"
        const val UNCAUGHT_MESSAGE = "IGSH-2840 crash"
        private const val SETTLE_MILLIS = 3_000L
        private const val ANR_BLOCK_MILLIS = 180_000L
    }
}
