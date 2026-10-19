package dev.multiprompt.companion.dictation

import kotlin.math.sqrt

sealed class GateAction {
    data object OpenStream : GateAction()
    data class Send(val bytes: ByteArray) : GateAction()
    data object Finalize : GateAction()
    data object KeepAlive : GateAction()
    data object CloseStream : GateAction()
    data class StopDictation(val reason: String) : GateAction()
}

class SpeechGate {
    var sentSamples: Long = 0
        private set
    val sentSeconds: Double get() = sentSamples.toDouble() / SAMPLE_RATE

    private val frame = ByteArray(FRAME_SAMPLES * 2)
    private var frameBytes = 0
    private val preRoll = ArrayDeque<ByteArray>()
    private var elapsedSamples = 0L
    private var lastSpeechSample = 0L
    private var lastKeepAliveSample = 0L
    private val noiseWindow = DoubleArray(NOISE_WINDOW_MS / FRAME_MS) { Double.POSITIVE_INFINITY }
    private var noiseWindowIndex = 0
    private var noiseFloor = ABSOLUTE_MINIMUM / NOISE_MULTIPLIER
    private var voicedFrames = 0
    private var speaking = false
    private var streamOpen = false
    private var stopped = false

    fun process(bytes: ByteArray, count: Int = bytes.size): List<GateAction> {
        require(count in 0..bytes.size)
        val actions = mutableListOf<GateAction>()
        var offset = 0
        while (offset < count && !stopped) {
            val copied = minOf(frame.size - frameBytes, count - offset)
            bytes.copyInto(frame, frameBytes, offset, offset + copied)
            frameBytes += copied
            offset += copied
            if (frameBytes == frame.size) {
                processFrame(actions)
                frameBytes = 0
            }
        }
        return actions
    }

    private fun processFrame(actions: MutableList<GateAction>) {
        elapsedSamples += FRAME_SAMPLES
        var energy = 0.0
        for (index in frame.indices step 2) {
            val sample = ((frame[index].toInt() and 255) or (frame[index + 1].toInt() shl 8)).toShort().toInt()
            energy += sample.toDouble() * sample
        }
        val rms = sqrt(energy / FRAME_SAMPLES)
        noiseWindow[noiseWindowIndex] = rms
        noiseWindowIndex = (noiseWindowIndex + 1) % noiseWindow.size
        val minimum = noiseWindow.min()
        noiseFloor = if (minimum < noiseFloor) minimum else noiseFloor + (minimum - noiseFloor) * FLOOR_RISE_RATE
        val voiced = rms > maxOf(noiseFloor * NOISE_MULTIPLIER, ABSOLUTE_MINIMUM)
        if (!speaking) {
            preRoll.addLast(frame.copyOf())
            if (preRoll.size > PRE_ROLL_MS / FRAME_MS) preRoll.removeFirst()
            voicedFrames = if (voiced) voicedFrames + 1 else 0
            if (voicedFrames >= START_FRAMES) {
                speaking = true
                lastSpeechSample = elapsedSamples
                if (!streamOpen) {
                    streamOpen = true
                    actions += GateAction.OpenStream
                }
                val audio = ByteArray(preRoll.size * frame.size)
                preRoll.forEachIndexed { index, bytes -> bytes.copyInto(audio, index * frame.size) }
                preRoll.clear()
                send(audio, actions)
            }
        } else {
            if (voiced) lastSpeechSample = elapsedSamples
            send(frame.copyOf(), actions)
            if (elapsedSamples - lastSpeechSample >= samples(HANGOVER_MS)) {
                speaking = false
                voicedFrames = 0
                lastKeepAliveSample = elapsedSamples
                actions += GateAction.Finalize
            }
        }
        if (streamOpen && !speaking && !stopped) {
            if (elapsedSamples - lastSpeechSample >= samples(CLOSE_SILENCE_MS)) {
                streamOpen = false
                actions += GateAction.CloseStream
            } else if (elapsedSamples - lastKeepAliveSample >= samples(KEEP_ALIVE_MS)) {
                lastKeepAliveSample = elapsedSamples
                actions += GateAction.KeepAlive
            }
        }
        if (!stopped && elapsedSamples - lastSpeechSample >= samples(NO_SPEECH_TIMEOUT_MS)) {
            stop("No speech for 45 seconds", actions)
        }
        if (!stopped && elapsedSamples >= samples(MAX_DURATION_MS)) {
            stop("Dictation reached 10 minutes", actions)
        }
    }

    private fun send(bytes: ByteArray, actions: MutableList<GateAction>) {
        val remaining = MAX_SENT_SAMPLES - sentSamples
        val size = minOf(bytes.size.toLong(), remaining * 2).toInt()
        if (size > 0) {
            actions += GateAction.Send(if (size == bytes.size) bytes else bytes.copyOf(size))
            sentSamples += size / 2
        }
        if (sentSamples >= MAX_SENT_SAMPLES) stop("Dictation streamed 5 minutes", actions)
    }

    private fun stop(reason: String, actions: MutableList<GateAction>) {
        stopped = true
        actions += GateAction.StopDictation(reason)
    }

    private fun samples(milliseconds: Int): Long = milliseconds.toLong() * SAMPLE_RATE / 1000

    companion object {
        const val SAMPLE_RATE = 16_000
        const val FRAME_MS = 20
        const val FRAME_SAMPLES = SAMPLE_RATE * FRAME_MS / 1000
        const val NOISE_MULTIPLIER = 3.2
        const val ABSOLUTE_MINIMUM = 300.0
        const val NOISE_WINDOW_MS = 1_500
        const val FLOOR_RISE_RATE = 0.1
        const val START_FRAMES = 3
        const val PRE_ROLL_MS = 300
        const val HANGOVER_MS = 600
        const val KEEP_ALIVE_MS = 4_000
        const val CLOSE_SILENCE_MS = 6_000
        const val NO_SPEECH_TIMEOUT_MS = 45_000
        const val MAX_SENT_SAMPLES = 5L * 60 * SAMPLE_RATE
        const val MAX_DURATION_MS = 10 * 60 * 1000
    }
}
