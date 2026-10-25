package dev.multiprompt.companion.dictation

import android.Manifest
import android.app.Application
import dev.multiprompt.companion.security.SecretStorage
import java.io.IOException
import java.util.concurrent.CopyOnWriteArrayList
import java.util.concurrent.CountDownLatch
import java.util.concurrent.LinkedBlockingQueue
import java.util.concurrent.TimeUnit
import okhttp3.Protocol
import okhttp3.Request
import okhttp3.Response
import okhttp3.WebSocket
import okhttp3.WebSocketListener
import okio.ByteString
import org.junit.Assert.*
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.RuntimeEnvironment
import org.robolectric.Shadows.shadowOf
import org.robolectric.annotation.Config

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [35], application = Application::class)
class DeepgramDictationTest {
    @Test
    fun backgroundEntryPointSynchronouslyStopsAndReleasesSilentMicrophone() {
        val fixture = Fixture()
        fixture.dictation.use { dictation ->
            dictation.start("test-session")
            fixture.mic.feed(1, voiced = false)
            assertEquals(DictationStatus.LISTENING, dictation.state.value.status)
            assertEquals(1, fixture.mic.starts)
            assertTrue(fixture.sockets.isEmpty())
            dictation.onAppBackgrounded()
            assertEquals(1, fixture.mic.stops)
            assertEquals(1, fixture.mic.releases)
            assertEquals(DictationStatus.IDLE, dictation.state.value.status)
            assertEquals(0.0, fixture.usage.entries().values.single(), 0.0)
            dictation.stop()
            assertEquals(1, fixture.mic.stops)
        }
    }

    @Test
    fun levelFollowsTheMicrophoneAndDropsToZeroOnStop() {
        val fixture = Fixture()
        fixture.dictation.use { dictation ->
            dictation.start("test-session")
            fixture.mic.feed(1, voiced = false)
            assertEquals(0f, dictation.level.value, 0f)
            fixture.mic.feed(1, voiced = true)
            assertTrue(dictation.level.value > 0.3f)
            dictation.onAppBackgrounded()
            assertEquals(0f, dictation.level.value, 0f)
        }
    }

    @Test
    fun gateClosesSocketButKeepsListeningAndAcceptsLateTranscriptsAfterReopening() {
        val fixture = Fixture()
        fixture.dictation.use { dictation ->
            dictation.start("test-session")
            fixture.mic.feed(10, voiced = false)
            assertTrue(fixture.sockets.isEmpty())
            fixture.mic.feed(10, voiced = true)
            val first = fixture.sockets.single()
            assertTrue(first.audio.isEmpty())
            first.open()
            assertTrue(first.audio.isNotEmpty())
            fixture.mic.feed(60, voiced = false)
            assertTrue(first.text.contains("""{"type":"Finalize"}"""))
            assertTrue(first.text.contains("""{"type":"KeepAlive"}"""))
            assertTrue(first.text.contains("""{"type":"CloseStream"}"""))
            assertEquals(1, first.closes)
            assertEquals(DictationStatus.LISTENING, dictation.state.value.status)
            assertEquals(0, fixture.mic.stops)
            fixture.mic.feed(10, voiced = true)
            val second = fixture.sockets.last()
            assertNotSame(first, second)
            second.open()
            first.transcript("late first")
            assertEquals("late first", dictation.state.value.transcript)
            first.listener.onClosed(first, 1000, "gate pause")
            assertEquals(DictationStatus.LISTENING, dictation.state.value.status)
            second.transcript("second")
            assertEquals("late first second", dictation.state.value.transcript)
            dictation.onAppBackgrounded()
            assertEquals(1, fixture.mic.stops)
            assertEquals(1, fixture.mic.releases)
            assertEquals(DictationStatus.FINISHING, dictation.state.value.status)
            second.listener.onClosed(second, 1000, "done")
            assertEquals(DictationStatus.IDLE, dictation.state.value.status)
            assertEquals(first.audio.sumOf { it.size } + second.audio.sumOf { it.size },
                (fixture.usage.entries().values.single() * 32_000).toInt())
        }
    }

