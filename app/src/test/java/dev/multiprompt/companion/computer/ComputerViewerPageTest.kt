package dev.multiprompt.companion.computer

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class ComputerViewerPageTest {
    @Test fun theViewerOpensViewOnlyThroughTheLocalTunnel() {
        assertEquals(
            "http://127.0.0.1:45123/vnc.html?autoconnect=1&resize=scale&view_only=1",
            ComputerViewerPage.url(45123),
        )
    }

    @Test fun takeControlClearsTheViewerViewOnlySettingAndGiveBackSetsIt() {
        val take = ComputerViewerPage.controlScript(true)
        val give = ComputerViewerPage.controlScript(false)
        assertTrue(take.contains("getElementById('noVNC_setting_view_only')"))
        assertTrue(take.contains("c.checked=false;"))
        assertTrue(take.contains("dispatchEvent(new Event('change'))"))
        assertTrue(give.contains("c.checked=true;"))
        assertTrue(give.contains("k.blur()"))
    }

    @Test fun theKeyboardFocusesTheViewerTypingField() {
        assertTrue(ComputerViewerPage.KEYBOARD_SCRIPT.contains("getElementById('noVNC_keyboardinput')"))
        assertTrue(ComputerViewerPage.KEYBOARD_SCRIPT.contains("k.focus()"))
    }
}
