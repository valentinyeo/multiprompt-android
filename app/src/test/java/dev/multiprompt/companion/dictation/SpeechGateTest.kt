package dev.multiprompt.companion.dictation

import java.io.ByteArrayOutputStream
import java.util.Random
import kotlin.math.PI
import kotlin.math.sin
import org.junit.Assert.*
import org.junit.Test

class SpeechGateTest {
    @Test
    fun sixtySecondsPreservesAllSpeechWithPreRollWithinBudget() {
        assertBurstSpeechWithinBudget(58.0, "original speech")
    }

    @Test
    fun sixtySecondsPreservesSpeechOverSteadyMinusFortyDbfsNoise() {
        assertBurstSpeechWithinBudget(327.68, "speech over -40 dBFS noise")
    }

    @Test
    fun stationaryMinusThirtyDbfsNoiseStopsFromIdleWithinBudget() {
        val random = Random(434)
        val gate = SpeechGate()
        val actions = gate.process(pcm(60) { random.nextGaussian() * 1_036.22 })
        val sent = actions.filterIsInstance<GateAction.Send>().sumOf { it.bytes.size }
        assertEquals(sent / 32_000.0, gate.sentSeconds, 0.0)
        assertTrue("Sent ${gate.sentSeconds}s", gate.sentSeconds <= 2.0)
        assertEquals(GateAction.StopDictation("No speech for 45 seconds"), actions.last())
        assertEquals(1, actions.count { it is GateAction.StopDictation })
        println("SpeechGate stationary -30 dBFS noise: ${gate.sentSeconds} streamed seconds")
    }

    private fun assertBurstSpeechWithinBudget(noiseRms: Double, label: String) {
        val bursts = listOf(2 to 6, 16 to 19, 32 to 35)
        val random = Random(434)
        val pcm = pcm(60) { sample ->
            val time = sample.toDouble() / SpeechGate.SAMPLE_RATE
            val burst = bursts.firstOrNull { time >= it.first && time < it.second }
            val noise = random.nextGaussian() * noiseRms
            if (burst == null) noise else {
                val phase = time - burst.first
                val dip = phase % 0.7 in 0.35..0.46
                val envelope = if (dip) 0.01 else 0.8 + 0.2 * sin(2 * PI * 4 * phase)
                noise + 5_000 * envelope * (sin(2 * PI * 200 * time) + 0.3 * sin(2 * PI * 400 * time))
            }
        }
        val gate = SpeechGate()
        val actions = pcm.asList().chunked(3_200).flatMap { gate.process(it.toByteArray()) }
        val sends = actions.filterIsInstance<GateAction.Send>()
        val sent = sends.sumOf { it.bytes.size }
        assertTrue("Sent ${sent / 32_000.0}s", sent <= 13 * 32_000)
        assertTrue("Sent ${sent / 32_000.0}s", sent >= 10 * 32_000)
        assertEquals(sent / 2L, gate.sentSamples)
        assertEquals(sent / 32_000.0, gate.sentSeconds, 0.0)
        assertEquals(3, actions.count { it == GateAction.OpenStream })
        assertEquals(3, actions.count { it == GateAction.CloseStream })
        assertEquals(3, actions.count { it == GateAction.Finalize })
        assertEquals(3, actions.count { it == GateAction.KeepAlive })
        assertFalse(actions.any { it is GateAction.StopDictation })
        val first = sends.first().bytes
        assertEquals(9_600, first.size)
        assertArrayEquals(pcm.copyOfRange((2 * 16_000 - 3_840) * 2, (2 * 16_000 + 960) * 2), first)
        val streams = mutableListOf<ByteArrayOutputStream>()
        actions.forEach {
            when (it) {
                GateAction.OpenStream -> streams += ByteArrayOutputStream()
                is GateAction.Send -> streams.last().write(it.bytes)
                else -> Unit
            }
        }
        bursts.forEachIndexed { index, (start, end) ->
            val audio = streams[index].toByteArray()
            val speech = pcm.copyOfRange(start * 32_000, end * 32_000)
            assertArrayEquals("Burst $index preserved sample for sample", speech, audio.copyOfRange(7_680, 7_680 + speech.size))
        }
        println("SpeechGate $label 60s fixture: ${gate.sentSeconds} streamed seconds ($sent bytes)")
    }

    @Test
    fun pureSilenceStopsAtFortyFiveSecondsWithoutOpeningOrSending() {
        val gate = SpeechGate()
        val actions = (1..600).flatMap { gate.process(ByteArray(3_200)) }
        assertEquals(listOf(GateAction.StopDictation("No speech for 45 seconds")), actions)
        assertEquals(0L, gate.sentSamples)
        val exact = SpeechGate()
        repeat(449) { assertTrue(exact.process(ByteArray(3_200)).isEmpty()) }
        assertTrue(exact.process(ByteArray(3_200)).single() is GateAction.StopDictation)
        assertTrue(exact.process(ByteArray(3_200)).isEmpty())
        println("SpeechGate pure silence: ${gate.sentSeconds} streamed seconds")
    }

