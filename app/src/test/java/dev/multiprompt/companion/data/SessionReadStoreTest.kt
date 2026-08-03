package dev.multiprompt.companion.data

import org.junit.Assert.assertEquals
import org.junit.Test

class SessionReadStoreTest {
    @Test
    fun clampsSavedFontScaleToSupportedRange() {
        assertEquals(0.75f, SessionReadStore.normalizeFontScale(0.1f))
        assertEquals(2f, SessionReadStore.normalizeFontScale(2f))
        assertEquals(5f, SessionReadStore.normalizeFontScale(20f))
        assertEquals(1.4f, SessionReadStore.normalizeFontScale(Float.NaN))
    }

    @Test
    fun archivedSessionReturnsAtDeadlineOrNewActivity() {
        assertEquals(
            true,
            SessionReadStore.shouldRemainArchived(100, 100, 500, 499),
        )
        assertEquals(
            false,
            SessionReadStore.shouldRemainArchived(100, 100, 500, 500),
        )
        assertEquals(
            false,
            SessionReadStore.shouldRemainArchived(101, 100, null, 101),
        )
    }
}
