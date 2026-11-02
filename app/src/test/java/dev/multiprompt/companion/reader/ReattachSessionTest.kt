package dev.multiprompt.companion.reader

import android.app.Application
import android.content.Context
import androidx.compose.ui.test.junit4.createComposeRule
import androidx.lifecycle.ViewModelStore
import dev.multiprompt.companion.MainViewModel
import dev.multiprompt.companion.MultipromptApplication
import dev.multiprompt.companion.model.HostProfile
import dev.multiprompt.companion.model.TmuxSession
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotSame
import org.junit.Assert.assertNotNull
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.RuntimeEnvironment
import org.robolectric.annotation.Config
import org.robolectric.util.ReflectionHelpers

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [35], application = Application::class, qualifiers = "w390dp-h844dp-mdpi")
class ReattachSessionTest {
    @get:Rule val compose = createComposeRule()
    private val models = ViewModelStore()
    private lateinit var app: MultipromptApplication
    private lateinit var model: MainViewModel

    @After fun close() {
        compose.runOnIdle {
            if (::model.isInitialized) model.closeReader()
            models.clear()
        }
        if (::app.isInitialized) app.deepgramDictation.close()
    }

    @Test fun reattachReplacesTheConnectionAndKeepsTheSession() {
        compose.runOnIdle {
            app = MultipromptApplication()
            ReflectionHelpers.callInstanceMethod<Unit>(app, "attach",
                ReflectionHelpers.ClassParameter.from(Context::class.java, RuntimeEnvironment.getApplication()))
            for (name in listOf("hosts", "account_layout", "encrypted_secrets", "session_reads")) {
                app.getSharedPreferences(name, Context.MODE_PRIVATE).edit().clear().commit()
            }
            app.getSharedPreferences("updates", Context.MODE_PRIVATE).edit()
                .putLong("last_check_ms", System.currentTimeMillis()).commit()
            val host = HostProfile("host", "VPS", "example.com", username = "user", keySecretId = "key")
            app.hostStore.upsert(host)
            model = MainViewModel(app).also { models.put("main", it) }
            assertEquals(listOf(host), model.state.value.hosts)
            val session = model.state.value.hosts.map { TmuxSession(it.id, "claude", 1, 0, 20) }.single()
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
