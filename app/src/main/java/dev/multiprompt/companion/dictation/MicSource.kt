package dev.multiprompt.companion.dictation

import android.annotation.SuppressLint
import android.media.AudioFormat
import android.media.AudioRecord
import android.media.MediaRecorder

interface MicSource {
    fun start(): Boolean
    fun read(buffer: ByteArray): Int
    fun stop()
    fun release()
}

internal class AudioRecordMicSource private constructor(private val recorder: AudioRecord) : MicSource {
    @SuppressLint("MissingPermission")
    override fun start(): Boolean {
        recorder.startRecording()
        return recorder.recordingState == AudioRecord.RECORDSTATE_RECORDING
    }

    override fun read(buffer: ByteArray): Int = recorder.read(buffer, 0, buffer.size)
    override fun stop() = recorder.stop()
    override fun release() = recorder.release()

    companion object {
        @SuppressLint("MissingPermission")
        fun create(): MicSource? {
            val minimum = AudioRecord.getMinBufferSize(
                SpeechGate.SAMPLE_RATE,
                AudioFormat.CHANNEL_IN_MONO,
                AudioFormat.ENCODING_PCM_16BIT,
            )
            if (minimum <= 0) return null
            val recorder = AudioRecord(
                MediaRecorder.AudioSource.VOICE_RECOGNITION,
                SpeechGate.SAMPLE_RATE,
                AudioFormat.CHANNEL_IN_MONO,
                AudioFormat.ENCODING_PCM_16BIT,
                maxOf(minimum, 3_200),
            )
            if (recorder.state != AudioRecord.STATE_INITIALIZED) {
                recorder.release()
                return null
            }
            return AudioRecordMicSource(recorder)
        }
    }
}
