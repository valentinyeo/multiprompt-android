package dev.multiprompt.companion.data

import dev.multiprompt.companion.model.TmuxSession
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import java.util.concurrent.Executors
import java.util.concurrent.TimeUnit

class SessionCacheStoreTest {
    private class FakeBacking : SessionCacheBacking {
        val values = mutableMapOf<String, String>()
        var reads = 0
        var writes = 0

        override fun read(key: String): String? {
            reads++
            return values[key]
        }

        override fun write(key: String, value: String) {
            writes++
            values[key] = value
        }

        override fun remove(key: String) {
            values.remove(key)
        }
    }

    private class Counting {
        var decodes = 0
        val decode: (String, String) -> List<TmuxSession> = { hostId, raw ->
            decodes++
            SessionCacheCodec.decode(hostId, raw)
        }
    }

    private fun session(name: String, preview: String = "hello") = TmuxSession(
        hostId = "h1",
        name = name,
        windows = 1,
        attachedClients = 0,
        lastActivityEpochSeconds = 100,
        preview = preview,
        replyAtEpochSeconds = 7,
        replyWaiting = true,
        accountName = "desk",
        workspaceName = "work",
        workspaceColor = 5,
    )

    private fun seeded(backing: FakeBacking, vararg sessions: TmuxSession) {
        backing.values["host::h1"] = SessionCacheCodec.encode(sessions.toList())
    }

    @Test
    fun tenLoadsDecodeTheSavedListOnce() {
        val backing = FakeBacking()
        seeded(backing, session("a"), session("b"))
        val counting = Counting()
        val store = SessionCacheStore(backing, counting.decode)

        repeat(10) { assertEquals(listOf("a", "b"), store.load("h1").map { it.name }) }

        assertEquals(1, counting.decodes)
        assertEquals(1, backing.reads)
    }

    @Test
    fun coldStartShowsTheSavedList() {
        val backing = FakeBacking()
        seeded(backing, session("a"))

        val loaded = SessionCacheStore(backing).load("h1")

        assertEquals(listOf("a"), loaded.map { it.name })
        assertEquals("hello", loaded.single().preview)
        assertEquals("h1", loaded.single().hostId)
    }

    @Test
    fun missingHostLoadsEmptyOnceWithoutDecoding() {
        val backing = FakeBacking()
        val counting = Counting()
        val store = SessionCacheStore(backing, counting.decode)

        repeat(3) { assertTrue(store.load("h1").isEmpty()) }

        assertEquals(0, counting.decodes)
        assertEquals(1, backing.reads)
    }

    @Test
    fun equalWriteIsSkipped() {
        val backing = FakeBacking()
        seeded(backing, session("a"))
        val store = SessionCacheStore(backing)
        store.load("h1")

        store.save("h1", listOf(session("a")))
        store.save("h1", listOf(session("a")))

        assertEquals(0, backing.writes)
    }

    @Test
    fun equalWriteBeforeAnyLoadIsSkipped() {
        val backing = FakeBacking()
        seeded(backing, session("a"))

        SessionCacheStore(backing).save("h1", listOf(session("a")))

        assertEquals(0, backing.writes)
    }

    @Test
    fun changedWriteIsPersistedAndVisibleWithoutDecoding() {
        val backing = FakeBacking()
        seeded(backing, session("a"))
        val counting = Counting()
        val store = SessionCacheStore(backing, counting.decode)
        store.load("h1")

        store.save("h1", listOf(session("a", preview = "newer"), session("b")))
        val loaded = store.load("h1")

        assertEquals(1, backing.writes)
        assertEquals(listOf("a", "b"), loaded.map { it.name })
        assertEquals("newer", loaded.first().preview)
        assertEquals(1, counting.decodes)
        assertEquals(loaded, SessionCacheStore(backing).load("h1"))
    }

    @Test
    fun savedThenLoadedMatchesWhatAColdStartReads() {
        val backing = FakeBacking()
        val store = SessionCacheStore(backing)

        store.save("h1", listOf(session("a"), session("b", preview = "line\nwith \"quotes\"")))

        assertEquals(store.load("h1"), SessionCacheStore(backing).load("h1"))
    }

    @Test
    fun saveThenRepeatedRefreshesWriteOnce() {
        val backing = FakeBacking()
        val store = SessionCacheStore(backing)
        val list = listOf(session("a"))

        repeat(10) {
            store.load("h1")
            store.save("h1", list)
        }

        assertEquals(1, backing.writes)
    }

    @Test
    fun removedHostLoadsEmptyAndStaysRemoved() {
        val backing = FakeBacking()
        seeded(backing, session("a"))
        val store = SessionCacheStore(backing)
        store.load("h1")

        store.removeHost("h1")

        assertTrue(store.load("h1").isEmpty())
        assertTrue(SessionCacheStore(backing).load("h1").isEmpty())
    }

    @Test
    fun hostsAreCachedSeparately() {
        val backing = FakeBacking()
        val store = SessionCacheStore(backing)

        store.save("h1", listOf(session("a")))
        store.save("h2", listOf(session("b")))

        assertEquals(listOf("a"), store.load("h1").map { it.name })
        assertEquals(listOf("b"), store.load("h2").map { it.name })
        assertEquals("h2", store.load("h2").single().hostId)
    }

    @Test
    fun concurrentReadsDecodeOnce() {
        val backing = FakeBacking()
        seeded(backing, session("a"))
        val counting = Counting()
        val store = SessionCacheStore(backing, counting.decode)
        val pool = Executors.newFixedThreadPool(8)

        val results = (1..32).map { pool.submit<List<TmuxSession>> { store.load("h1") } }.map { it.get(10, TimeUnit.SECONDS) }
        pool.shutdown()

        assertTrue(results.all { it.map { s -> s.name } == listOf("a") })
        assertEquals(1, counting.decodes)
    }
}
