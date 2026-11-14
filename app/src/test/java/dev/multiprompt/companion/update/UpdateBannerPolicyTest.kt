package dev.multiprompt.companion.update

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class UpdateBannerPolicyTest {
    @Test fun bannerShowsWhenNothingWasDismissed() {
        assertTrue(UpdateBannerPolicy.shouldShow(availableVersionCode = 100_050, dismissedVersionCode = 0))
    }

    @Test fun laterHidesTheBannerForThatVersion() {
        val dismissed = UpdateBannerPolicy.nextDismissed(currentDismissedVersionCode = 0, laterVersionCode = 100_050)
        assertFalse(UpdateBannerPolicy.shouldShow(availableVersionCode = 100_050, dismissedVersionCode = dismissed))
    }

    @Test fun strictlyNewerVersionBringsTheBannerBack() {
        val dismissed = UpdateBannerPolicy.nextDismissed(currentDismissedVersionCode = 0, laterVersionCode = 100_050)
        assertTrue(UpdateBannerPolicy.shouldShow(availableVersionCode = 100_051, dismissedVersionCode = dismissed))
    }

    @Test fun olderDismissedVersionNeverLowersTheStoredValue() {
        assertEquals(
            100_050L,
            UpdateBannerPolicy.nextDismissed(currentDismissedVersionCode = 100_050, laterVersionCode = 100_040),
        )
    }

    @Test fun hiddenVersionStaysHiddenAfterRestartFromStoredValue() {
        val stored = UpdateBannerPolicy.nextDismissed(currentDismissedVersionCode = 0, laterVersionCode = 100_050)
        val afterRestart = stored
        assertFalse(UpdateBannerPolicy.shouldShow(availableVersionCode = 100_050, dismissedVersionCode = afterRestart))
    }
}
