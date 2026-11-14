package dev.multiprompt.companion.skills

import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.TimeoutCancellationException
import kotlinx.coroutines.delay

object SkillsLoader {
    const val TIMEOUT_MESSAGE = "Skills took too long. Tap to retry."
    const val RETRY_DELAY_MILLIS = 800L

    sealed interface Outcome {
        data class Loaded(val skills: List<NativeSkill>) : Outcome
        data class Failed(val message: String) : Outcome
    }

    suspend fun load(retryDelayMillis: Long = RETRY_DELAY_MILLIS, fetch: suspend () -> List<NativeSkill>): Outcome {
        var failure: Exception? = null
        for (attempt in 0..1) {
            try {
                return Outcome.Loaded(fetch())
            } catch (_: TimeoutCancellationException) {
                return Outcome.Failed(TIMEOUT_MESSAGE)
            } catch (cancelled: CancellationException) {
                throw cancelled
            } catch (error: Exception) {
                failure = error
            }
            if (attempt == 0) delay(retryDelayMillis)
        }
        return Outcome.Failed(describe(requireNotNull(failure)))
    }

    fun describe(failure: Exception): String {
        val reason = failure.message?.lineSequence()?.firstOrNull { it.isNotBlank() }?.trim().orEmpty().take(200)
        return if (reason.isEmpty()) "Could not load skills (tap to retry)" else "Could not load skills: $reason (tap to retry)"
    }
}
