package dev.multiprompt.companion.room

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class RoomMentionsTest {
    private val aliases = listOf("ht-product-lead", "sf-design-lead")

    @Test
    fun aBareAtOpensTheAgentList() {
        assertTrue(roomShowsMentions("@"))
        assertTrue(roomShowsMentions("hello @"))
        assertTrue(roomShowsMentions("hello @sf"))
        assertFalse(roomShowsMentions("hello"))
        assertFalse(roomShowsMentions("mail me at a@b"))
    }

    @Test
    fun mentionsBecomeRecipientsOnlyForTheAgentsNamed() {
        assertEquals(listOf("sf-design-lead"), roomRecipients("ship it @sf-design-lead", aliases))
        assertEquals(aliases, roomRecipients("@ht-product-lead and @sf-design-lead go", aliases))
        assertEquals(emptyList<String>(), roomRecipients("everyone look", aliases))
        assertEquals(emptyList<String>(), roomRecipients("@unknown", aliases))
    }

    @Test
    fun anAddressIsNotAMention() {
        assertEquals(emptyList<String>(), roomRecipients("write to me@host now", aliases))
    }
}
