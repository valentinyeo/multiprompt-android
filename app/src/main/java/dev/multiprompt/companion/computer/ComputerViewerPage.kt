package dev.multiprompt.companion.computer

data class ComputerControl(val enabled: Boolean = false, val keyboardRequests: Int = 0)

object ComputerViewerPage {
    fun url(localPort: Int): String =
        "http://${ComputerTarget.DEFAULT_HOST}:$localPort/vnc.html?autoconnect=1&resize=scale&view_only=1"

    fun controlScript(enabled: Boolean): String =
        "(function(){" +
            "var c=document.getElementById('noVNC_setting_view_only');" +
            "if(!c)return false;" +
            "c.checked=${!enabled};" +
            "c.dispatchEvent(new Event('change'));" +
            (if (enabled) "" else "var k=document.getElementById('noVNC_keyboardinput');if(k)k.blur();") +
            "return true;" +
            "})()"

    const val KEYBOARD_SCRIPT: String =
        "(function(){" +
            "var k=document.getElementById('noVNC_keyboardinput');" +
            "if(!k)return false;" +
            "k.focus();" +
            "return true;" +
            "})()"
}
