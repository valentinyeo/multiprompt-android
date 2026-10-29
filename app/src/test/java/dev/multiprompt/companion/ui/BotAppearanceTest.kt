package dev.multiprompt.companion.ui

import dev.multiprompt.companion.account.AccountLayout
import org.junit.Assert.*
import org.junit.Test

class BotAppearanceTest {
    @Test fun arbitraryWorkspaceOrderOwnsColorAndSessionOwnsFace() {
        val layout = AccountLayout(workspaces = listOf("Cedar", "Orbit", "Lagoon", "Moss", "Comet", "Coral", "Dune", "River", "Nova"))
        layout.workspaces.forEachIndexed { index, name ->
            val first = BotAppearance.forSession("bot-0", layout.workspaceColor(name))
            val second = BotAppearance.forSession("bot-2", layout.workspaceColor(name))
            assertEquals(index % 7, first.color)
            assertEquals(first.color, second.color)
            assertNotEquals(first.face, second.face)
        }
        assertEquals(7, botColors.distinct().size)
        botColors.forEach { assertFalse(it.red == it.green && it.green == it.blue) }
        val changed = layout.setWorkspaceColor("Cedar", 6)
        assertEquals(6, BotAppearance.forSession("bot-0", changed.workspaceColor("Cedar")).color)
        assertEquals(BotAppearance.forSession("bot-0").face, BotAppearance.forSession("bot-0", 6).face)
        assertEquals(BotAppearance(9, 0), BotAppearance.forSession("机器人"))
    }
}
