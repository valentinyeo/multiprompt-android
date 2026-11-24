package dev.multiprompt.companion.data

import android.app.Application
import dev.multiprompt.companion.model.TmuxSession
import org.junit.Assert.assertEquals
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.RuntimeEnvironment
import org.robolectric.annotation.Config

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [35], application = Application::class)
class PinnedChatStoreTest {
    private val context = RuntimeEnvironment.getApplication()
    private val alpha = TmuxSession("host-1", "alpha", 1, 0, 100)
    private val beta = TmuxSession("host-1", "beta", 1, 0, 200)
    private val gamma = TmuxSession("host-2", "gamma", 1, 0, 300)

    @Test
    fun pinsSurviveStoreRecreationInPinOrder() {
        val store = PinnedChatStore(context)
        store.pin(gamma)
        store.pin(alpha)

        val reopened = PinnedChatStore(context)
        val loaded = reopened.load()
        assertEquals(listOf(PinnedChats.key(gamma), PinnedChats.key(alpha)), loaded)
        assertEquals(
            listOf("gamma", "alpha", "beta"),
            PinnedChats.order(listOf(alpha, beta, gamma), loaded).map { it.name },
        )
    }

    @Test
    fun unpinAndRemoveHostPersistAcrossRecreation() {
        val store = PinnedChatStore(context)
        store.pin(alpha)
        store.pin(beta)
        store.pin(gamma)

        store.unpin(alpha)
        assertEquals(listOf(PinnedChats.key(beta), PinnedChats.key(gamma)), PinnedChatStore(context).load())

        store.removeHost("host-1")
        assertEquals(listOf(PinnedChats.key(gamma)), PinnedChatStore(context).load())
    }

    @Test
    fun pruneDropsPinsForSessionsMissingFromTheHostRefresh() {
        val store = PinnedChatStore(context)
        store.pin(alpha)
        store.pin(beta)
        store.pin(gamma)

        val pruned = store.prune(mapOf("host-1" to listOf("beta"), "host-2" to listOf("gamma")))

        assertEquals(listOf(PinnedChats.key(beta), PinnedChats.key(gamma)), pruned)
        assertEquals(pruned, PinnedChatStore(context).load())
    }
}
