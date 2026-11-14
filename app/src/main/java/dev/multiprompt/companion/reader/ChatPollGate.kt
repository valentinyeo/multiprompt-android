package dev.multiprompt.companion.reader

object ChatPollGate {
    enum class Step { POLL_NOW, WAIT_FOR_WAKE }

    fun step(selected: Boolean): Step = if (selected) Step.POLL_NOW else Step.WAIT_FOR_WAKE
}
