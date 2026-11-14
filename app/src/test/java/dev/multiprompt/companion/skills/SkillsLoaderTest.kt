package dev.multiprompt.companion.skills

import dev.multiprompt.companion.ssh.SshProblem
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.async
import kotlinx.coroutines.cancelAndJoin
import kotlinx.coroutines.delay
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withTimeout
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class SkillsLoaderTest {
    private val skills = listOf(NativeSkill("review", "Check it"))

    @Test fun retriesOnceAfterAFailureAndThenSucceeds() = runBlocking {
        var calls = 0
        val outcome = SkillsLoader.load(retryDelayMillis = 0) {
            if (++calls == 1) throw SshProblem.Connection("Connection reset") else skills
        }
        assertEquals(SkillsLoader.Outcome.Loaded(skills), outcome)
        assertEquals(2, calls)
    }

    @Test fun reportsTheRealReasonAfterTheRetryAlsoFails() = runBlocking {
        var calls = 0
        val outcome = SkillsLoader.load(retryDelayMillis = 0) {
            calls++
            throw SshProblem.Connection("Could not read this session’s native skills: python3: command not found")
        }
        assertEquals(2, calls)
        assertEquals(
            SkillsLoader.Outcome.Failed("Could not load skills: Could not read this session’s native skills: python3: command not found (tap to retry)"),
            outcome,
        )
    }

    @Test fun failureWithoutAMessageStillSaysSomethingPlain() = runBlocking {
        val outcome = SkillsLoader.load(retryDelayMillis = 0) { throw IllegalStateException() }
        assertEquals(SkillsLoader.Outcome.Failed("Could not load skills (tap to retry)"), outcome)
    }

    @Test fun timeoutGivesThePlainMessageWithoutRetrying() = runBlocking {
        var calls = 0
        val outcome = SkillsLoader.load(retryDelayMillis = 0) {
            calls++
            withTimeout(20) { delay(5_000) }
            skills
        }
        assertEquals(SkillsLoader.Outcome.Failed("Skills took too long. Tap to retry."), outcome)
        assertEquals(1, calls)
    }

    @Test fun realCancellationStillPropagates() = runBlocking {
        val started = CompletableDeferred<Unit>()
        val job = async {
            SkillsLoader.load(retryDelayMillis = 0) {
                started.complete(Unit)
                delay(60_000)
                skills
            }
        }
        started.await()
        job.cancelAndJoin()
        assertTrue(job.isCancelled)
        assertTrue(runCatching { job.await() }.exceptionOrNull() is CancellationException)
    }
}
