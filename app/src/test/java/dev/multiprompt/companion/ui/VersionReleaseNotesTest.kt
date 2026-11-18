package dev.multiprompt.companion.ui

import org.junit.Assert.assertEquals
import org.junit.Test

class VersionReleaseNotesTest {
    @Test
    fun descriptionNamesTheVersionAndOpensReleaseNotes() {
        assertEquals(
            "Version 1.2.3-verify, open release notes",
            versionReleaseNotesDescription("1.2.3-verify"),
        )
    }
}
