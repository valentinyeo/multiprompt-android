package dev.multiprompt.companion.ui

import android.speech.tts.TextToSpeech
import android.speech.tts.UtteranceProgressListener
import androidx.test.platform.app.InstrumentationRegistry
import java.io.File
import java.util.concurrent.CountDownLatch
import java.util.concurrent.TimeUnit
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class ReadAloudSoundTest {
    @Test fun engineSpeaksTheReplyTextIntoAFile() {
        val context = InstrumentationRegistry.getInstrumentation().targetContext
        val ready = CountDownLatch(1)
        var status = TextToSpeech.ERROR
        val engine = TextToSpeech(context) { value ->
            status = value
            ready.countDown()
        }
        try {
            assertTrue("speech engine did not start", ready.await(20, TimeUnit.SECONDS))
            assertEquals("speech engine refused to start", TextToSpeech.SUCCESS, status)
            val folder = File(
                checkNotNull(InstrumentationRegistry.getArguments().getString("additionalTestOutputDir")),
                "robot-faces",
            ).apply { mkdirs() }
            val output = File(folder, "read-aloud.wav")
            val done = CountDownLatch(1)
            engine.setOnUtteranceProgressListener(object : UtteranceProgressListener() {
                override fun onStart(utteranceId: String?) = Unit
                override fun onDone(utteranceId: String?) { done.countDown() }
                @Deprecated("Deprecated in Java")
                override fun onError(utteranceId: String?) { done.countDown() }
            })
            val started = engine.synthesizeToFile(
                "Read aloud check. This is a reply from your agent.",
                android.os.Bundle(),
                output,
                "read-aloud",
            )
            assertEquals("synthesis did not start", TextToSpeech.SUCCESS, started)
            assertTrue("synthesis did not finish", done.await(30, TimeUnit.SECONDS))
            assertTrue("no audio was written", output.length() > 1000)
        } finally {
            engine.stop()
            engine.shutdown()
        }
    }
}
