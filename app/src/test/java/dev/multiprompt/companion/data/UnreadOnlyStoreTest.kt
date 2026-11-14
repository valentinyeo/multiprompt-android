package dev.multiprompt.companion.data

import android.app.Application
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.RuntimeEnvironment
import org.robolectric.annotation.Config

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [35], application = Application::class)
class UnreadOnlyStoreTest {
    private val context = RuntimeEnvironment.getApplication()

    @Test fun aFreshInstallShowsEveryChat() {
        assertFalse(SessionReadStore(context).unreadOnly())
    }

    @Test fun theChoiceSurvivesANewStoreInstance() {
        SessionReadStore(context).setUnreadOnly(true)
        assertTrue(SessionReadStore(context).unreadOnly())
        SessionReadStore(context).setUnreadOnly(false)
        assertFalse(SessionReadStore(context).unreadOnly())
    }
}