    @Test
    fun gateClosedFailureIsIgnoredEvenWithoutANewSocket() {
        val fixture = Fixture()
        fixture.dictation.use { dictation ->
            dictation.start("test-session")
            fixture.mic.feed(10, voiced = true)
            val first = fixture.sockets.single()
            first.open()
            fixture.mic.feed(61, voiced = false)
            first.listener.onFailure(first, IOException("closing"), null)
            assertEquals(DictationStatus.LISTENING, dictation.state.value.status)
            assertNull(dictation.state.value.error)
            assertEquals(0, fixture.mic.stops)
            dictation.stop()
            assertEquals(DictationStatus.IDLE, dictation.state.value.status)
        }
    }

    @Test
    fun pendingGateClosedSocketFlushesItsOwnAudioAndControlsWhenOpenedLate() {
        val fixture = Fixture()
        fixture.dictation.use { dictation ->
            dictation.start("test-session")
            fixture.mic.feed(10, voiced = true)
            val first = fixture.sockets.single()
            fixture.mic.feed(61, voiced = false)
            fixture.mic.feed(10, voiced = true)
            val second = fixture.sockets.last()
            first.open()
            assertEquals(1, first.audio.size)
            assertTrue(first.text.contains("""{"type":"Finalize"}"""))
            assertTrue(first.text.contains("""{"type":"CloseStream"}"""))
            assertTrue(second.audio.isEmpty())
            second.open()
            assertTrue(second.audio.isNotEmpty())
            first.transcript("old socket final")
            assertEquals("old socket final", dictation.state.value.transcript)
            assertEquals(DictationStatus.LISTENING, dictation.state.value.status)
        }
    }

    @Test
    fun euFailureFallsBackToGlobalWithoutLosingBufferedAudio() {
        val fixture = Fixture()
        fixture.dictation.use { dictation ->
            dictation.start("test-session")
            fixture.mic.feed(10, voiced = true)
            val eu = fixture.sockets.single()
            assertEquals("api.eu.deepgram.com", eu.request().url.host)
            eu.listener.onFailure(eu, IOException("EU unavailable"), null)
            val global = fixture.sockets.last()
            assertEquals("api.deepgram.com", global.request().url.host)
            global.open()
            assertEquals(32_000, global.audio.sumOf { it.size })
            assertEquals(DictationStatus.LISTENING, dictation.state.value.status)
        }
    }

    @Test
    fun stopBeforeSocketOpensNeverReturnsToListeningOnOpen() {
        val fixture = Fixture()
        fixture.dictation.use { dictation ->
            dictation.start("test-session")
            fixture.mic.feed(10, voiced = true)
            dictation.stop()
            assertEquals(DictationStatus.FINISHING, dictation.state.value.status)
            val active = fixture.sockets.single()
            active.open()
            assertEquals(DictationStatus.FINISHING, dictation.state.value.status)
            assertTrue(active.text.contains("""{"type":"Finalize"}"""))
            active.listener.onClosed(active, 1000, "done")
            assertEquals(DictationStatus.IDLE, dictation.state.value.status)
        }
    }

    @Test
    fun forgottenSilentMicStopsWithoutOpeningASocket() {
        val fixture = Fixture()
        fixture.dictation.use { dictation ->
            dictation.start("test-session")
            fixture.mic.feed(450, voiced = false, awaitDrain = false)
            assertTrue(fixture.mic.released.await(5, TimeUnit.SECONDS))
            synchronized(dictation) {
                assertEquals(DictationStatus.IDLE, dictation.state.value.status)
                assertEquals(1, fixture.mic.stops)
                assertEquals(1, fixture.mic.releases)
                assertTrue(fixture.sockets.isEmpty())
            }
        }
    }

