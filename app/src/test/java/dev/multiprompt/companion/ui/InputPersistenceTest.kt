package dev.multiprompt.companion.ui

import android.app.Application
import android.content.SharedPreferences
import dev.multiprompt.companion.account.AccountLayoutStore
import java.util.concurrent.CountDownLatch
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicBoolean
import org.junit.After
import org.junit.Assert.*
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config
import org.robolectric.util.ReflectionHelpers

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [35], application = Application::class)
class InputPersistenceTest {
    private lateinit var f: SessionActivityFixture
    @Before fun setup() { f = SessionActivityFixture() }
    @After fun cleanup() { f.close() }

    @Test fun inputStampPublishesImmediatelyWithoutCommittingOnTheCallerThread() {
        val store = f.field<AccountLayoutStore>(f.model, "accountStore")
        val preferences = f.field<SharedPreferences>(store, "preferences")
        val caller = Thread.currentThread()
        val committedOnCaller = AtomicBoolean(false)
        val committed = CountDownLatch(1)
        val measured = object : SharedPreferences by preferences {
            override fun edit(): SharedPreferences.Editor {
                val editor = preferences.edit()
                return object : SharedPreferences.Editor by editor {
                    override fun putString(key: String?, value: String?): SharedPreferences.Editor {
                        editor.putString(key, value)
                        return this
                    }
                    override fun commit(): Boolean {
                        try {
                            committedOnCaller.set(Thread.currentThread() === caller)
                            check(!committedOnCaller.get()) { "Input stamp attempted disk commit on the UI caller" }
                            return editor.commit()
                        } finally {
                            committed.countDown()
                        }
                    }
                }
            }
        }
        ReflectionHelpers.setField(store, "preferences", measured)
        f.model.stampSessionInput(f.session)
        val input = f.model.state.value.accountLayout!!.tabs.first().input
        assertNotNull("Input state must publish before disk completion", input)
        assertNull(f.model.state.value.accountError)
        assertTrue("The durable write must complete", committed.await(5, TimeUnit.SECONDS))
        assertFalse("Input persistence must not block the UI caller", committedOnCaller.get())
        assertEquals(input, store.load("test-account")!!.edited.tabs.first().input)
    }
}
