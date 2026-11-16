package dev.multiprompt.companion.data

import org.junit.Assert.assertEquals
import org.junit.Test

class SessionReadStoreTest {
    @Test
    fun clampsSavedFontScaleToSupportedRange() {
        assertEquals(0.75f, SessionReadStore.normalizeFontScale(0.1f))
        assertEquals(2f, SessionReadStore.normalizeFontScale(2f))
        assertEquals(5f, SessionReadStore.normalizeFontScale(20f))
        assertEquals(1f, SessionReadStore.normalizeFontScale(Float.NaN))
    }

    @Test
    fun waitingSessionReturnsWhenAgentNeedsInput() {
        assertEquals(
            true,
            SessionReadStore.shouldRemainArchived(100, 100, 500, 499, needsAttention = true),
        )
        assertEquals(
            false,
            SessionReadStore.shouldRemainArchived(100, 100, 500, 500, needsAttention = false),
        )
        assertEquals(
            false,
            SessionReadStore.shouldRemainArchived(100, 100, null, 101, needsAttention = true),
        )
        assertEquals(
            true,
            SessionReadStore.shouldRemainArchived(101, 100, null, 101, needsAttention = false),
        )
    }

    @Test
    fun draftKeysListOnlySessionsWithUnsentText() {
        val stored = mapOf(
            "composer_draft::host::alpha" to "half a message",
            "composer_draft::host::beta" to "   ",
            "composer_draft::host::gamma" to "",
            "composer_draft::host::delta" to 7,
            "host::epsilon" to "not a draft",
            "unread_only" to "text",
        )
        assertEquals(setOf("host::alpha"), SessionReadStore.draftKeysFrom(stored))
    }

    @Test
    fun draftKeysAreEmptyOnceTheTextIsClearedOrSent() {
        assertEquals(emptySet<String>(), SessionReadStore.draftKeysFrom(emptyMap<String, Any>()))
        assertEquals(
            emptySet<String>(),
            SessionReadStore.draftKeysFrom(mapOf("composer_draft::host::alpha" to "\n")),
        )
    }
}
