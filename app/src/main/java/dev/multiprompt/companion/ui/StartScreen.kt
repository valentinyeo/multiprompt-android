package dev.multiprompt.companion.ui

import dev.multiprompt.companion.AppSection

/** Decides which screen the Sessions section shows. */
internal object StartScreen {
    /** Cold start, launcher tap and process restore all land in the Inbox. */
    const val INBOX_ON_LAUNCH = true

    /** Back from Hosts or Update returns to the workspaces view; any other section returns to the Inbox. */
    fun inboxAfterLeaving(section: AppSection): Boolean =
        section != AppSection.HOSTS && section != AppSection.UPDATE
}
