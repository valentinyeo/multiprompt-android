package dev.multiprompt.companion.reader

import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class SnapshotFingerprintTest {
    @Test
    fun aRepeatedSnapshotIsTheSameOnlyAfterTheFirstReply() {
        val fingerprint = SnapshotFingerprint()
        assertFalse(fingerprint.same(LongChatSnapshot.built(1, SIZE).reply))
        assertTrue(fingerprint.same(LongChatSnapshot.built(2, SIZE).reply))
        assertTrue(fingerprint.same(LongChatSnapshot.built(97, SIZE).reply))
    }

    @Test
    fun oneNewMessageIsAChange() {
        val fingerprint = SnapshotFingerprint()
        val base = LongChatSnapshot.built(1, SIZE)
        assertFalse(fingerprint.same(base.reply))
        assertTrue(fingerprint.same(LongChatSnapshot.built(2, SIZE).reply))
        assertFalse(fingerprint.same(LongChatSnapshot.built(3, SIZE, 1).reply))
    }

    @Test
    fun aChangedStatusRowIsAChange() {
        val fingerprint = SnapshotFingerprint()
        val base = LongChatSnapshot.built(1, SIZE).reply
        assertFalse(fingerprint.same(base))
        assertTrue(fingerprint.same(LongChatSnapshot.built(2, SIZE).reply))
        assertFalse(fingerprint.same(base.replace("\\\"state\\\":\\\"delivered\\\"", "\\\"state\\\":\\\"queued\\\"")))
    }

    @Test
    fun aChangedProbeIsAChange() {
        val fingerprint = SnapshotFingerprint()
        val base = LongChatSnapshot.built(1, SIZE).reply
        assertFalse(fingerprint.same(base))
        val other = "b".repeat(64)
        assertFalse(fingerprint.same(base.replace(LongChatSnapshot.PROBE, other)))
        assertTrue(fingerprint.same(base.replace(LongChatSnapshot.PROBE, other).replace("\"id\":1", "\"id\":8")))
    }

    @Test
    fun aChangedOffsetIsAChange() {
        val fingerprint = SnapshotFingerprint()
        val base = LongChatSnapshot.built(1, SIZE).reply
        assertFalse(fingerprint.same(base))
        assertFalse(fingerprint.same(base.replace("\\\"offset\\\":0,", "\\\"offset\\\":2,")))
    }

    private companion object {
        const val SIZE = 95 * 1024
    }
}
