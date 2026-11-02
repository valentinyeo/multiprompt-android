package dev.multiprompt.companion.reader

import android.content.Context
import androidx.compose.ui.test.junit4.createAndroidComposeRule
import dev.multiprompt.companion.MainActivity
import dev.multiprompt.companion.MainViewModel
import dev.multiprompt.companion.MultipromptApplication
import dev.multiprompt.companion.model.HostProfile
import dev.multiprompt.companion.model.TmuxSession
import org.junit.After
import org.junit.Rule
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotSame
import org.junit.Assert.assertNotNull
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.RuntimeEnvironment
import org.robolectric.annotation.Config
import org.robolectric.util.ReflectionHelpers

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [35], application = MultipromptApplication::class)
class ReattachSessionTest {
    @get:Rule(order = 0) val compose = createAndroidComposeRule<MainActivity>()
    private lateinit var app: MultipromptApplication
    private var model: MainViewModel? = null

    @After fun tearDown() {
        model?.closeReader()
        if (::app.isInitialized) app.deepgramDictation.close()
    }

    @Test fun reattachReplacesTheConnectionAndKeepsTheSession() {
        compose.runOnIdle {
        app = ReflectionHelpers.newInstance(MultipromptApplication::class.java)
        ReflectionHelpers.callInstanceMethod<Unit>(app, "attach",
            ReflectionHelpers.ClassParameter.from(Context::class.java, RuntimeEnvironment.getApplication()))
        val host = HostProfile("host", "VPS", "example.com", username = "user", keySecretId = "key")
        app.hostStore.upsert(host)
        val model = MainViewModel(app).also { model = it }
        val session = TmuxSession("host", "claude", 1, 0, 20)
        model.openReader(session)
        val first = model.state.value.reader
        assertNotNull("the reader opens", first)
        assertEquals(session.name, model.state.value.readerSession?.name)
        model.reattachReader(session)
        val second = model.state.value.reader
        assertNotNull("reattach leaves a reader open", second)
        assertNotSame("reattach builds a fresh connection", first, second)
        assertEquals(session.name, model.state.value.readerSession?.name)
        }
    }
}