    @Test
    fun continuousSpeechStopsExactlyAtFiveMinutesSent() {
        val gate = SpeechGate()
        val chunk = pcm(1) { sample ->
            val time = sample.toDouble() / SpeechGate.SAMPLE_RATE
            if (time % 0.5 < 0.1) 0.0 else 5_000 * sin(2 * PI * 200 * time)
        }
        var stops = 0
        var bytes = 0L
        repeat(310) {
            gate.process(chunk).forEach { action ->
                if (action is GateAction.Send) bytes += action.bytes.size
                if (action is GateAction.StopDictation) stops++
            }
        }
        assertEquals(1, stops)
        assertEquals(300 * 32_000L, bytes)
        assertEquals(300.0, gate.sentSeconds, 0.0)
        println("SpeechGate five-minute sent cap: ${gate.sentSeconds} streamed seconds")
    }

    @Test
    fun elapsedSampleTimeStopsAtTenMinutesEvenWithRegularShortSpeech() {
        val gate = SpeechGate()
        val speech = pcm(1) { sample -> 4_000 * sin(2 * PI * 200 * sample / 16_000) }
        var stop: GateAction.StopDictation? = null
        repeat(600) { second ->
            val actions = gate.process(if (second % 30 == 0) speech else ByteArray(32_000))
            val stops = actions.filterIsInstance<GateAction.StopDictation>()
            if (second < 599) assertTrue(stops.isEmpty())
            if (stops.isNotEmpty()) stop = stops.single()
        }
        assertEquals("Dictation reached 10 minutes", stop?.reason)
        assertTrue(gate.sentSeconds < 300)
        println("SpeechGate ten-minute elapsed cap: ${gate.sentSeconds} streamed seconds")
    }

    @Test
    fun shortSpikesDoNotOpenAndPauseTimeoutRestartsAfterSpeech() {
        val gate = SpeechGate()
        val voiced = pcm(1) { 2_000.0 }.copyOf(640)
        assertTrue(gate.process(voiced).isEmpty())
        assertTrue(gate.process(voiced).isEmpty())
        assertTrue(gate.process(ByteArray(640)).isEmpty())
        val onset = gate.process(voiced + voiced + voiced)
        assertEquals(GateAction.OpenStream, onset.first())
        var elapsed = 0
        val pauses = mutableListOf<GateAction>()
        while (elapsed < 44_980) {
            pauses += gate.process(ByteArray(640))
            elapsed += 20
        }
        assertEquals(1, pauses.count { it == GateAction.Finalize })
        assertEquals(1, pauses.count { it == GateAction.KeepAlive })
        assertEquals(1, pauses.count { it == GateAction.CloseStream })
        assertFalse(pauses.any { it is GateAction.StopDictation })
        assertTrue(gate.process(ByteArray(640)).single() is GateAction.StopDictation)
        println("SpeechGate short spikes and idle: ${gate.sentSeconds} streamed seconds")
    }

    @Test
    fun noiseFloorTracksWindowMinimumOnVoicedFramesAndFallsImmediately() {
        val gate = SpeechGate()
        assertTrue(gate.process(pcm(5) { 250.0 }).isEmpty())
        val probe = pcm(1) { 600.0 }.copyOf(3 * 640)
        assertTrue(gate.process(probe).isEmpty())
        assertTrue(gate.process(ByteArray(640)).isEmpty())
        assertEquals(GateAction.OpenStream, gate.process(probe).first())
        val beforeWindowExpires = gate.process(pcm(1) { 600.0 })
        assertTrue(beforeWindowExpires.all { it is GateAction.Send })
        val ongoing = gate.process(pcm(9) { 600.0 })
        assertEquals(1, ongoing.count { it == GateAction.Finalize })
        assertTrue("Sent ${gate.sentSeconds}s", gate.sentSeconds <= 2.5)
        println("SpeechGate floor rise and fall: ${gate.sentSeconds} streamed seconds")
    }

    @Test
    fun arbitraryByteBoundariesProduceIdenticalAudioAndActions() {
        val input = pcm(3) { sample -> if (sample < 16_000) 0.0 else 2_000.0 }
        val whole = SpeechGate()
        val split = SpeechGate()
        val expected = whole.process(input)
        val actual = input.asList().chunked(137).flatMap { split.process(it.toByteArray()) }
        assertEquals(expected.filterNot { it is GateAction.Send }, actual.filterNot { it is GateAction.Send })
        assertArrayEquals(sentAudio(expected), sentAudio(actual))
        assertEquals(whole.sentSamples, split.sentSamples)
        println("SpeechGate arbitrary byte boundaries: ${whole.sentSeconds} streamed seconds per framing")
    }

    private fun sentAudio(actions: List<GateAction>): ByteArray = ByteArrayOutputStream().apply {
        actions.filterIsInstance<GateAction.Send>().forEach { write(it.bytes) }
    }.toByteArray()

    private fun pcm(seconds: Int, signal: (Int) -> Double): ByteArray {
        val bytes = ByteArray(seconds * 32_000)
        repeat(seconds * 16_000) { index ->
            val value = signal(index).toInt().coerceIn(-32768, 32767)
            bytes[index * 2] = value.toByte()
            bytes[index * 2 + 1] = (value shr 8).toByte()
        }
        return bytes
    }
}
