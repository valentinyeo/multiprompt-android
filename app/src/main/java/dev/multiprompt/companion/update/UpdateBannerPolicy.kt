package dev.multiprompt.companion.update

object UpdateBannerPolicy {
    fun shouldShow(availableVersionCode: Long, dismissedVersionCode: Long): Boolean =
        availableVersionCode > dismissedVersionCode

    fun nextDismissed(currentDismissedVersionCode: Long, laterVersionCode: Long): Long =
        maxOf(currentDismissedVersionCode, laterVersionCode)
}
