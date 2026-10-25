package dev.multiprompt.companion.dictation

import kotlin.math.log10
import kotlin.math.sqrt

object AudioLevel {
    private const val FLOOR_DB = -50.0

    fun of(pcm16le: ByteArray, byteCount: Int): Float {
        val samples = minOf(byteCount, pcm16le.size) / 2
        if (samples == 0) return 0f
        var sum = 0.0
        for (i in 0 until samples) {
            val value = (pcm16le[2 * i].toInt() and 0xFF) or (pcm16le[2 * i + 1].toInt() shl 8)
            sum += value.toDouble() * value
        }
        val rms = sqrt(sum / samples) / Short.MAX_VALUE
        if (rms <= 0.0) return 0f
        val db = 20 * log10(rms)
        return ((db - FLOOR_DB) / -FLOOR_DB).coerceIn(0.0, 1.0).toFloat()
    }

    fun timer(elapsedMillis: Long): String {
        val seconds = (elapsedMillis.coerceAtLeast(0) / 1000)
        return "%d:%02d".format(seconds / 60, seconds % 60)
    }

    fun litDots(history: List<Float>, dots: Int): List<Boolean> =
        List(dots) { index -> history.getOrElse(history.size - dots + index) { 0f } > 0.08f }
}
