package dev.multiprompt.companion.model

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

class RenameCommandTest {
    @Test fun slashRenameGivesTheNewName() {
        assertEquals("Multi Bot Opus Runner", RenameCommand.parse("/rename Multi Bot Opus Runner"))
        assertEquals("Runner", RenameCommand.parse("  /rename   Runner  "))
    }

    @Test fun otherTextIsSentAsAPrompt() {
        assertNull(RenameCommand.parse("/rename"))
        assertNull(RenameCommand.parse("/rename   "))
        assertNull(RenameCommand.parse("/renamex Runner"))
        assertNull(RenameCommand.parse("please /rename Runner"))
        assertNull(RenameCommand.parse("/rename one\ntwo"))
        assertNull(RenameCommand.parse("/model opus"))
    }
}
