package dev.multiprompt.companion.ui

import android.app.Application
import android.content.SharedPreferences
import dev.multiprompt.companion.account.AccountLayoutStore
import dev.multiprompt.companion.account.PendingLayout
import java.util.concurrent.CountDownLatch
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicBoolean
import java.util.concurrent.atomic.AtomicInteger
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

    private fun observeCommit(before: () -> Unit, after: () -> Unit = {}): AccountLayoutStore {
        val store = f.field<AccountLayoutStore>(f.model, "accountStore")
        val preferences = f.field<SharedPreferences>(store, "preferences")
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
                            before()
                            return editor.commit()
                        } finally {
                            after()
                        }
                    }
                }
            }
        }
        ReflectionHelpers.setField(store, "preferences", measured)
        return store
    }

    @Test fun inputStampPublishesImmediatelyWithoutCommittingOnTheCallerThread() {
        val caller = Thread.currentThread()
        val committedOnCaller = AtomicBoolean(false)
        val committed = CountDownLatch(1)
        val releaseWrite = CountDownLatch(1)
        val store = observeCommit(before = {
            committedOnCaller.set(Thread.currentThread() === caller)
            check(!committedOnCaller.get()) { "Input stamp attempted disk commit on the UI caller" }
            check(releaseWrite.await(5, TimeUnit.SECONDS)) { "Caller waited for input persistence" }
        }, after = { committed.countDown() })
        try {
            f.model.stampSessionInput(f.session)
            assertNotNull("Input state must publish before disk completion", f.model.state.value.accountLayout!!.tabs.first().input)
            assertNull(f.model.state.value.accountError)
            assertEquals("Disk write must still be held after input returns", 1L, committed.count)
        } finally {
            releaseWrite.countDown()
        }
        val input = f.model.state.value.accountLayout!!.tabs.first().input
        assertTrue("The durable write must complete", committed.await(5, TimeUnit.SECONDS))
        assertFalse("Input persistence must not block the UI caller", committedOnCaller.get())
        assertEquals(input, store.load("test-account")!!.edited.tabs.first().input)
    }

    @Test fun queuedInputCannotOverwriteANewerWorkspaceSave() {
        val started = CountDownLatch(1)
        val releaseWrite = CountDownLatch(1)
        val commits = AtomicInteger()
        val store = observeCommit(before = {
            if (commits.incrementAndGet() == 1) {
                started.countDown()
                check(releaseWrite.await(5, TimeUnit.SECONDS))
            }
        })
        val earlier = PendingLayout(f.layout)
        val newer = PendingLayout(f.layout.copy(revision = 2))
        val first = store.saveAsync("ordered", earlier)
        try {
            assertTrue(started.await(5, TimeUnit.SECONDS))
            val second = store.saveAsync("ordered", newer)
            assertFalse("Later writes must wait for the active durable write", second.isDone)
            releaseWrite.countDown()
            first.get(5, TimeUnit.SECONDS)
            second.get(5, TimeUnit.SECONDS)
            assertEquals(newer, store.load("ordered"))
            val latest = PendingLayout(f.layout.copy(revision = 3))
            store.save("ordered", latest)
            assertEquals(latest, store.load("ordered"))
        } finally {
            releaseWrite.countDown()
        }
    }
}