    @Test
    fun zeroByteReadsBackOffThenResumeAudioAndRemainCancellable() {
        val fixture = Fixture()
        fixture.dictation.use { dictation ->
            dictation.start("test-session")
            val elapsed = fixture.mic.feedZeroReads(12)
            assertTrue("Zero reads took ${elapsed}ms", elapsed >= 100)
            assertEquals(DictationStatus.LISTENING, dictation.state.value.status)
            assertTrue(fixture.sockets.isEmpty())
            fixture.mic.feed(10, voiced = true)
            assertEquals(1, fixture.sockets.size)
            dictation.stop()
            assertEquals(1, fixture.mic.stops)
            assertEquals(1, fixture.mic.releases)
        }
    }

    private class Fixture {
        val mic = FakeMic()
        val sockets = CopyOnWriteArrayList<FakeSocket>()
        private val application = RuntimeEnvironment.getApplication()
        val usage = PreferencesDictationUsageStore(application)
        val dictation: DeepgramDictation

        init {
            shadowOf(application).grantPermissions(Manifest.permission.RECORD_AUDIO)
            application.getSharedPreferences("dictation_usage", 0).edit().clear().commit()
            dictation = DeepgramDictation(application, object : SecretStorage {
                override fun get(id: String): ByteArray = "test-key".toByteArray()
                override fun put(id: String, value: ByteArray) = Unit
                override fun remove(id: String?) = Unit
            }, micFactory = { mic }, socketFactory = { request, listener ->
                FakeSocket(request, listener).also { sockets += it }
            })
        }
    }

    private class FakeMic : MicSource {
        private val input = LinkedBlockingQueue<() -> ByteArray?>()
        private val endOfInput = byteArrayOf()
        val released = CountDownLatch(1)
        var starts = 0
        var stops = 0
        var releases = 0
        override fun start(): Boolean { starts++; return true }
        override fun read(buffer: ByteArray): Int {
            while (true) {
                val bytes = input.take().invoke() ?: continue
                if (bytes === endOfInput) return -1
                if (bytes.isEmpty()) return 0
                bytes.copyInto(buffer)
                return bytes.size
            }
        }
        override fun stop() { stops++; input.offer { endOfInput } }
        override fun release() { releases++; released.countDown() }

        fun feedZeroReads(count: Int): Long {
            val started = System.nanoTime()
            repeat(count) { input.put { byteArrayOf() } }
            val drained = CountDownLatch(1)
            input.put { drained.countDown(); null }
            assertTrue("Recorder drained zero reads", drained.await(5, TimeUnit.SECONDS))
            return TimeUnit.NANOSECONDS.toMillis(System.nanoTime() - started)
        }

        fun feed(chunks: Int, voiced: Boolean, awaitDrain: Boolean = true) {
            val bytes = ByteArray(3_200)
            if (voiced) {
                for (index in 640 until bytes.size step 2) {
                    bytes[index] = 0xD0.toByte()
                    bytes[index + 1] = 0x07
                }
            }
            repeat(chunks) { input.put { bytes } }
            if (awaitDrain) {
                val drained = CountDownLatch(1)
                input.put { drained.countDown(); null }
                assertTrue("Recorder drained input", drained.await(5, TimeUnit.SECONDS))
            }
        }
    }

    private class FakeSocket(private val request: Request, val listener: WebSocketListener) : WebSocket {
        val text = CopyOnWriteArrayList<String>()
        val audio = CopyOnWriteArrayList<ByteString>()
        var closes = 0
        override fun request(): Request = request
        override fun queueSize(): Long = 0
        override fun send(text: String): Boolean { this.text += text; return true }
        override fun send(bytes: ByteString): Boolean { audio += bytes; return true }
        override fun close(code: Int, reason: String?): Boolean { closes++; return true }
        override fun cancel() = Unit
        fun open() {
            listener.onOpen(this, Response.Builder().request(request).protocol(Protocol.HTTP_1_1)
                .code(101).message("Switching Protocols").build())
        }
        fun transcript(value: String) {
            listener.onMessage(this, """{"type":"Results","is_final":true,"channel":{"alternatives":[{"transcript":"$value"}]}}""")
        }
    }
}
