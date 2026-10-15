package dev.multiprompt.companion.ui

import dev.multiprompt.companion.AppSection

internal object StartScreen {
    const val INBOX_ON_LAUNCH = true

    fun inboxAfterLeaving(section: AppSection): Boolean =
        section != AppSection.HOSTS && section != AppSection.UPDATE
}
