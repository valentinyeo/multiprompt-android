package dev.multiprompt.companion.ssh

import java.io.IOException
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.runBlocking
import org.junit.Assert.assertSame
import org.junit.Assert.fail
import org.junit.Test

class OpenSshChannelTest {
    @Test
    fun serverChannelRefusalsAreMarkedUnsentBeforeAnyCommandRuns() = runBlocking {
        listOf(
            IOException("channel 10: open failed: connect failed: open failed"),
            IOException("administratively prohibited"),
            IOException("wrapped", IOException("SSH_OPEN_ADMINISTRATIVELY_PROHIBITED: open failed")),
        ).forEach { refusal ->
            try {
                openSshChannel<Any> { throw refusal }
                fail("A refused channel cannot run a command")
            } catch (error: SshProblem.LinkUnavailable) {
                assertSame(refusal, error.cause)
            }
        }
        try {
            openSshChannel<Any> { null }
            fail("A missing channel cannot run a command")
        } catch (_: SshProblem.LinkUnavailable) {
        }
    }

    @Test
    fun unrelatedFailuresAndCancellationAreNeverClassifiedAsChannelCapacity() = runBlocking {
        listOf(IOException("key exchange failed"), CancellationException("open failed")).forEach { failure ->
            try {
                openSshChannel<Any> { throw failure }
                fail("Expected the original failure")
            } catch (error: Exception) {
                assertSame(failure, error)
            }
        }
    }
}
