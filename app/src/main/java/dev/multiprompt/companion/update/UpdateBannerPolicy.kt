package dev.multiprompt.companion.update

object UpdateBannerPolicy {
    fun shouldShow(availableVersionCode: Long, dismissedVersionCode: Long): Boolean =
        availableVersionCode > dismissedVersionCode

    fun nextDismissed(currentDismissedVersionCode: Long, laterVersionCode: Long): Long =
        maxOf(currentDismissedVersionCode, laterVersionCode)

    fun bannerRelease(state: UpdateState): UpdateRelease? = when (state) {
        is UpdateState.Available -> state.release
        is UpdateState.PermissionRequired -> state.release
        is UpdateState.Downloading -> state.release
        else -> null
    }

    fun shouldShowBanner(onUpdateScreen: Boolean, state: UpdateState, dismissedVersionCode: Long): Boolean {
        if (onUpdateScreen) return false
        val release = bannerRelease(state) ?: return false
        if (state is UpdateState.Available && !shouldShow(release.versionCode, dismissedVersionCode)) return false
        return true
    }
}
